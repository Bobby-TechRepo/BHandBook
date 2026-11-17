package com.bsoft.bhandbook.core.math

/** Expression tree (AST). [Group] preserves explicit parentheses so step-by-step output can respect the author's grouping. */
sealed interface Expr
data class Num(val value: Rational, val decimal: Boolean = false) : Expr {
    constructor(n: Long) : this(Rational.of(n))
}
data class Var(val name: String) : Expr
data class Const(val name: String) : Expr              // "pi"
data class Neg(val operand: Expr) : Expr
enum class Op(val symbol: String, val debug: String) { ADD("+", "+"), SUB("-", "-"), MUL("×", "*"), DIV("/", "/") }
data class Bin(val op: Op, val left: Expr, val right: Expr) : Expr
data class Pow(val base: Expr, val exponent: Expr) : Expr
data class Func(val name: String, val args: List<Expr>) : Expr
data class Group(val inner: Expr) : Expr

sealed interface MathStatement {
    data class Expression(val expr: Expr) : MathStatement
    data class Equation(val lhs: Expr, val rhs: Expr) : MathStatement
}

data class ParseError(val message: String, val position: Int)

sealed interface ParseResult {
    /** [requestedExplicitly] is true when the source ended with '=' (the user asked for the result). */
    data class Success(val statement: MathStatement, val requestedExplicitly: Boolean = false) : ParseResult
    data class Failure(val error: ParseError) : ParseResult
}

interface MathExpressionParser { fun parse(input: String): ParseResult }

/** Fully parenthesised canonical form used by tests and diagnostics. Groups are transparent. */
fun Expr.debug(): String = when (this) {
    is Num -> Printer.number(value)
    is Var -> name
    is Const -> name
    is Neg -> "(-${operand.debug()})"
    is Bin -> "(${left.debug()}${op.debug}${right.debug()})"
    is Pow -> "(${base.debug()}^${exponent.debug()})"
    is Func -> "$name(${args.joinToString(",") { it.debug() }})"
    is Group -> inner.debug()
}
