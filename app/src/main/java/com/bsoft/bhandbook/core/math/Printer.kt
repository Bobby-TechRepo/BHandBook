package com.bsoft.bhandbook.core.math

import java.math.BigDecimal
import java.math.BigInteger

/** Human-readable notation (× ÷ √ ^), minimal parentheses beyond those the author wrote. */
object Printer {
    /** Integer, terminating decimal (<= 10 places), or "n/d". */
    fun number(r: Rational): String {
        if (r.isInteger) return r.num.toString()
        decimalOrNull(r, 10)?.let { return it }
        return "${r.num}/${r.den}"
    }

    fun decimalOrNull(r: Rational, maxPlaces: Int): String? {
        var d = r.den
        var twos = 0; var fives = 0
        val two = BigInteger.valueOf(2); val five = BigInteger.valueOf(5)
        while (d.mod(two).signum() == 0) { d = d.divide(two); twos++ }
        while (d.mod(five).signum() == 0) { d = d.divide(five); fives++ }
        if (d != BigInteger.ONE) return null
        val places = maxOf(twos, fives)
        if (places > maxPlaces) return null
        return BigDecimal(r.num).divide(BigDecimal(r.den)).stripTrailingZeros().toPlainString()
    }

    /** Fractions print as "n/d" unless the user typed decimals ([Num.decimal]) and the value terminates. */
    private fun num(e: Num): String =
        if (!e.value.isInteger && !e.decimal) "${e.value.num}/${e.value.den}" else number(e.value)

    fun print(e: Expr): String = when (e) {
        is Num -> num(e)
        is Var -> e.name
        is Const -> if (e.name == "pi") "π" else e.name
        is Neg -> "-" + wrapIfLoose(e.operand)
        is Bin -> printBin(e)
        is Pow -> printOperand(e.base, forPow = true) + "^" + printExponent(e.exponent)
        is Func -> when (e.name) {
            "sqrt" -> stripGroup(e.args[0]).let { a ->
                if (a is Var || (a is Num && a.value.signum >= 0 && a.value.isInteger)) "√" + print(a) else "√(" + print(a) + ")"
            }
            else -> e.name + "(" + e.args.joinToString(", ") { print(it) } + ")"
        }
        is Group -> "(" + print(e.inner) + ")"
    }

    private fun containsSymbol(e: Expr): Boolean = when (e) {
        is Var, is Const -> true
        is Num -> false
        is Neg -> containsSymbol(e.operand)
        is Group -> containsSymbol(e.inner)
        is Bin -> containsSymbol(e.left) || containsSymbol(e.right)
        is Pow -> containsSymbol(e.base) || containsSymbol(e.exponent)
        is Func -> e.args.any { containsSymbol(it) }
    }

    /** A name, or a product whose last factor is a name (the `2f` of `2f(3)`). */
    private fun endsWithName(e: Expr): Boolean = e is Var || (e is Bin && e.op == Op.MUL && e.right is Var)

    private fun stripGroup(e: Expr) = if (e is Group) e.inner else e

    private fun isFractionLiteral(e: Expr) = e is Num && !e.value.isInteger && (!e.decimal || decimalOrNull(e.value, 10) == null)

    private fun printBin(e: Bin): String {
        // a name directly before a bracket reads as a call: f(x)
        if (e.op == Op.MUL && e.right is Group && endsWithName(e.left)) return print(e.left) + print(e.right)
        // implicit multiplication reads naturally in algebra: 2x, 2(x + 1), 3π -- but never between plain numbers
        if (e.op == Op.MUL && e.left is Num && !isFractionLiteral(e.left) && containsSymbol(e.right) &&
            (e.right is Var || e.right is Const || e.right is Group || e.right is Func || (e.right is Pow && (e.right.base is Var || e.right.base is Const)))
        ) return print(e.left) + print(e.right)
        // an integer over an integer is a fraction literal: print it compactly, "1/2"
        if (e.op == Op.DIV && e.left is Num && e.right is Num && e.left.value.isInteger && e.right.value.isInteger &&
            e.left.value.signum >= 0 && e.right.value.signum > 0
        ) return "${print(e.left)}/${print(e.right)}"
        val l = printOperand(e.left, e.op, isRight = false)
        val r = printOperand(e.right, e.op, isRight = true)
        return "$l ${e.op.symbol} $r"
    }

    private fun printOperand(e: Expr, op: Op? = null, isRight: Boolean = false, forPow: Boolean = false): String {
        val needs = when {
            e is Num && (e.value.signum < 0) -> isRight || forPow || op == Op.MUL || op == Op.DIV
            isFractionLiteral(e) -> op == Op.MUL || op == Op.DIV || forPow
            e is Neg -> isRight || forPow
            forPow && (e is Bin || e is Pow) -> true
            else -> false
        }
        val s = print(e)
        return if (needs) "($s)" else s
    }

    private fun printExponent(e: Expr): String = when {
        e is Num && e.value.isInteger && e.value.signum >= 0 -> print(e)
        e is Var || e is Const -> print(e)
        e is Group -> print(e)
        else -> "(" + print(e) + ")"
    }

    private fun wrapIfLoose(e: Expr): String = if (e is Bin) "(" + print(e) + ")" else print(e)
}
