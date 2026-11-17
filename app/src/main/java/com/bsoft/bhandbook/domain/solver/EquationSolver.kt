package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import java.math.BigInteger

/** Solves linear and quadratic equations in one variable with exact rational arithmetic and justified steps. */
internal class EquationSolver(private val engine: MathCalculationEngine = ExactCalculationEngine()) {

    private class Ctx(val v: String, val decimal: Boolean)

    fun solve(lhs: Expr, rhs: Expr): SolveOutcome {
        return try {
            val vars = Poly.variablesOf(lhs).toMutableSet().also { Poly.variablesOf(rhs, it) }
            if (vars.size > 1) return SolveOutcome.Unsupported("Equations with more than one variable are not supported yet.")
            if (vars.isEmpty()) return numericCheck(lhs, rhs)
            val v = vars.single()
            val ctx = Ctx(v, Poly.hasDecimalLiteral(lhs) || Poly.hasDecimalLiteral(rhs))
            val l = Poly.from(lhs, v, engine); val r = Poly.from(rhs, v, engine)
            val original = Printer.print(normalizeLiterals(lhs)) + " = " + Printer.print(normalizeLiterals(rhs))
            val maxDeg = maxOf(l.degree, r.degree)
            when {
                maxDeg <= 1 -> linear(ctx, l, r, original)
                maxDeg == 2 -> quadratic(ctx, l, r, original)
                else -> SolveOutcome.Unsupported("Equations of degree $maxDeg are not supported yet.")
            }
        } catch (e: UnsupportedMath) { SolveOutcome.Unsupported(e.message ?: "unsupported") }
        catch (e: InvalidMath) { SolveOutcome.Invalid(e.message ?: "invalid") }
    }

    private fun numericCheck(lhs: Expr, rhs: Expr): SolveOutcome {
        val a = engine.evaluate(lhs); val b = engine.evaluate(rhs)
        if (a is CalcResult.Failure) return SolveOutcome.Invalid(a.message)
        if (b is CalcResult.Failure) return SolveOutcome.Invalid(b.message)
        val x = (a as CalcResult.Success).value; val y = (b as CalcResult.Success).value
        val equal = if (x is Value.Exact && y is Value.Exact) x.value == y.value else Math.abs(x.toDouble() - y.toDouble()) < 1e-9
        val text = Printer.print(lhs) + " = " + Printer.print(rhs)
        val step = SolutionStep(1, text, if (equal) "Both sides are equal, so the statement is true" else "The two sides are different, so the statement is false", if (equal) "true" else "false")
        return if (equal) SolveOutcome.Identity(listOf(step)) else SolveOutcome.NoSolution(listOf(step), "The statement is false")
    }

    // ------------------------------------------------------------------ formatting
    private fun fmt(r: Rational, c: Ctx): String {
        if (r.isInteger) return r.num.toString()
        if (c.decimal) Printer.decimalOrNull(r, 10)?.let { return it }
        return "${r.num}/${r.den}"
    }
    private fun wrapped(r: Rational, c: Ctx) = fmt(r, c).let { if (r.signum < 0 || !r.isInteger && !(c.decimal && Printer.decimalOrNull(r, 10) != null)) "($it)" else it }

    /** Coefficient + variable text: 2x, -x, x/2, 3x/4, 0.5x. */
    private fun termText(coef: Rational, power: Int, c: Ctx): String {
        val x = when (power) { 0 -> ""; 1 -> c.v; else -> "${c.v}^$power" }
        if (power == 0) return fmt(coef, c)
        val abs = coef.abs(); val sign = if (coef.signum < 0) "-" else ""
        return when {
            abs == Rational.ONE -> sign + x
            abs.isInteger -> sign + abs.num + x
            c.decimal && Printer.decimalOrNull(abs, 10) != null -> sign + fmt(abs, c) + x
            abs.num == BigInteger.ONE -> "$sign$x/${abs.den}"
            else -> "$sign${abs.num}$x/${abs.den}"
        }
    }

    private fun polyText(p: Poly, c: Ctx): String {
        val parts = (p.degree downTo 0).filter { !p.coef(it).isZero }
        if (parts.isEmpty()) return "0"
        val sb = StringBuilder()
        for ((i, d) in parts.withIndex()) {
            val co = p.coef(d)
            if (i == 0) sb.append(termText(co, d, c))
            else sb.append(if (co.signum < 0) " - " else " + ").append(termText(co.abs(), d, c))
        }
        return sb.toString()
    }

    /** Signed sum of already-formatted magnitudes: [(+, "15"), (-, "5")] -> "15 - 5". */
    private fun joinSigned(items: List<Pair<Boolean, String>>): String {
        if (items.isEmpty()) return "0"
        val sb = StringBuilder()
        for ((i, it) in items.withIndex()) {
            if (i == 0) sb.append(if (it.first) it.second else "-" + it.second)
            else sb.append(if (it.first) " + " else " - ").append(it.second)
        }
        return sb.toString()
    }

    private fun sameText(a: String, b: String) = a.replace(" ", "") == b.replace(" ", "")

    // ------------------------------------------------------------------ linear
    private fun linear(ctx: Ctx, l0: Poly, r0: Poly, original: String): SolveOutcome {
        val steps = mutableListOf<SolutionStep>()
        var shown = original
        fun add(explanation: String, resulting: String) {
            if (sameText(shown, resulting)) return
            steps += SolutionStep(steps.size + 1, shown, explanation, resulting); shown = resulting
        }
        var l = l0; var r = r0
        val simplified = "${polyText(l, ctx)} = ${polyText(r, ctx)}"
        add("Expand brackets and combine like terms", simplified)

        if (l.coef(1).isZero && !r.coef(1).isZero) {
            val t = l; l = r; r = t
            add("Swap sides so the variable is on the left", "${polyText(l, ctx)} = ${polyText(r, ctx)}")
        }
        val a = l.coef(1); val b = l.coef(0); val c = r.coef(1); val d = r.coef(0)

        // Move variable terms left and constants right.
        if (!c.isZero || !b.isZero) {
            val lhsItems = mutableListOf<Pair<Boolean, String>>()
            if (!a.isZero) lhsItems += (a.signum >= 0) to termText(a.abs(), 1, ctx)
            if (!c.isZero) lhsItems += (c.signum < 0) to termText(c.abs(), 1, ctx)
            if (lhsItems.isEmpty()) lhsItems += true to "0"
            val rhsItems = mutableListOf<Pair<Boolean, String>>()
            if (!d.isZero) rhsItems += (d.signum >= 0) to fmt(d.abs(), ctx)
            if (!b.isZero) rhsItems += (b.signum < 0) to fmt(b.abs(), ctx)
            add("Move the variable terms to the left and the constants to the right", "${joinSigned(lhsItems)} = ${joinSigned(rhsItems)}")
        }
        val k = a - c; val m = d - b
        add("Combine like terms", "${termTextOrZero(k, ctx)} = ${fmt(m, ctx)}")

        if (k.isZero) {
            return if (m.isZero) SolveOutcome.Identity(steps + SolutionStep(steps.size + 1, shown, "Both sides are always equal, so every value works", "true"))
            else SolveOutcome.NoSolution(steps + SolutionStep(steps.size + 1, shown, "This is never true, so there is no solution", "false"), "no solution")
        }
        val result = m / k
        if (k != Rational.ONE) {
            add("Divide both sides by ${fmt(k, ctx)}", "${ctx.v} = ${fmt(m, ctx)} / ${wrapped(k, ctx)}")
            val alreadyReduced = m.isInteger && k.isInteger && k.signum > 0 && m.num.gcd(k.num) == BigInteger.ONE && k != Rational.ONE
            if (!alreadyReduced) add("Simplify", "${ctx.v} = ${fmt(result, ctx)}")
        }
        val sol = fmt(result, ctx)
        return SolveOutcome.Solved(steps, "${ctx.v} = $sol", listOf(sol))
    }

    private fun termTextOrZero(k: Rational, c: Ctx) = if (k.isZero) "0" else termText(k, 1, c)

    // --------------------------------------------------------------- quadratic
    private fun quadratic(ctx: Ctx, l: Poly, r: Poly, original: String): SolveOutcome {
        val steps = mutableListOf<SolutionStep>()
        var shown = original
        fun add(explanation: String, resulting: String) {
            if (sameText(shown, resulting)) return
            steps += SolutionStep(steps.size + 1, shown, explanation, resulting); shown = resulting
        }
        val p = l - r
        add("Move all terms to one side", "${polyText(p, ctx)} = 0")
        if (p.degree < 2) return linear(ctx, p, Poly.ZERO, shown).let { prependSteps(steps, it) }

        val a = p.coef(2); val b = p.coef(1); val c = p.coef(0)
        val disc = b * b - Rational.of(4) * a * c
        val bTxt = wrapped(b, ctx)
        add("Compute the discriminant D = b² − 4ac", "D = $bTxt^2 - 4 × ${wrapped(a, ctx)} × ${wrapped(c, ctx)}")
        add("Evaluate the discriminant", "D = ${fmt(disc, ctx)}")

        if (disc.signum < 0) {
            steps += SolutionStep(steps.size + 1, shown, "D is negative, so there are no real solutions", "no real solutions")
            return SolveOutcome.NoSolution(steps, "negative discriminant")
        }
        val twoA = Rational.of(2) * a
        val negB = -b
        if (disc.isZero) {
            add("D = 0, so there is exactly one solution x = -b / 2a", "${ctx.v} = ${fmt(negB, ctx)} / (2 × ${wrapped(a, ctx)})")
            val x = negB / twoA
            add("Simplify", "${ctx.v} = ${fmt(x, ctx)}")
            return SolveOutcome.Solved(steps, "${ctx.v} = ${fmt(x, ctx)}", listOf(fmt(x, ctx)))
        }
        add("Apply the quadratic formula x = (-b ± √D) / 2a", "${ctx.v} = (${fmt(negB, ctx)} ± √${fmtRoot(disc, ctx)}) / (2 × ${wrapped(a, ctx)})")
        val root = disc.exactSqrt()
        if (root != null) {
            val x1 = (negB - root) / twoA; val x2 = (negB + root) / twoA
            val sorted = listOf(x1, x2).sorted()
            val final = "${ctx.v} = ${fmt(sorted[0], ctx)} or ${ctx.v} = ${fmt(sorted[1], ctx)}"
            add("Evaluate both roots", final)
            return SolveOutcome.Solved(steps, final, sorted.map { fmt(it, ctx) })
        }
        // irrational roots: keep them exact
        val sq = "√${fmtRoot(disc, ctx)}"
        fun rootText(sign: String): String {
            val num = "${fmt(negB, ctx)} $sign $sq"
            return if (twoA == Rational.ONE) num else "($num) / ${wrapped(twoA, ctx)}"
        }
        val x1 = negB.toDouble().let { (it - Math.sqrt(disc.toDouble())) / twoA.toDouble() }
        val x2 = negB.toDouble().let { (it + Math.sqrt(disc.toDouble())) / twoA.toDouble() }
        val ordered = if (x1 <= x2) listOf(rootText("-"), rootText("+")) else listOf(rootText("+"), rootText("-"))
        val final = "${ctx.v} = ${ordered[0]} or ${ctx.v} = ${ordered[1]}"
        add("The square root of D is not a whole number, so the roots stay exact", final)
        return SolveOutcome.Solved(steps, final, ordered)
    }

    private fun fmtRoot(r: Rational, c: Ctx) = fmt(r, c).let { if (r.isInteger) it else "($it)" }

    private fun prependSteps(prefix: List<SolutionStep>, o: SolveOutcome): SolveOutcome {
        fun renumber(extra: List<SolutionStep>): List<SolutionStep> =
            (prefix + extra).mapIndexed { i, s -> s.copy(number = i + 1) }
        return when (o) {
            is SolveOutcome.Solved -> o.copy(steps = renumber(o.steps))
            is SolveOutcome.NoSolution -> o.copy(steps = renumber(o.steps))
            is SolveOutcome.Identity -> o.copy(steps = renumber(o.steps))
            else -> o
        }
    }
}

class DefaultSolutionEngine : MathSolutionEngine {
    override fun solveSystem(equations: List<MathStatement.Equation>, calc: CalcSettings): SolveOutcome =
        SolutionFormat.apply(LinearSystemSolver(ExactCalculationEngine(calc.angle)).solve(equations), calc)

    override fun solve(statement: MathStatement, calc: CalcSettings): SolveOutcome {
        val engine = ExactCalculationEngine(calc.angle)
        return when (statement) {
            is MathStatement.Expression -> ArithmeticStepper(engine, calc).solve(statement.expr)
            is MathStatement.Equation -> SolutionFormat.apply(EquationSolver(engine).solve(statement.lhs, statement.rhs), calc)
        }
    }
}
