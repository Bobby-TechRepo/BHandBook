package com.bsoft.bhandbook.core.math

/**
 * Recursive-descent parser. Grammar (lowest to highest precedence):
 *   statement := expr [ '=' [ expr ] ]
 *   expr      := term { ('+'|'-') term }
 *   term      := unary { ('×'|'÷') unary | <implicit> power }
 *   unary     := ('-'|'+') unary | power
 *   power     := postfix [ '^' unary ]            (right associative; -2^2 = -(2^2))
 *   postfix   := primary { '%' }
 *   primary   := number | variable | const | func '(' args ')' | '√' atom | '(' expr ')' | '|' expr '|'
 * Never evaluates anything and never uses dynamic code execution.
 */
class DefaultMathParser : MathExpressionParser {

    private sealed interface Tok { val pos: Int }
    private data class TNum(val text: String, override val pos: Int) : Tok
    private data class TId(val name: String, override val pos: Int) : Tok       // variable, constant or function name
    private data class TSym(val c: Char, override val pos: Int) : Tok            // + - * / ^ ( ) | , = % √
    private data class TEnd(override val pos: Int) : Tok

    private class Fail(val error: ParseError) : RuntimeException(null, null, false, false)

    override fun parse(input: String): ParseResult = try {
        if (input.length > MAX_LENGTH) throw Fail(ParseError("expression is too long", MAX_LENGTH))
        Parser(lex(input)).statement()
    } catch (f: Fail) {
        ParseResult.Failure(f.error)
    }

    // ---------------------------------------------------------------- lexer
    private fun lex(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    var dots = 0
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) { if (s[i] == '.') dots++; i++ }
                    val text = s.substring(start, i)
                    if (dots > 1 || text.endsWith(".") || text == ".") throw Fail(ParseError("malformed number '$text'", start))
                    out += TNum(text, start)
                }
                c.isLetter() && c != '√' && c != 'π' -> {
                    val start = i
                    val rest = s.substring(i)
                    val fn = KNOWN_WORDS.firstOrNull { rest.startsWith(it) }
                    if (fn != null) { out += TId(fn, start); i += fn.length } else { out += TId(c.toString(), start); i++ }
                }
                c == 'π' -> { out += TId("pi", i); i++ }
                c == '√' -> { out += TSym('√', i); i++ }
                c == '−' || c == '–' || c == '-' -> { out += TSym('-', i); i++ }
                c == '×' || c == '·' || c == '*' || c == '⋅' -> { out += TSym('*', i); i++ }
                c == '÷' || c == '/' -> { out += TSym('/', i); i++ }
                c == '[' -> { out += TSym('(', i); i++ }
                c == ']' -> { out += TSym(')', i); i++ }
                c in "+^()|,=%" -> { out += TSym(c, i); i++ }
                else -> throw Fail(ParseError("unexpected character '$c'", i))
            }
        }
        out += TEnd(s.length)
        return out
    }

    // --------------------------------------------------------------- parser
    private class Parser(val toks: List<Tok>) {
        var p = 0
        var depth = 0
        val cur get() = toks[p]

        private fun fail(msg: String, pos: Int = cur.pos): Nothing = throw Fail(ParseError(msg, pos))
        private fun isSym(c: Char) = (cur as? TSym)?.c == c
        private fun enter() { if (++depth > MAX_DEPTH) fail("expression is nested too deeply") }
        private fun leave() { depth-- }

        fun statement(): ParseResult {
            if (cur is TEnd) fail("empty expression")
            if (isSym('=')) fail("missing expression before '='")
            val lhs = expr()
            if (isSym('=')) {
                p++
                if (cur is TEnd) return ParseResult.Success(MathStatement.Expression(lhs), requestedExplicitly = true)
                val rhs = expr()
                if (isSym('=')) fail("only one '=' is allowed")
                if (cur !is TEnd) fail("unexpected '${describe(cur)}'")
                return ParseResult.Success(MathStatement.Equation(lhs, rhs))
            }
            if (cur !is TEnd) fail(if (cur is TNum) "unexpected number '${describe(cur)}'" else "unexpected '${describe(cur)}'")
            return ParseResult.Success(MathStatement.Expression(lhs))
        }

        private fun describe(t: Tok) = when (t) { is TNum -> t.text; is TId -> t.name; is TSym -> t.c.toString(); is TEnd -> "end" }

        fun expr(): Expr {
            enter()
            var left = term()
            while (true) {
                val op = when { isSym('+') -> Op.ADD; isSym('-') -> Op.SUB; else -> break }
                p++
                left = Bin(op, left, term())
            }
            leave(); return left
        }

        private fun term(): Expr {
            var left = unary()
            while (true) {
                when {
                    isSym('*') -> { p++; left = Bin(Op.MUL, left, unary()) }
                    isSym('/') -> { p++; left = Bin(Op.DIV, left, unary()) }
                    startsImplicitFactor() -> left = Bin(Op.MUL, left, power())
                    else -> break
                }
            }
            return left
        }

        /** Implicit multiplication: 2x, 2(x+1), (a)(b), 2√9, 2π, 3sin(x). Never before a bare number. */
        private fun startsImplicitFactor(): Boolean {
            val t = cur
            return t is TId || (t is TSym && (t.c == '(' || t.c == '√'))
        }

        private fun unary(): Expr {
            enter()
            val r = when {
                isSym('-') -> { p++; Neg(unary()) }
                isSym('+') -> { p++; unary() }
                else -> power()
            }
            leave(); return r
        }

        private fun power(): Expr {
            val base = postfix()
            if (isSym('^')) { p++; return Pow(base, unary()) }
            return base
        }

        private fun postfix(): Expr {
            var e = primary()
            while (isSym('%')) { p++; e = Bin(Op.DIV, e, Num(100)) }
            return e
        }

        private fun primary(): Expr {
            val t = cur
            when (t) {
                is TNum -> { p++; return Num(Rational.parse(t.text), decimal = t.text.contains('.')) }
                is TId -> { p++; return identifier(t) }
                is TEnd -> fail("incomplete expression", t.pos)
                is TSym -> when (t.c) {
                    '(' -> { p++; return group(t) }
                    '|' -> {
                        p++
                        if (isSym('|')) fail("empty absolute value")
                        val inner = expr()
                        if (!isSym('|')) fail("missing closing '|'")
                        p++
                        return Func("abs", listOf(inner))
                    }
                    '√' -> { p++; return Func("sqrt", listOf(sqrtOperand())) }
                    ')' -> fail("unmatched ')'")
                    else -> fail("unexpected '${t.c}'")
                }
            }
        }

        private fun sqrtOperand(): Expr {
            enter()
            val t = cur
            val r = when {
                t is TNum || t is TId -> primary()
                t is TSym && (t.c == '(' || t.c == '√') -> primary()
                else -> fail("expected a value after '√'")
            }
            leave(); return r
        }

        private fun group(open: TSym): Expr {
            if (isSym(')')) fail("empty parentheses")
            val inner = expr()
            if (!isSym(')')) fail("missing closing ')'", cur.pos)
            p++
            return Group(inner)
        }

        private fun identifier(t: TId): Expr {
            val name = t.name
            if (name == "pi") return Const("pi")
            if (name in FUNCTIONS) {
                if (!isSym('(')) fail("expected '(' after '$name'")
                p++
                if (isSym(')')) fail("missing argument for '$name'")
                val args = ArrayList<Expr>()
                args += expr()
                while (isSym(',')) { p++; args += expr() }
                if (!isSym(')')) fail("missing closing ')'")
                p++
                val arity = if (name == "root") 2 else 1
                if (args.size != arity) fail("'$name' takes $arity argument(s)", t.pos)
                return Func(ALIASES[name] ?: name, args)
            }
            return Var(name)
        }
    }

    companion object {
        const val MAX_LENGTH = 2000
        const val MAX_DEPTH = 200
        val FUNCTIONS = setOf("sin", "cos", "tan", "asin", "acos", "atan", "arcsin", "arccos", "arctan", "log", "ln", "sqrt", "abs", "root")
        /** Long names are the same functions as the short ones. */
        private val ALIASES = mapOf("arcsin" to "asin", "arccos" to "acos", "arctan" to "atan")
        /** Longest-first so "sin" is not read as s·i·n. */
        private val KNOWN_WORDS = (FUNCTIONS + "pi").sortedByDescending { it.length }
    }
}
