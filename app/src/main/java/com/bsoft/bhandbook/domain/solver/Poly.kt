package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*

/** Thrown while analysing an expression; mapped to [SolveOutcome.Unsupported] / [SolveOutcome.Invalid]. */
internal class UnsupportedMath(message: String) : RuntimeException(message, null, false, false)
internal class InvalidMath(message: String) : RuntimeException(message, null, false, false)

/** Polynomial in one variable with exact rational coefficients; index = degree. */
internal class Poly(coeffs: List<Rational>) {
    val c: List<Rational> = coeffs.dropLastWhile { it.isZero }
    val degree get() = if (c.isEmpty()) 0 else c.size - 1
    val isZero get() = c.isEmpty()
    fun coef(i: Int) = c.getOrElse(i) { Rational.ZERO }

    operator fun plus(o: Poly) = Poly(List(maxOf(c.size, o.c.size)) { coef(it) + o.coef(it) })
    operator fun minus(o: Poly) = Poly(List(maxOf(c.size, o.c.size)) { coef(it) - o.coef(it) })
    operator fun unaryMinus() = Poly(c.map { -it })
    operator fun times(o: Poly): Poly {
        if (isZero || o.isZero) return ZERO
        val out = MutableList(c.size + o.c.size - 1) { Rational.ZERO }
        for (i in c.indices) for (j in o.c.indices) out[i + j] = out[i + j] + c[i] * o.c[j]
        return Poly(out)
    }
    fun scaled(k: Rational) = Poly(c.map { it * k })

    companion object {
        val ZERO = Poly(emptyList())
        fun const(r: Rational) = Poly(listOf(r))
        val X = Poly(listOf(Rational.ZERO, Rational.ONE))
        const val MAX_DEGREE = 8

        fun variablesOf(e: Expr, into: MutableSet<String> = linkedSetOf()): Set<String> {
            when (e) {
                is Var -> into += e.name
                is Neg -> variablesOf(e.operand, into)
                is Group -> variablesOf(e.inner, into)
                is Bin -> { variablesOf(e.left, into); variablesOf(e.right, into) }
                is Pow -> { variablesOf(e.base, into); variablesOf(e.exponent, into) }
                is Func -> e.args.forEach { variablesOf(it, into) }
                is Num, is Const -> {}
            }
            return into
        }

        fun hasDecimalLiteral(e: Expr): Boolean = when (e) {
            is Num -> e.decimal
            is Neg -> hasDecimalLiteral(e.operand)
            is Group -> hasDecimalLiteral(e.inner)
            is Bin -> hasDecimalLiteral(e.left) || hasDecimalLiteral(e.right)
            is Pow -> hasDecimalLiteral(e.base) || hasDecimalLiteral(e.exponent)
            is Func -> e.args.any { hasDecimalLiteral(it) }
            else -> false
        }

        private val defaultEngine: MathCalculationEngine = ExactCalculationEngine()

        /** Converts [e] to a polynomial in [v]; throws [UnsupportedMath] / [InvalidMath] when that is impossible. */
        fun from(e: Expr, v: String, engine: MathCalculationEngine = defaultEngine): Poly = when (e) {
            is Num -> const(e.value)
            is Var -> if (e.name == v) X else throw UnsupportedMath("more than one variable")
            is Const -> throw UnsupportedMath("constants like π are not supported in equations yet")
            is Neg -> -from(e.operand, v, engine)
            is Group -> from(e.inner, v, engine)
            is Bin -> when (e.op) {
                Op.ADD -> from(e.left, v, engine) + from(e.right, v, engine)
                Op.SUB -> from(e.left, v, engine) - from(e.right, v, engine)
                Op.MUL -> (from(e.left, v, engine) * from(e.right, v, engine)).also { if (it.degree > MAX_DEGREE) throw UnsupportedMath("degree too high") }
                Op.DIV -> {
                    val d = from(e.right, v, engine)
                    if (d.degree > 0) throw UnsupportedMath("a variable in a denominator is not supported yet")
                    if (d.isZero) throw InvalidMath("division by zero")
                    from(e.left, v, engine).scaled(Rational.ONE / d.coef(0))
                }
            }
            is Pow -> {
                val ex = from(e.exponent, v, engine)
                if (ex.degree > 0) throw UnsupportedMath("a variable in an exponent is not supported yet")
                val n = ex.coef(0)
                val base = from(e.base, v, engine)
                if (base.degree == 0) {
                    constantValue(Pow(Num(base.coef(0)), Num(n)), engine)     // constant ^ constant: exact where possible
                } else {
                    if (!n.isInteger || n.signum < 0 || n.num.bitLength() > 8) throw UnsupportedMath("only whole-number powers are supported")
                    var r = const(Rational.ONE)
                    repeat(n.num.toInt()) { r = r * base; if (r.degree > MAX_DEGREE) throw UnsupportedMath("degree too high") }
                    r
                }
            }
            is Func -> {
                if (variablesOf(e).isNotEmpty()) throw UnsupportedMath("functions of the variable (${e.name}) are not supported yet")
                constantValue(e, engine)
            }
        }

        private fun constantValue(e: Expr, engine: MathCalculationEngine): Poly = when (val r = engine.evaluate(e)) {
            is CalcResult.Success -> when (val v = r.value) {
                is Value.Exact -> const(v.value)
                is Value.Approx -> throw UnsupportedMath("irrational constants are not supported in equations yet")
            }
            is CalcResult.Failure -> if (r.error == CalcError.DIVISION_BY_ZERO || r.error == CalcError.DOMAIN) throw InvalidMath(r.message) else throw UnsupportedMath(r.message)
        }
    }
}
