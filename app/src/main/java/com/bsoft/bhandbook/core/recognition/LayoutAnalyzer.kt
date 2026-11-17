package com.bsoft.bhandbook.core.recognition

import com.bsoft.bhandbook.core.graphics.WorldRect

/** Turns classified symbols into a 2-D structure: fractions, radicals, superscripts, decimal vs. multiplication dots. */
internal object LayoutAnalyzer {

    private sealed interface Item { val bounds: WorldRect }
    private class SymItem(var sym: Sym) : Item { override val bounds get() = sym.bounds }
    private class FracItem(val bar: Sym, val num: List<Item>, val den: List<Item>) : Item {
        override val bounds: WorldRect = (num + den).fold(bar.bounds) { a, i -> a.union(i.bounds) }
    }
    private class RadItem(val sym: Sym, val inner: List<Item>) : Item { override val bounds get() = sym.bounds }

    fun analyze(symbols: List<Sym>): MathExpression {
        if (symbols.isEmpty()) return MathExpression.Empty
        val row = buildRow(symbols.map { SymItem(it) })
        val flat = ArrayList<MathSymbol>()
        collect(row, flat)
        return MathExpression(row, Linearizer.linear(row), flat.sortedBy { it.bounds.left })
    }

    private fun collect(s: MathStructure, out: MutableList<MathSymbol>) {
        when (s) {
            is MathStructure.Leaf -> out += s.symbol
            is MathStructure.Row -> s.children.forEach { collect(it, out) }
            is MathStructure.Fraction -> { out += s.bar; collect(s.numerator, out); collect(s.denominator, out) }
            is MathStructure.Radical -> { out += s.symbol; collect(s.radicand, out) }
            is MathStructure.Script -> collect(s.exponent, out)
        }
    }

    private fun isSymbolic(i: Item, vararg labels: String) = i is SymItem && i.sym.label in labels

    private fun buildRow(input: List<Item>): MathStructure.Row {
        val items = input.toMutableList()

        // Containers, widest first, so outer fractions claim inner structures.
        val containers = items.filterIsInstance<SymItem>().filter { it.sym.label == "BAR" || it.sym.label == "√" }
            .sortedByDescending { it.sym.bounds.width }
        for (c in containers) {
            if (c !in items) continue
            val b = c.sym.bounds
            if (c.sym.label == "BAR") {
                val inRange = items.filter { it !== c && it.bounds.centerX in (b.left - 0.1f * b.width)..(b.right + 0.1f * b.width) && !(it is SymItem && it.sym.label == "BAR" && it.sym.bounds.width > 1.3f * b.width) }
                val above = inRange.filter { it.bounds.centerY < b.centerY - 0.1f * b.width.coerceAtMost(b.height + 60f).coerceAtLeast(0f) }
                val below = inRange.filter { it.bounds.centerY > b.centerY }
                val aboveStrict = above.filter { it.bounds.bottom <= b.centerY + 2f || it.bounds.centerY < b.centerY }
                if (aboveStrict.isNotEmpty() && below.isNotEmpty()) {
                    items.removeAll((aboveStrict + below).toSet()); items.remove(c)
                    items += FracItem(b.let { c.sym }, aboveStrict.sortedBy { it.bounds.left }, below.sortedBy { it.bounds.left })
                }
            } else {
                val h = b.height
                val inner = items.filter { it !== c && it.bounds.centerX in (b.left + 0.15f * h)..(b.right + 0.1f * h) && it.bounds.centerY in b.top..(b.bottom + 0.05f * h) }
                if (inner.isNotEmpty()) {
                    items.removeAll(inner.toSet()); items.remove(c)
                    items += RadItem(c.sym, inner.sortedBy { it.bounds.left })
                }
            }
        }
        items.sortBy { it.bounds.left }

        // Resolve provisional labels that depend on neighbours.
        for ((idx, it) in items.withIndex()) if (it is SymItem) {
            when (it.sym.label) {
                "BAR" -> it.sym = it.sym.copy(label = "-")
                "DOT" -> {
                    val neighbours = listOfNotNull(items.getOrNull(idx - 1), items.getOrNull(idx + 1)).filter { n -> !(n is SymItem && n.sym.label == "DOT") }
                    val low = if (neighbours.isEmpty()) true else {
                        val refBottom = neighbours.map { n -> n.bounds.bottom }.average().toFloat()
                        val refH = neighbours.map { n -> n.bounds.height }.average().toFloat()
                        it.sym.cy > refBottom - 0.3f * refH
                    }
                    it.sym = it.sym.copy(label = if (low) "." else "·")
                }
            }
        }

        val children = ArrayList<MathStructure>()
        var base: Item? = null
        var i = 0
        while (i < items.size) {
            val cur = items[i]
            val b = base
            if (b != null && isScript(cur, b) && canStartScript(cur)) {
                val exps = ArrayList<Item>()
                exps += cur; i++
                // the run continues only while symbols stay at the exponent's own height
                while (i < items.size && isScript(items[i], b) && sameLevel(exps.last(), items[i])) { exps += items[i]; i++ }
                children += MathStructure.Script(buildRow(exps))
                continue
            }
            children += toStructure(cur)
            base = if (isBaseable(cur)) cur else null
            i++
        }
        return MathStructure.Row(children)
    }

    private fun isBaseable(i: Item) = when (i) {
        is SymItem -> i.sym.label.length == 1 && (i.sym.label[0].isDigit() || i.sym.label in listOf("X", ")"))
        else -> true
    }

    /** An exponent begins with a digit, variable, minus sign or opening bracket; '+', '=', '×' etc. start a new term. */
    private fun canStartScript(i: Item) = i is SymItem && (i.sym.label[0].isDigit() || i.sym.label in listOf("X", "-", "("))

    private fun sameLevel(prev: Item, next: Item): Boolean {
        val tol = 0.35f * maxOf(prev.bounds.height, next.bounds.height).coerceAtLeast(1f)
        return kotlin.math.abs(prev.bounds.centerY - next.bounds.centerY) <= tol
    }

    private fun isScript(it: Item, base: Item): Boolean {
        if (it is SymItem && (it.sym.label == "." || it.sym.label == "·")) return false
        if (it is FracItem || it is RadItem) return false
        val bb = base.bounds; val ib = it.bounds
        if (ib.left < bb.left) return false
        val high = ib.centerY < bb.top + 0.40f * bb.height
        val raised = ib.bottom < bb.bottom - 0.2f * bb.height
        return high && raised
    }

    private fun toStructure(i: Item): MathStructure = when (i) {
        is SymItem -> MathStructure.Leaf(i.sym.toSymbol())
        is FracItem -> MathStructure.Fraction(buildRow(i.num), buildRow(i.den), i.bar.toSymbol("frac"))
        is RadItem -> MathStructure.Radical(buildRow(i.inner), i.sym.toSymbol())
    }
}

/** Renders a [MathStructure] as parser-ready text and resolves × versus the variable x from context. */
internal object Linearizer {
    private data class Tok(val text: String, val starts: Boolean, val ends: Boolean, val cross: Boolean = false, val slash: Boolean = false)

    fun linear(row: MathStructure.Row): String = tokens(row).joinToString("") { it.text }

    private fun tokens(row: MathStructure.Row): List<Tok> {
        val out = ArrayList<Tok>()
        for (c in row.children) when (c) {
            is MathStructure.Leaf -> out += leaf(c.symbol.label)
            is MathStructure.Fraction -> out += Tok("(${linear(c.numerator)})/(${linear(c.denominator)})", true, true)
            is MathStructure.Radical -> out += Tok("√(${linear(c.radicand)})", true, true)
            is MathStructure.Script -> out += Tok("^(${linear(c.exponent)})", false, true)
            is MathStructure.Row -> out += tokens(c)
        }
        // '/' versus a slanted '1': a division slash needs an operand on its left, otherwise it is a one
        for (i in out.indices) if (out[i].slash) {
            out[i] = if (out.getOrNull(i - 1)?.ends == true) Tok("/", false, false) else Tok("1", true, true)
        }
        // × versus x: multiplication only between something that ends an operand and something that starts one
        for (i in out.indices) if (out[i].cross) {
            val left = out.getOrNull(i - 1)?.ends == true
            val right = out.getOrNull(i + 1)?.let { it.starts || it.cross } == true
            out[i] = if (left && right) Tok("×", false, false) else Tok("x", true, true)
        }
        return out
    }

    private fun leaf(label: String): Tok = when {
        label.length == 1 && label[0].isDigit() -> Tok(label, true, true)
        label == "." -> Tok(".", true, true)
        label == "X" -> Tok("x", true, true, cross = true)
        label == "(" -> Tok("(", true, false)
        label == ")" -> Tok(")", false, true)
        label == "√" -> Tok("√", true, false)
        label == "/" -> Tok("/", false, false, slash = true)
        label == "·" -> Tok("×", false, false)
        label == "?" -> Tok("?", false, false)
        else -> Tok(label, false, false)      // + - ÷ / =
    }
}
