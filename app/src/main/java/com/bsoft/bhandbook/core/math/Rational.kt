package com.bsoft.bhandbook.core.math

import java.math.BigInteger

/** Exact rational number (always normalised: den > 0, gcd(num, den) = 1). Avoids Java 9+ BigInteger APIs for old Android. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    val isInteger get() = den == BigInteger.ONE
    val signum get() = num.signum()
    val isZero get() = num.signum() == 0

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.isZero) throw ArithmeticException("division by zero")
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)
    fun abs() = if (num.signum() < 0) -this else this

    fun pow(n: Int): Rational = when {
        n == 0 -> ONE
        n > 0 -> Rational(num.pow(n), den.pow(n))
        isZero -> throw ArithmeticException("zero to a negative power")
        else -> of(den.pow(-n), num.pow(-n))
    }

    /** Exact k-th root if this is a perfect k-th power of a rational, otherwise null. */
    fun exactRoot(k: Int): Rational? {
        if (k <= 0) return null
        if (num.signum() < 0) return if (k % 2 == 1) (-this).exactRoot(k)?.let { -it } else null
        val n = intRoot(num, k) ?: return null
        val d = intRoot(den, k) ?: return null
        return Rational(n, d)
    }
    fun exactSqrt(): Rational? = exactRoot(2)

    fun toDouble(): Double {
        val d = num.toDouble() / den.toDouble()
        if (!d.isNaN() && !d.isInfinite()) return d
        return java.math.BigDecimal(num).divide(java.math.BigDecimal(den), java.math.MathContext(20)).toDouble()
    }

    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = num.hashCode() * 31 + den.hashCode()
    override fun toString() = if (isInteger) num.toString() else "$num/$den"

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(n: Long, d: Long = 1L) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            if (d.signum() == 0) throw ArithmeticException("zero denominator")
            if (n.signum() == 0) return ZERO
            val g = n.gcd(d)
            val sign = d.signum()
            return Rational(n.divide(g).multiply(BigInteger.valueOf(sign.toLong())), d.divide(g).abs())
        }

        /** Parses "12", "3.14", ".5" exactly (no binary floating point involved). */
        fun parse(s: String): Rational {
            val neg = s.startsWith("-")
            val body = s.removePrefix("-")
            val parts = body.split('.')
            require(parts.size <= 2 && body.isNotEmpty()) { "bad number: $s" }
            val intPart = parts[0].ifEmpty { "0" }
            val frac = if (parts.size == 2) parts[1] else ""
            val n = BigInteger(intPart + frac)
            val d = BigInteger.TEN.pow(frac.length)
            return of(if (neg) n.negate() else n, d)
        }

        /** Floor integer k-th root via binary search; returns null unless exact. */
        private fun intRoot(x: BigInteger, k: Int): BigInteger? {
            if (x.signum() == 0 || x == BigInteger.ONE) return x
            var lo = BigInteger.ONE
            var hi = BigInteger.ONE.shiftLeft(x.bitLength() / k + 1)
            while (lo <= hi) {
                val mid = lo.add(hi).shiftRight(1)
                val p = mid.pow(k)
                val c = p.compareTo(x)
                if (c == 0) return mid
                if (c < 0) lo = mid.add(BigInteger.ONE) else hi = mid.subtract(BigInteger.ONE)
            }
            return null
        }
    }
}
