package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*

/**
 * Produces step-by-step arithmetic by repeatedly applying ONE exact reduction in conventional order:
 * innermost parentheses -> powers/roots/functions -> × ÷ (left to right) -> + − (left to right).
 * Every step is computed from the expression tree and the exact evaluator, never by text manipulation.
 */
/** Folds -(literal) into a negative literal and drops parentheses around literals; Printer re-adds parentheses where needed. */
internal fun normalizeLiterals(e: Expr): Expr = when (e) {
    is Neg -> normalizeLiterals(e.operand).let { if (it is Num) Num(-it.value, it.decimal) else Neg(it) }
    is Group -> normalizeLiterals(e.inner).let { if (it is Num) it else Group(it) }
    is Bin -> Bin(e.op, normalizeLiterals(e.left), normalizeLiterals(e.right))
    is Pow -> Pow(normalizeLiterals(e.base), normalizeLiterals(e.exponent))
    is Func -> Func(e.name, e.args.map { normalizeLiterals(it) })
    else -> e
}

internal class ArithmeticStepper(private val engine: MathCalculationEngine = ExactCalculationEngine(), private val calc: CalcSettings = CalcSettings()) {

    private class Reduction(val expr: Expr, val explanation: String, val shown: String)

    fun solve(original: Expr): SolveOutcome {
        if (Poly.variablesOf(original).isNotEmpty()) return SolveOutcome.Unsupported("This expression has variables. Add '=' to make an equation to solve.")
        // fail fast, with a precise reason, on anything undefined (e.g. division by zero)
        (engine.evaluate(original) as? CalcResult.Failure)?.let { return SolveOutcome.Invalid(it.message) }

        val steps = mutableListOf<SolutionStep>()
        var current = normalizeLiterals(original)
        var beforeText = Printer.print(original)
        var guard = 0
        while (current !is Num && guard++ < MAX_STEPS) {
            val r = reduce(current) ?: break
            val next = normalizeLiterals(r.expr)
            val after = Printer.print(next)
            if (after != beforeText) {
                val at = after.indexOf(r.shown).takeIf { it >= 0 }
                steps += SolutionStep(steps.size + 1, beforeText, r.explanation, after, at?.let { it until it + r.shown.length })
                beforeText = after
            }
            current = next
        }
        if (current is Num) return SolveOutcome.Solved(steps, NumberFormatter.format(Value.Exact(current.value, current.decimal), calc).primary)

        // Not exactly reducible (e.g. √2): finish with a numeric approximation of the remaining expression.
        return when (val v = engine.evaluate(current)) {
            is CalcResult.Success -> {
                val text = NumberFormatter.format(v.value, calc).primary
                steps += SolutionStep(steps.size + 1, beforeText, "Evaluate numerically (approximate value)", text, 0 until text.length)
                SolveOutcome.Solved(steps, text)
            }
            is CalcResult.Failure -> SolveOutcome.Invalid(v.message)
        }
    }

    // ---------------------------------------------------------------- reduction
    private fun reduce(e: Expr): Reduction? = reduceInnermostGroup(e) ?: reduceScope(e)

    private fun containsGroup(e: Expr): Boolean = when (e) {
        is Group -> true
        is Neg -> containsGroup(e.operand)
        is Bin -> containsGroup(e.left) || containsGroup(e.right)
        is Pow -> containsGroup(e.base) || containsGroup(e.exponent)
        is Func -> e.args.any { containsGroup(it) }
        else -> false
    }

    /** Leftmost group that contains no other group: work inside it first. */
    private fun reduceInnermostGroup(e: Expr): Reduction? = when (e) {
        is Group -> {
            if (containsGroup(e.inner)) reduceInnermostGroup(e.inner)?.let { Reduction(Group(it.expr), it.explanation, it.shown) }
            else reduceScope(e.inner)?.let { Reduction(Group(it.expr), "Work inside the parentheses first: " + it.explanation.replaceFirstChar { c -> c.lowercase() }, it.shown) }
        }
        is Neg -> reduceInnermostGroup(e.operand)?.let { Reduction(Neg(it.expr), it.explanation, it.shown) }
        is Bin -> reduceInnermostGroup(e.left)?.let { Reduction(Bin(e.op, it.expr, e.right), it.explanation, it.shown) }
            ?: reduceInnermostGroup(e.right)?.let { Reduction(Bin(e.op, e.left, it.expr), it.explanation, it.shown) }
        is Pow -> reduceInnermostGroup(e.base)?.let { Reduction(Pow(it.expr, e.exponent), it.explanation, it.shown) }
            ?: reduceInnermostGroup(e.exponent)?.let { Reduction(Pow(e.base, it.expr), it.explanation, it.shown) }
        is Func -> reduceFuncArgs(e) { reduceInnermostGroup(it) }
        else -> null
    }

    private fun reduceFuncArgs(f: Func, step: (Expr) -> Reduction?): Reduction? {
        for ((i, a) in f.args.withIndex()) {
            val r = step(a) ?: continue
            return Reduction(Func(f.name, f.args.toMutableList().also { it[i] = r.expr }), r.explanation, r.shown)
        }
        return null
    }

    private fun reduceScope(scope: Expr): Reduction? {
        for (cls in 1..3) rewriteFirst(scope, cls, scope)?.let { return it }
        return null
    }

    private fun isLit(e: Expr) = e is Num

    /** Post-order, left-to-right search (does not enter groups) for the first node of precedence class [cls]. */
    private fun rewriteFirst(e: Expr, cls: Int, root: Expr): Reduction? {
        when (e) {
            is Bin -> {
                rewriteFirst(e.left, cls, root)?.let { return Reduction(Bin(e.op, it.expr, e.right), it.explanation, it.shown) }
                rewriteFirst(e.right, cls, root)?.let { return Reduction(Bin(e.op, e.left, it.expr), it.explanation, it.shown) }
                val mine = when (e.op) { Op.MUL, Op.DIV -> 2; else -> 3 }
                if (mine == cls && isLit(e.left) && isLit(e.right)) return compute(e, root)
            }
            is Pow -> {
                rewriteFirst(e.base, cls, root)?.let { return Reduction(Pow(it.expr, e.exponent), it.explanation, it.shown) }
                rewriteFirst(e.exponent, cls, root)?.let { return Reduction(Pow(e.base, it.expr), it.explanation, it.shown) }
                if (cls == 1 && isLit(e.base) && isLit(e.exponent)) return compute(e, root)
            }
            is Func -> {
                reduceFuncArgs(e) { rewriteFirst(it, cls, root) }?.let { return it }
                if (cls == 1 && e.args.all { isLit(it) }) return compute(e, root)
            }
            is Neg -> rewriteFirst(e.operand, cls, root)?.let { return Reduction(Neg(it.expr), it.explanation, it.shown) }
            else -> {}
        }
        return null
    }

    private fun compute(node: Expr, root: Expr): Reduction? {
        val v = (engine.evaluate(node) as? CalcResult.Success)?.value as? Value.Exact ?: return null   // irrational: stay symbolic
        val result = Num(v.value, v.decimalStyle)
        return Reduction(result, explain(node, root), Printer.print(result))
    }

    private fun explain(node: Expr, root: Expr): String {
        val hasLower = { ops: Set<Op> -> containsOp(root, ops, except = node) }
        return when (node) {
            is Bin -> {
                val txt = Printer.print(node)
                when (node.op) {
                    Op.ADD -> "Add $txt"
                    Op.SUB -> "Subtract $txt"
                    Op.MUL -> "Multiply $txt" + if (hasLower(setOf(Op.ADD, Op.SUB))) " first (multiplication comes before addition and subtraction)" else ""
                    Op.DIV -> "Divide $txt" + if (hasLower(setOf(Op.ADD, Op.SUB))) " first (division comes before addition and subtraction)" else ""
                }
            }
            is Pow -> "Evaluate the power ${Printer.print(node)}" + if (hasLower(setOf(Op.ADD, Op.SUB, Op.MUL, Op.DIV))) " first (powers come before multiplication and addition)" else ""
            is Func -> when (node.name) {
                "sqrt" -> "Take the square root: ${Printer.print(node)}"
                "root" -> "Take the root: ${Printer.print(node)}"
                "abs" -> "Take the absolute value: ${Printer.print(node)}"
                else -> "Evaluate ${Printer.print(node)}"
            }
            else -> "Simplify"
        }
    }

    private fun containsOp(e: Expr, ops: Set<Op>, except: Expr): Boolean = when {
        e === except -> false
        e is Bin -> e.op in ops || containsOp(e.left, ops, except) || containsOp(e.right, ops, except)
        e is Pow -> containsOp(e.base, ops, except) || containsOp(e.exponent, ops, except)
        e is Neg -> containsOp(e.operand, ops, except)
        e is Func -> e.args.any { containsOp(it, ops, except) }
        e is Group -> containsOp(e.inner, ops, except)
        else -> false
    }

    companion object { const val MAX_STEPS = 200 }
}
