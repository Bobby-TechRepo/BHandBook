package com.bsoft.bhandbook.domain.editor

import com.bsoft.bhandbook.core.graphics.WorldRect
import com.bsoft.bhandbook.core.math.CalcSettings
import com.bsoft.bhandbook.core.math.MathStatement
import com.bsoft.bhandbook.domain.solver.MathSolutionEngine
import com.bsoft.bhandbook.domain.solver.Poly
import com.bsoft.bhandbook.domain.solver.SolveOutcome

/** A recognised equation and where its handwriting is on the canvas. */
class EquationBlock(val id: String, val bounds: WorldRect, val equation: MathStatement.Equation)

/** Equations that belong together; [ids] are in reading order (top to bottom) and the result is shown under [anchorId], the lowest one. */
class SystemGroup(val ids: List<String>) { val anchorId: String get() = ids.last() }

/**
 * Finds simultaneous equations on the infinite canvas: equations written one under the other, spaced like lines of text and roughly
 * left-aligned (all distances are relative to the size of the handwriting, so zoom does not matter), where at least one equation has two or more
 * unknowns (a lone one-unknown equation is simply solved by itself).
 */
object EquationSystems {
    private const val MAX_LINE_GAP = 1.6f        // vertical gap, in line heights
    private const val MIN_OVERLAP = 0.3f         // horizontal overlap, as a fraction of the narrower equation
    private const val MAX_LEFT_OFFSET = 1.5f     // alternatively: left edges within this many line heights

    fun detect(blocks: List<EquationBlock>): List<SystemGroup> {
        val n = blocks.size
        if (n < 2) return emptyList()
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in 0 until n) for (j in i + 1 until n) if (stacked(blocks[i].bounds, blocks[j].bounds)) parent[find(i)] = find(j)
        return blocks.indices.groupBy { find(it) }.values
            .filter { it.size >= 2 && it.any { i -> Poly.variablesOf(blocks[i].equation.lhs).union(Poly.variablesOf(blocks[i].equation.rhs)).size >= 2 } }
            .map { idx -> SystemGroup(idx.map { blocks[it] }.sortedBy { it.bounds.top }.map { it.id }) }
    }

    private fun stacked(a: WorldRect, b: WorldRect): Boolean {
        val line = maxOf(a.height, b.height).coerceAtLeast(1f)
        val gapY = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)               // negative when the boxes overlap vertically
        if (gapY > MAX_LINE_GAP * line) return false
        if (gapY < -0.5f * minOf(a.height, b.height)) return false               // overlapping vertically means same line, not stacked
        val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val narrower = minOf(a.width, b.width).coerceAtLeast(1f)
        return overlap >= MIN_OVERLAP * narrower || kotlin.math.abs(a.left - b.left) <= MAX_LEFT_OFFSET * line
    }

    /** Solves the group's equations together, in reading order. */
    fun solve(group: SystemGroup, blocks: List<EquationBlock>, engine: MathSolutionEngine, calc: CalcSettings = CalcSettings()): SolveOutcome {
        val byId = blocks.associateBy { it.id }
        return engine.solveSystem(group.ids.mapNotNull { byId[it]?.equation }, calc)
    }
}
