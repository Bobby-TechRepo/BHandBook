package com.bsoft.bhandbook.core.math

import org.junit.Assert.*
import org.junit.Test

class RationalTest {
    @Test fun normalizes_signAndGcd() {
        val r = Rational.of(6, -8)
        assertEquals(-3L, r.num.toLong()); assertEquals(4L, r.den.toLong())
    }
    @Test fun arithmetic_isExact() {
        assertEquals(Rational.of(1, 2), Rational.of(1, 3) + Rational.of(1, 6))
        assertEquals(Rational.of(1, 6), Rational.of(1, 2) * Rational.of(1, 3))
        assertEquals(Rational.of(3, 2), Rational.of(1, 2) / Rational.of(1, 3))
        assertEquals(Rational.of(-1, 6), Rational.of(1, 3) - Rational.of(1, 2))
    }
    @Test(expected = ArithmeticException::class) fun divisionByZero_throws() { Rational.of(1) / Rational.ZERO }
    @Test fun parseDecimal_isExact() {
        assertEquals(Rational.of(1, 10), Rational.parse("0.1"))
        assertEquals(Rational.of(314, 100).let { Rational.of(157, 50) }, Rational.parse("3.14"))
        assertEquals(Rational.of(5), Rational.parse("5"))
    }
    @Test fun pow_integerExponents() {
        assertEquals(Rational.of(8), Rational.of(2).pow(3))
        assertEquals(Rational.of(1, 8), Rational.of(2).pow(-3))
        assertEquals(Rational.ONE, Rational.of(7).pow(0))
    }
    @Test fun exactSqrt_onlyForPerfectSquares() {
        assertEquals(Rational.of(3), Rational.of(9).exactSqrt())
        assertEquals(Rational.of(2, 3), Rational.of(4, 9).exactSqrt())
        assertNull(Rational.of(2).exactSqrt())
        assertNull(Rational.of(-4).exactSqrt())
    }
    @Test fun compare_andDouble() {
        assertTrue(Rational.of(1, 3) < Rational.of(1, 2))
        assertEquals(0.5, Rational.of(1, 2).toDouble(), 0.0)
    }
}
