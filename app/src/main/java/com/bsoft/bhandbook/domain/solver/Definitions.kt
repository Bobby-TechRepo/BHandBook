package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import java.math.BigDecimal
import java.math.MathContext

/**
 * Named values and functions written on the canvas: `a = 5`, `f(x) = 2x + 1`. They are found from what was recognised, resolved here, and used when
 * other expressions are evaluated, solved or graphed. x, y and z can never be defined: they are the unknowns of equations and graphs.
 */
sealed interface Definition { val name: String; val order: Long }
data class ValueDefinition(override val name: String, val expr: Expr, override val order: Long = 0L) : Definition
data class FunctionDefinition(override val name: String, val param: String, val body: Expr, override val order: Long = 0L) : Definition

/** The definitions that are in force: [values] (already computed), [functions], and [problems] (e.g. circular definitions) by name. */
class Definitions(val values: Map<String, Value>, val functions: Map<String, FunctionDefinition>, val problems: Map<String, String> = emptyMap()) {
    val isEmpty get() = values.isEmpty() && functions.isEmpty()

    /** Readable lines: "a = 5", "f(x) = 2x + 1", then one line per problem. */
    fun describe(): List<String> =
        values.toSortedMap().map { (n, v) -> "$n = ${NumberFormatter.format(v).primary}" } +
            functions.toSortedMap().map { (n, f) -> "$n(${f.param}) = ${Printer.print(normalizeLiterals(f.body))}" } +
            problems.toSortedMap().map { (n, p) -> "$n: $p" }

    /** Changes exactly when anything that expressions depend on changes (used to know when to evaluate them again). */
    fun signature(): String = if (isEmpty) "" else (
        values.toSortedMap().map { (n, v) -> "$n=${NumberFormatter.format(v).primary}" } +
            functions.toSortedMap().map { (n, f) -> "$n(${f.param})=${Printer.print(f.body)}" }).joinToString(";")

    companion object {
        val EMPTY = Definitions(emptyMap(), emptyMap())
        val RESERVED = setOf("x", "y", "z")
    }
}

object DefinitionParser {
    /** The definition that [st] is, by its shape alone (null for anything else); whether it can be used is decided by [DefinitionResolver]. */
    fun parse(st: MathStatement, order: Long = 0L): Definition? {
        if (st !is MathStatement.Equation) return null
        val l = strip(st.lhs); val rhs = st.rhs
        val rhsNames = Substitution.namesIn(rhs)
        if (l is Var) {
            if (!isLetter(l.name) || l.name in Definitions.RESERVED || l.name in rhsNames) return null
            return ValueDefinition(l.name, rhs, order)
        }
        if (l is Bin && l.op == Op.MUL) {
            val f = strip(l.left) as? Var; val g = l.right as? Group; val p = g?.let { strip(it.inner) } as? Var
            if (f != null && p != null && isLetter(f.name) && isLetter(p.name) && f.name != p.name && f.name !in Definitions.RESERVED && f.name !in rhsNames)
                return FunctionDefinition(f.name, p.name, rhs, order)
        }
        return null
    }

    private fun isLetter(n: String) = n.length == 1 && n[0].isLetter()
    private fun strip(e: Expr): Expr = if (e is Group) strip(e.inner) else e
}

object DefinitionResolver {
    /** The definitions in force: the latest of each name; anything that depends on something undefined is simply not a definition, cycles are reported. */
    fun resolve(candidates: List<Definition>, calc: CalcSettings): Definitions {
        fun <T : Definition> latest(list: List<T>): Map<String, T> = list.groupBy { it.name }.mapValues { (_, v) -> v.maxByOrNull { it.order }!! }
        val values = latest(candidates.filterIsInstance<ValueDefinition>())
        val functions = latest(candidates.filterIsInstance<FunctionDefinition>())
        return Run(values, functions, ExactCalculationEngine(calc.angle)).result()
    }

    private class Run(val valueDefs: Map<String, ValueDefinition>, val funcDefs: Map<String, FunctionDefinition>, val engine: MathCalculationEngine) {
        private enum class S { VISITING, OK, BAD }
        private val state = HashMap<String, S>()
        private val stack = ArrayList<String>()
        private val cycleMembers = HashSet<String>()
        private val values = LinkedHashMap<String, Value>()
        private val okFunctions = LinkedHashMap<String, FunctionDefinition>()
        private val problems = LinkedHashMap<String, String>()

        fun result(): Definitions {
            for (n in valueDefs.keys.sorted()) visit("v:$n")
            for (n in funcDefs.keys.sorted()) visit("f:$n")
            return Definitions(values, okFunctions, problems)
        }

        /** The definitions that [key] needs, or null if it needs something that is not defined (so it is not a definition at all). */
        private fun needs(key: String): List<String>? {
            val isValue = key.startsWith("v:"); val name = key.substring(2)
            val names = if (isValue) Substitution.namesIn(valueDefs.getValue(name).expr) else Substitution.namesIn(funcDefs.getValue(name).body) - funcDefs.getValue(name).param
            val out = ArrayList<String>()
            for (n in names) when {
                n in funcDefs -> out += "f:$n"
                n in valueDefs && n !in Definitions.RESERVED -> out += "v:$n"
                else -> return null
            }
            return out
        }

        private fun visit(key: String): S {
            state[key]?.let { s ->
                if (s == S.VISITING) {                                                           // reached again while still being resolved: a cycle
                    for (k in stack.subList(stack.indexOf(key), stack.size)) { cycleMembers += k; problems[k.substring(2)] = "circular definition" }
                    return S.BAD
                }
                return s
            }
            state[key] = S.VISITING; stack += key
            val deps = needs(key)
            var result = if (deps == null) S.BAD else S.OK
            if (deps != null) for (d in deps) if (visit(d) != S.OK) result = S.BAD
            if (key in cycleMembers) result = S.BAD
            if (result == S.OK) {
                val name = key.substring(2)
                if (key.startsWith("v:")) {
                    val expanded = Substitution.expandCalls(valueDefs.getValue(name).expr, okFunctions)
                    when (val r = engine.evaluate(expanded, values)) {
                        is CalcResult.Success -> values[name] = r.value
                        is CalcResult.Failure -> { problems[name] = r.message; result = S.BAD }
                    }
                } else okFunctions[name] = funcDefs.getValue(name)
            }
            stack.removeAt(stack.size - 1); state[key] = result
            return result
        }
    }
}

object Substitution {
    private const val MAX_DEPTH = 16

    fun namesIn(e: Expr): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(x: Expr) { when (x) {
            is Var -> out += x.name
            is Neg -> walk(x.operand); is Group -> walk(x.inner); is Bin -> { walk(x.left); walk(x.right) }
            is Pow -> { walk(x.base); walk(x.exponent) }; is Func -> x.args.forEach(::walk)
            is Num, is Const -> {}
        } }
        walk(e); return out
    }

    /** [e] with the name [from] renamed to [to] (used to graph a function of any parameter letter against x). */
    fun rename(e: Expr, from: String, to: String): Expr = replaceVar(e, from, Var(to))

    /** Replaces calls of defined functions (`f(3)`, `2f(a+1)`, `f(f(1))`) by their bodies. Always terminates, even for a runaway recursion. */
    fun expandCalls(e: Expr, functions: Map<String, FunctionDefinition>): Expr = if (functions.isEmpty()) e else expand(e, functions, 0)

    private fun expand(e: Expr, fns: Map<String, FunctionDefinition>, depth: Int): Expr {
        if (depth > MAX_DEPTH) return e
        return when (e) {
            is Bin -> {
                val right = e.right
                // f(3) is read as f * (3), and f(3)^2 as f * ((3)^2): both are calls, and the power belongs to the call, not to its argument
                val callArgument = if (e.op == Op.MUL) (right as? Group) ?: ((right as? Pow)?.base as? Group) else null
                if (callArgument != null) {
                    val call = trailingCall(e.left, fns)
                    if (call != null) {
                        val (prefix, def) = call
                        val arg = expand(callArgument.inner, fns, depth + 1)
                        val body = Group(expand(replaceVar(def.body, def.param, Group(arg)), fns, depth + 1))
                        val called: Expr = if (right is Pow) Pow(body, expand(right.exponent, fns, depth + 1)) else body
                        return if (prefix == null) called else Bin(Op.MUL, expand(prefix, fns, depth + 1), called)
                    }
                }
                Bin(e.op, expand(e.left, fns, depth), expand(e.right, fns, depth))
            }
            is Neg -> Neg(expand(e.operand, fns, depth)); is Group -> Group(expand(e.inner, fns, depth))
            is Pow -> Pow(expand(e.base, fns, depth), expand(e.exponent, fns, depth)); is Func -> Func(e.name, e.args.map { expand(it, fns, depth) })
            is Num, is Var, is Const -> e
        }
    }

    /** `f` or `... * f` at the end of the left factor: the function being called and whatever multiplies it. */
    private fun trailingCall(left: Expr, fns: Map<String, FunctionDefinition>): Pair<Expr?, FunctionDefinition>? {
        if (left is Var) return fns[left.name]?.let { null to it }
        if (left is Bin && left.op == Op.MUL && left.right is Var) return fns[(left.right as Var).name]?.let { left.left to it }
        return null
    }

    private fun replaceVar(e: Expr, name: String, with: Expr): Expr = when (e) {
        is Var -> if (e.name == name) with else e
        is Neg -> Neg(replaceVar(e.operand, name, with)); is Group -> Group(replaceVar(e.inner, name, with))
        is Bin -> Bin(e.op, replaceVar(e.left, name, with), replaceVar(e.right, name, with)); is Pow -> Pow(replaceVar(e.base, name, with), replaceVar(e.exponent, name, with))
        is Func -> Func(e.name, e.args.map { replaceVar(it, name, with) }); is Num, is Const -> e
    }

    /** Replaces every defined value (except the reserved unknowns) by its number, so that equations can be solved with the other names left as unknowns. */
    fun withValues(e: Expr, defs: Definitions, keep: Set<String> = Definitions.RESERVED): Expr = when (e) {
        is Var -> defs.values[e.name]?.takeIf { e.name !in keep }?.let(::numberExpr) ?: e
        is Neg -> Neg(withValues(e.operand, defs, keep)); is Group -> Group(withValues(e.inner, defs, keep))
        is Bin -> Bin(e.op, withValues(e.left, defs, keep), withValues(e.right, defs, keep)); is Pow -> Pow(withValues(e.base, defs, keep), withValues(e.exponent, defs, keep))
        is Func -> Func(e.name, e.args.map { withValues(it, defs, keep) }); is Num, is Const -> e
    }

    private fun numberExpr(v: Value): Expr {
        val (r, decimal) = when (v) {
            is Value.Exact -> v.value to v.decimalStyle
            is Value.Approx -> (v.value.takeIf { it.isFinite() }?.let { Rational.parse(BigDecimal(it).round(MathContext(15)).toPlainString()) } ?: Rational.ZERO) to true
        }
        val magnitude = r.abs()
        val positive: Expr = when {
            magnitude.isInteger -> Num(magnitude, decimal)
            decimal -> Num(magnitude, true)                                                    // written as a decimal: stays a decimal; otherwise an exact fraction stays exact
            else -> Group(Bin(Op.DIV, Num(Rational.of(magnitude.num, java.math.BigInteger.ONE)), Num(Rational.of(magnitude.den, java.math.BigInteger.ONE))))
        }
        return if (r.signum < 0) Group(Neg(positive)) else positive
    }
}
