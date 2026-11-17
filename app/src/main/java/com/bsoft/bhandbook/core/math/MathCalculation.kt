package com.bsoft.bhandbook.core.math

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

sealed interface Value {
    /** [decimalStyle]: the user typed decimals, so show a decimal rather than an exact fraction. */
    data class Exact(val value: Rational, val decimalStyle: Boolean = false) : Value
    data class Approx(val value: Double) : Value
    fun toDouble(): Double = when (this) { is Exact -> value.toDouble(); is Approx -> value }
}

enum class CalcError { DIVISION_BY_ZERO, DOMAIN, UNDEFINED_VARIABLE, OVERFLOW, UNSUPPORTED }
sealed interface CalcResult {
    data class Success(val value: Value) : CalcResult
    data class Failure(val error: CalcError, val message: String) : CalcResult
}
enum class AngleMode { RADIANS, DEGREES, GRADIANS }

interface MathCalculationEngine {
    fun evaluate(expr: Expr, variables: Map<String, Value> = emptyMap()): CalcResult

    /** An engine that reads trigonometric arguments (and answers inverse functions) in [mode]. Engines without angles may return themselves. */
    fun withAngle(mode: AngleMode): MathCalculationEngine = this
}

/** Evaluates a validated [Expr] exactly where possible (rationals, perfect roots), falling back to Double. */
class ExactCalculationEngine(private val angleMode: AngleMode = AngleMode.RADIANS) : MathCalculationEngine {

    override fun withAngle(mode: AngleMode): MathCalculationEngine = if (mode == angleMode) this else ExactCalculationEngine(mode)

    private class CalcException(val error: CalcError, message: String) : RuntimeException(message, null, false, false)

    override fun evaluate(expr: Expr, variables: Map<String, Value>): CalcResult = try {
        CalcResult.Success(ev(expr, variables))
    } catch (e: CalcException) {
        CalcResult.Failure(e.error, e.message ?: e.error.name)
    } catch (e: StackOverflowError) {
        CalcResult.Failure(CalcError.UNSUPPORTED, "expression too deep")
    }

    private fun ev(e: Expr, vars: Map<String, Value>): Value = when (e) {
        is Num -> Value.Exact(e.value, e.decimal)
        is Var -> vars[e.name] ?: throw CalcException(CalcError.UNDEFINED_VARIABLE, "'${e.name}' has no value")
        is Const -> Value.Approx(Math.PI)
        is Group -> ev(e.inner, vars)
        is Neg -> when (val v = ev(e.operand, vars)) { is Value.Exact -> v.copy(value = -v.value); is Value.Approx -> Value.Approx(-v.value) }
        is Bin -> binary(e.op, ev(e.left, vars), ev(e.right, vars))
        is Pow -> power(ev(e.base, vars), ev(e.exponent, vars))
        is Func -> function(e, vars)
    }

    private fun binary(op: Op, a: Value, b: Value): Value {
        if (a is Value.Exact && b is Value.Exact) {
            val style = a.decimalStyle || b.decimalStyle
            val r = when (op) {
                Op.ADD -> a.value + b.value
                Op.SUB -> a.value - b.value
                Op.MUL -> a.value * b.value
                Op.DIV -> { if (b.value.isZero) throw CalcException(CalcError.DIVISION_BY_ZERO, "division by zero"); a.value / b.value }
            }
            return Value.Exact(r, style)
        }
        val x = a.toDouble(); val y = b.toDouble()
        val r = when (op) {
            Op.ADD -> x + y; Op.SUB -> x - y; Op.MUL -> x * y
            Op.DIV -> { if (y == 0.0) throw CalcException(CalcError.DIVISION_BY_ZERO, "division by zero"); x / y }
        }
        return approx(r)
    }

    private fun power(base: Value, exp: Value): Value {
        if (base is Value.Exact && exp is Value.Exact) {
            val b = base.value; val x = exp.value
            val style = base.decimalStyle || exp.decimalStyle
            if (x.isInteger && x.num.bitLength() <= 31) {
                val n = x.num.toInt()
                if (b.isZero && n < 0) throw CalcException(CalcError.DIVISION_BY_ZERO, "zero to a negative power")
                if (abs(n) <= MAX_EXACT_EXPONENT && b.num.bitLength().toLong() * abs(n) <= MAX_EXACT_BITS) return Value.Exact(b.pow(n), style)
            } else if (!x.isInteger && x.den.bitLength() <= 6 && x.num.bitLength() <= 31 && b.signum >= 0) {
                val n = x.num.toInt()
                if (abs(n) <= 64) b.exactRoot(x.den.toInt())?.let { root -> if (!(root.isZero && n < 0)) return Value.Exact(root.pow(n), style) }
            }
        }
        val bd = base.toDouble(); val xd = exp.toDouble()
        if (bd < 0 && xd != Math.floor(xd)) throw CalcException(CalcError.DOMAIN, "negative base with fractional exponent")
        if (bd == 0.0 && xd < 0) throw CalcException(CalcError.DIVISION_BY_ZERO, "zero to a negative power")
        return approx(Math.pow(bd, xd))
    }

    private fun function(f: Func, vars: Map<String, Value>): Value {
        val a = f.args.map { ev(it, vars) }
        return when (f.name) {
            "abs" -> when (val v = a[0]) { is Value.Exact -> v.copy(value = v.value.abs()); is Value.Approx -> Value.Approx(abs(v.value)) }
            "sqrt" -> root(2, a[0])
            "root" -> {
                val n = (a[0] as? Value.Exact)?.value
                if (n == null || !n.isInteger || n.signum <= 0 || n.num.bitLength() > 16) throw CalcException(CalcError.DOMAIN, "root degree must be a positive integer")
                root(n.num.toInt(), a[1])
            }
            "sin", "cos", "tan" -> trig(f.name, a[0])
            "asin", "acos", "atan" -> inverseTrig(f.name, a[0])
            "log", "ln" -> {
                val x = a[0].toDouble()
                if (x <= 0.0) throw CalcException(CalcError.DOMAIN, "logarithm of a non-positive number")
                approx(if (f.name == "log") Math.log10(x) else Math.log(x))
            }
            else -> throw CalcException(CalcError.UNSUPPORTED, "unknown function '${f.name}'")
        }
    }

    // ---------------------------------------------------------------- trigonometry in degrees, gradians or radians
    /** Exact answers for the angles whose sine/cosine/tangent are rational (multiples of 30 and 45 degrees), numeric ones otherwise, noise removed. */
    private fun trig(name: String, arg: Value): Value {
        if (angleMode != AngleMode.RADIANS && arg is Value.Exact) exactTrig(name, toDegrees(arg.value))?.let { return it }
        val x = arg.toDouble()
        val rad = when (angleMode) { AngleMode.RADIANS -> x; AngleMode.DEGREES -> Math.toRadians(x); AngleMode.GRADIANS -> x * Math.PI / 200.0 }
        val r = when (name) {
            "sin" -> Math.sin(rad)
            "cos" -> Math.cos(rad)
            else -> { if (abs(Math.cos(rad)) < ZERO_NOISE) throw CalcException(CalcError.DOMAIN, "tan is undefined at this angle"); Math.tan(rad) }
        }
        return if (abs(r) < ZERO_NOISE) Value.Exact(Rational.ZERO) else approx(r)                // sin(pi) is 0, not 1.2e-16
    }

    private fun toDegrees(angle: Rational): Rational = if (angleMode == AngleMode.GRADIANS) angle * Rational.of(9, 10) else angle

    private fun exactTrig(name: String, degrees: Rational): Value? {
        if (!degrees.isInteger) return null
        val d = degrees.num.mod(java.math.BigInteger.valueOf(360)).toInt()
        fun q(n: Long, den: Long = 1) = Value.Exact(Rational.of(n, den))
        return when (name) {
            "sin" -> when (d) { 0, 180 -> q(0); 30, 150 -> q(1, 2); 210, 330 -> q(-1, 2); 90 -> q(1); 270 -> q(-1); else -> null }
            "cos" -> when (d) { 90, 270 -> q(0); 60, 300 -> q(1, 2); 120, 240 -> q(-1, 2); 0 -> q(1); 180 -> q(-1); else -> null }
            else -> when (d) {
                0, 180 -> q(0); 45, 225 -> q(1); 135, 315 -> q(-1)
                90, 270 -> throw CalcException(CalcError.DOMAIN, "tan is undefined at this angle")
                else -> null
            }
        }
    }

    /** asin, acos, atan answer in the current angle unit; results that are whole numbers of degrees/gradians (asin(1/2) = 30) are exact. */
    private fun inverseTrig(name: String, arg: Value): Value {
        val x = arg.toDouble()
        if (name != "atan" && abs(x) > 1.0) throw CalcException(CalcError.DOMAIN, "$name is only defined for values from -1 to 1")
        val rad = when (name) { "asin" -> Math.asin(x); "acos" -> Math.acos(x); else -> Math.atan(x) }
        val angle = when (angleMode) { AngleMode.RADIANS -> rad; AngleMode.DEGREES -> Math.toDegrees(rad); AngleMode.GRADIANS -> rad * 200.0 / Math.PI }
        if (angleMode != AngleMode.RADIANS) {
            val whole = Math.rint(angle)
            if (abs(angle - whole) < 1e-9 * maxOf(1.0, abs(angle))) return Value.Exact(Rational.of(whole.toLong(), 1))
        }
        return if (abs(angle) < ZERO_NOISE) Value.Exact(Rational.ZERO) else approx(angle)
    }

    private fun root(n: Int, v: Value): Value {
        if (v is Value.Exact) {
            val exact = v.value.exactRoot(n)
            if (exact != null) return Value.Exact(exact, v.decimalStyle)
        }
        val x = v.toDouble()
        if (x < 0 && n % 2 == 0) throw CalcException(CalcError.DOMAIN, "even root of a negative number")
        return approx(if (x < 0) -Math.pow(-x, 1.0 / n) else Math.pow(x, 1.0 / n))
    }

    private fun approx(d: Double): Value {
        if (d.isNaN()) throw CalcException(CalcError.DOMAIN, "result is undefined")
        if (d.isInfinite()) throw CalcException(CalcError.OVERFLOW, "result is too large")
        return Value.Approx(d)
    }

    companion object {
        /** Results this close to zero from sin/cos/tan are floating-point noise (sin(pi) = 1.2e-16), not values. */
        private const val ZERO_NOISE = 1e-12
        const val MAX_EXACT_EXPONENT = 10_000
        const val MAX_EXACT_BITS = 200_000L
    }
}

data class FormattedNumber(val primary: String, val decimal: String? = null)

object NumberFormatter {
    private const val SIG_DIGITS = 10
    private const val MAX_PLAIN_DIGITS = 15
    private const val DEFAULT_PLACES = 9
    private val MC = MathContext(SIG_DIGITS)

    fun format(v: Value, settings: CalcSettings = CalcSettings()): FormattedNumber = when (v) {
        is Value.Exact -> exact(v, settings)
        is Value.Approx -> FormattedNumber(approx(v.value, settings))
    }

    private fun fixed(bd: BigDecimal, places: Int): String = bd.setScale(places, java.math.RoundingMode.HALF_UP).toPlainString()

    private fun exact(v: Value.Exact, s: CalcSettings): FormattedNumber {
        val r = v.value
        val places = s.places
        if (r.isInteger) {
            val digits = r.num.abs().toString().length
            if (digits > MAX_PLAIN_DIGITS) return FormattedNumber(scientific(BigDecimal(r.num)))
            return FormattedNumber(if (places != null) fixed(BigDecimal(r.num), places) else r.num.toString())
        }
        if (r.num.abs().toString().length > MAX_PLAIN_DIGITS || r.den.toString().length > MAX_PLAIN_DIGITS)
            return FormattedNumber(approx(r.toDouble(), s))
        if (places != null) return FormattedNumber(fixed(BigDecimal(r.num).divide(BigDecimal(r.den), places, java.math.RoundingMode.HALF_UP), places))
        val companion = BigDecimal(r.num).divide(BigDecimal(r.den), DEFAULT_PLACES, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        if (s.style == ResultStyle.DECIMAL) return FormattedNumber(companion)
        if (v.decimalStyle) Printer.decimalOrNull(r, 9)?.let { return FormattedNumber(it) }
        return FormattedNumber("${r.num}/${r.den}", companion)
    }

    private fun approx(x: Double, s: CalcSettings): String {
        if (x.isNaN()) return "undefined"
        if (x.isInfinite()) return if (x > 0) "∞" else "-∞"
        val places = s.places
        if (places != null && abs(x) < 1e15) return fixed(BigDecimal(x), places)
        if (x == 0.0) return "0"
        val a = abs(x)
        val bd = BigDecimal(x)
        return if (a >= 1e15 || a < 1e-4) scientific(bd) else {
            val r = bd.round(MC).stripTrailingZeros()
            if (r.signum() == 0) "0" else r.toPlainString()
        }
    }

    private fun scientific(bd: BigDecimal): String {
        val r = bd.round(MC)
        val exp = r.precision() - r.scale() - 1
        val mant = r.movePointLeft(exp).stripTrailingZeros().toPlainString()
        return "$mant×10^$exp"
    }
}
