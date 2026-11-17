package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import java.math.BigInteger

/** A linear expression in any number of unknowns: sum of coeffs[name] * name + constant (exact rationals). */
internal class LinearForm(val coeffs: Map<String, Rational>, val constant: Rational) {
    val isConstant get() = coeffs.values.all { it.isZero }
    operator fun plus(o: LinearForm) = LinearForm((coeffs.keys + o.coeffs.keys).associateWith { (coeffs[it] ?: Rational.ZERO) + (o.coeffs[it] ?: Rational.ZERO) }, constant + o.constant)
    operator fun minus(o: LinearForm) = this + o.scaled(Rational.of(-1))
    fun scaled(k: Rational) = LinearForm(coeffs.mapValues { it.value * k }, constant * k)
}

internal object LinearForms {
    private val defaultEngine: MathCalculationEngine = ExactCalculationEngine()

    /** Converts [e] to a linear form; throws [UnsupportedMath] for anything non-linear and [InvalidMath] for undefined arithmetic. */
    fun from(e: Expr, engine: MathCalculationEngine = defaultEngine): LinearForm = when (e) {
        is Num -> LinearForm(emptyMap(), e.value)
        is Var -> LinearForm(mapOf(e.name to Rational.ONE), Rational.ZERO)
        is Const -> throw UnsupportedMath("constants like π are not supported in systems yet")
        is Neg -> from(e.operand, engine).scaled(Rational.of(-1))
        is Group -> from(e.inner, engine)
        is Bin -> when (e.op) {
            Op.ADD -> from(e.left, engine) + from(e.right, engine)
            Op.SUB -> from(e.left, engine) - from(e.right, engine)
            Op.MUL -> {
                val l = from(e.left, engine); val r = from(e.right, engine)
                when { l.isConstant -> r.scaled(l.constant); r.isConstant -> l.scaled(r.constant); else -> throw UnsupportedMath("a product of unknowns is not linear") }
            }
            Op.DIV -> {
                val d = from(e.right, engine)
                if (!d.isConstant) throw UnsupportedMath("an unknown in a denominator is not linear")
                if (d.constant.isZero) throw InvalidMath("division by zero")
                from(e.left, engine).scaled(Rational.ONE / d.constant)
            }
        }
        is Pow -> {
            val ex = from(e.exponent, engine); val base = from(e.base, engine)
            if (!ex.isConstant) throw UnsupportedMath("an unknown in an exponent is not linear")
            val n = ex.constant
            when {
                n == Rational.ONE -> base
                n.isZero -> LinearForm(emptyMap(), Rational.ONE)
                base.isConstant && n.isInteger && n.num.bitLength() <= 7 -> LinearForm(emptyMap(), base.constant.pow(n.num.toInt()))
                base.isConstant -> constantOf(e, engine)
                else -> throw UnsupportedMath("a power of an unknown is not linear")
            }
        }
        is Func -> if (Poly.variablesOf(e).isNotEmpty()) throw UnsupportedMath("a function of an unknown (${e.name}) is not linear") else constantOf(e, engine)
    }

    private fun constantOf(e: Expr, engine: MathCalculationEngine): LinearForm = when (val r = engine.evaluate(e)) {
        is CalcResult.Success -> when (val v = r.value) {
            is Value.Exact -> LinearForm(emptyMap(), v.value)
            is Value.Approx -> throw UnsupportedMath("irrational constants are not supported in systems yet")
        }
        is CalcResult.Failure -> if (r.error == CalcError.DIVISION_BY_ZERO || r.error == CalcError.DOMAIN) throw InvalidMath(r.message) else throw UnsupportedMath(r.message)
    }
}

/** Number formatting shared by algebra output. */
internal object AlgebraText {
    fun rational(r: Rational, decimal: Boolean): String {
        if (r.isInteger) return r.num.toString()
        if (decimal) Printer.decimalOrNull(r, 10)?.let { return it }
        return "${r.num}/${r.den}"
    }

    /** 2x, -x, x/2, 3x/4, 0.5x (coefficient magnitude and sign handled by the caller for non-leading terms). */
    fun term(coef: Rational, name: String, decimal: Boolean): String {
        val abs = coef.abs(); val sign = if (coef.signum < 0) "-" else ""
        return when {
            abs == Rational.ONE -> sign + name
            abs.isInteger -> sign + abs.num + name
            decimal && Printer.decimalOrNull(abs, 10) != null -> sign + rational(abs, true) + name
            abs.num == BigInteger.ONE -> "$sign$name/${abs.den}"
            else -> "$sign${abs.num}$name/${abs.den}"
        }
    }

    /** "2x - y + 3z" from coefficients (zero terms omitted); "0" if there are none. */
    fun sum(coeffs: List<Rational>, names: List<String>, decimal: Boolean): String {
        val sb = StringBuilder()
        for (i in coeffs.indices) {
            val c = coeffs[i]; if (c.isZero) continue
            if (sb.isEmpty()) sb.append(term(c, names[i], decimal)) else sb.append(if (c.signum < 0) " - " else " + ").append(term(c.abs(), names[i], decimal))
        }
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    /** "2 - y" style affine expression: constant first, then terms. */
    fun affine(constant: Rational, coeffs: List<Rational>, names: List<String>, decimal: Boolean): String {
        val sb = StringBuilder()
        if (!constant.isZero) sb.append(rational(constant, decimal))
        for (i in coeffs.indices) {
            val c = coeffs[i]; if (c.isZero) continue
            if (sb.isEmpty()) sb.append(term(c, names[i], decimal)) else sb.append(if (c.signum < 0) " - " else " + ").append(term(c.abs(), names[i], decimal))
        }
        return if (sb.isEmpty()) "0" else sb.toString()
    }
}

/** Solves systems of linear equations (up to [MAX_UNKNOWNS] unknowns) by Gauss-Jordan elimination, narrating every row operation. */
internal class LinearSystemSolver(private val engine: MathCalculationEngine = ExactCalculationEngine()) {
    private class Row(val coeffs: MutableList<Rational>, var rhs: Rational)

    fun solve(equations: List<MathStatement.Equation>): SolveOutcome = try { run(equations) } catch (e: UnsupportedMath) { SolveOutcome.Unsupported(e.message ?: "unsupported") } catch (e: InvalidMath) { SolveOutcome.Invalid(e.message ?: "invalid") }

    private fun run(equations: List<MathStatement.Equation>): SolveOutcome {
        val forms = equations.map { LinearForms.from(it.lhs, engine) - LinearForms.from(it.rhs, engine) }          // each equals 0
        val names = forms.flatMap { it.coeffs.filterValues { c -> !c.isZero }.keys }.distinct().sortedWith(compareBy({ ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }, { it }))
        if (names.size > MAX_UNKNOWNS) return SolveOutcome.Unsupported("Systems with more than $MAX_UNKNOWNS unknowns are not supported yet.")
        val decimal = equations.any { Poly.hasDecimalLiteral(it.lhs) || Poly.hasDecimalLiteral(it.rhs) }
        val rows = forms.map { f -> Row(names.map { f.coeffs[it] ?: Rational.ZERO }.toMutableList(), -f.constant) }.toMutableList()
        fun text(r: Row) = AlgebraText.sum(r.coeffs, names, decimal) + " = " + AlgebraText.rational(r.rhs, decimal)

        val steps = mutableListOf<SolutionStep>()
        fun step(explanation: String, before: String, after: String) { if (before.replace(" ", "") != after.replace(" ", "") || explanation.startsWith("Swap")) steps += SolutionStep(steps.size + 1, before, explanation, after) }

        // 1. standard form of every equation the user wrote
        equations.forEachIndexed { i, eq ->
            val written = Printer.print(normalizeLiterals(eq.lhs)) + " = " + Printer.print(normalizeLiterals(eq.rhs))
            if (rows[i].coeffs.any { !it.isZero }) step("Write equation ${i + 1} in standard form", written, text(rows[i]))
        }

        // 2. Gauss-Jordan
        val pivotColumns = ArrayList<Int>(); var pivotRow = 0
        for (col in names.indices) {
            val found = (pivotRow until rows.size).firstOrNull { !rows[it].coeffs[col].isZero } ?: continue
            if (found != pivotRow) {
                val before = text(rows[pivotRow]); val t = rows[found]; rows[found] = rows[pivotRow]; rows[pivotRow] = t
                step("Swap equations ${pivotRow + 1} and ${found + 1} so that the coefficient of ${names[col]} is not zero", before, text(rows[pivotRow]))
            }
            val p = rows[pivotRow].coeffs[col]
            if (p != Rational.ONE) {
                val before = text(rows[pivotRow])
                for (j in rows[pivotRow].coeffs.indices) rows[pivotRow].coeffs[j] = rows[pivotRow].coeffs[j] / p
                rows[pivotRow].rhs = rows[pivotRow].rhs / p
                step("Divide equation ${pivotRow + 1} by ${AlgebraText.rational(p, decimal)} so that the coefficient of ${names[col]} is 1", before, text(rows[pivotRow]))
            }
            for (i in rows.indices) {
                if (i == pivotRow) continue
                val f = rows[i].coeffs[col]; if (f.isZero) continue
                val before = text(rows[i])
                for (j in rows[i].coeffs.indices) rows[i].coeffs[j] = rows[i].coeffs[j] - f * rows[pivotRow].coeffs[j]
                rows[i].rhs = rows[i].rhs - f * rows[pivotRow].rhs
                val k = AlgebraText.rational(f.abs(), decimal).let { if (f.abs() == Rational.ONE) "" else "$it × " }
                step("${if (f.signum > 0) "Subtract" else "Add"} ${k}equation ${pivotRow + 1} ${if (f.signum > 0) "from" else "to"} equation ${i + 1} to eliminate ${names[col]}", before, text(rows[i]))
            }
            pivotColumns += col; pivotRow++
        }

        // 3. consistency
        for (i in pivotRow until rows.size) {
            if (rows[i].coeffs.all { it.isZero } && !rows[i].rhs.isZero) {
                steps += SolutionStep(steps.size + 1, text(rows[i]), "This equation can never be true, so the system has no solution", text(rows[i]))
                return SolveOutcome.NoSolution(steps, "inconsistent equations")
            }
        }
        if (names.isEmpty()) return SolveOutcome.Unsupported("There are no unknowns to solve for.")

        // 4. read the solution
        if (pivotColumns.size == names.size) {
            val sols = names.indices.map { j -> "${names[j]} = ${AlgebraText.rational(rows[pivotColumns.indexOf(j)].rhs, decimal)}" }
            return SolveOutcome.Solved(steps, sols.joinToString(", "), sols)
        }
        val free = names.indices.filter { it !in pivotColumns }
        val sols = pivotColumns.mapIndexed { r, col ->
            "${names[col]} = " + AlgebraText.affine(rows[r].rhs, free.map { -rows[r].coeffs[it] }, free.map { names[it] }, decimal)
        }
        val freeText = free.joinToString(", ") { names[it] }
        steps += SolutionStep(steps.size + 1, sols.first(), "There are fewer independent equations than unknowns: $freeText can be any value", sols.first())
        return SolveOutcome.Solved(steps, "infinitely many solutions: ${sols.joinToString(", ")}", sols)
    }

    companion object {
        const val MAX_UNKNOWNS = 4
        private val ORDER = listOf("x", "y", "z", "a", "b", "c", "t", "u", "v", "w")
    }
}
