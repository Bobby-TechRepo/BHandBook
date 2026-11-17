package com.bsoft.bhandbook.robustness

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.core.recognition.MathRecognitionEngine
import com.bsoft.bhandbook.domain.graph.GraphBuilder
import com.bsoft.bhandbook.domain.model.Stroke
import com.bsoft.bhandbook.domain.solver.*
import com.bsoft.bhandbook.domain.usecase.ProcessExpressionUseCase
import com.bsoft.bhandbook.domain.usecase.ProcessingSettings
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Definitions can refer to each other, to themselves and in circles. Whatever a user writes, resolving and using them must finish quickly and never throw. */
class DefinitionsFuzzTest {
    private val parser = DefaultMathParser()
    private val none = object : MathRecognitionEngine { override fun recognize(strokes: List<Stroke>, isCancelled: () -> Boolean) = throw AssertionError("not used") }
    private val useCase = ProcessExpressionUseCase(none, parser, ExactCalculationEngine(), DefaultSolutionEngine())
    private val names = listOf("a", "b", "c", "d"); private val fns = listOf("f", "g", "h"); private val params = listOf("x", "t", "u")

    private fun term(r: Random, depth: Int = 0): String = when (r.nextInt(if (depth > 2) 4 else 9)) {
        0 -> (r.nextInt(20) - 5).toString()
        1 -> names[r.nextInt(names.size)]
        2 -> params[r.nextInt(params.size)]
        3 -> listOf("x", "2", "y", "q")[r.nextInt(4)]
        4 -> "${fns[r.nextInt(fns.size)]}(${term(r, depth + 1)})"
        5 -> "(${term(r, depth + 1)}${listOf("+", "-", "*", "/", "^")[r.nextInt(5)]}${term(r, depth + 1)})"
        6 -> "${fns[r.nextInt(fns.size)]}(${term(r, depth + 1)})^${r.nextInt(3)}"
        7 -> "sin(${term(r, depth + 1)})"
        else -> "${term(r, depth + 1)}${listOf("+", "*")[r.nextInt(2)]}${term(r, depth + 1)}"
    }
    private fun definition(r: Random) = if (r.nextBoolean()) "${names[r.nextInt(names.size)]}=${term(r)}" else "${fns[r.nextInt(fns.size)]}(${params[r.nextInt(params.size)]})=${term(r)}"

    @Test fun randomDefinitions_neverThrow_andAlwaysFinishQuickly() {
        val r = Random(20261111); var parsed = 0; var resolvedAny = 0; var problems = 0
        val t0 = System.nanoTime()
        repeat(600) {
            val sources = List(1 + r.nextInt(8)) { definition(r) }
            val candidates = sources.mapIndexedNotNull { i, s -> ((parser.parse(s) as? ParseResult.Success)?.statement)?.let { DefinitionParser.parse(it, i.toLong()) } }
            parsed += candidates.size
            val started = System.nanoTime()
            val defs = DefinitionResolver.resolve(candidates, CalcSettings(angle = AngleMode.values()[r.nextInt(3)]))
            if (!defs.isEmpty) resolvedAny++; problems += defs.problems.size
            assertTrue("every resolved value is finite", defs.values.values.all { v -> v.toDouble().let { !it.isNaN() } })
            defs.describe(); defs.signature()
            for (src in listOf("f(2)", "a+b", "g(f(1))", "h(a)*2", "f(g(h(1)))", "a", "f(x)^2", "2c+d")) {
                val st = (parser.parse(src) as? ParseResult.Success)?.statement ?: continue
                if (st is MathStatement.Expression) { Substitution.withValues(Substitution.expandCalls(st.expr, defs.functions), defs) }
                useCase.fromText(src + "=", 1f, ProcessingSettings(definitions = defs))
            }
            for (src in listOf("y=f(x)", "y=a*x+b", "x+a=10", "b=a*2", "f(x)=g(x)+1")) {
                val st = (parser.parse(src) as? ParseResult.Success)?.statement ?: continue
                GraphBuilder.build(listOf(st), AngleMode.RADIANS, defs)
                useCase.fromText(src, 1f, ProcessingSettings(definitions = defs))
            }
            assertTrue("one round took too long: ${(System.nanoTime() - started) / 1_000_000} ms for $sources", (System.nanoTime() - started) < 3_000_000_000L)
        }
        println("FUZZ definitions: 600 sets, $parsed definitions parsed, $resolvedAny with something in force, $problems problems reported, ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue("the generator must actually produce usable definitions", resolvedAny > 100)
    }

    @Test fun recursionInEveryShape_terminates() {
        for (set in listOf(
            listOf("f(x)=f(x)+1"), listOf("f(x)=g(x)", "g(x)=f(x)"), listOf("a=a+1"), listOf("a=b", "b=c", "c=a"), listOf("f(x)=f(f(x))"),
            listOf("f(x)=g(x)+1", "g(x)=h(x)+1", "h(x)=f(x)+1"), listOf("a=f(1)", "f(x)=a+x"), listOf("f(x)=2*f(x-1)"),
        )) {
            val candidates = set.mapIndexedNotNull { i, s -> DefinitionParser.parse((parser.parse(s) as ParseResult.Success).statement, i.toLong()) }
            val defs = DefinitionResolver.resolve(candidates, CalcSettings())
            for (src in listOf("f(3)=", "a=", "g(2)+h(1)=")) useCase.fromText(src, 1f, ProcessingSettings(definitions = defs))
            // and even if a runaway definition is handed straight to the expander it stops
            val forced = candidates.filterIsInstance<FunctionDefinition>().associateBy { it.name }
            for (src in listOf("f(3)", "g(2)", "h(1)")) (parser.parse(src) as? ParseResult.Success)?.statement.let { if (it is MathStatement.Expression) Substitution.expandCalls(it.expr, forced) }
        }
    }

    @Test fun aLongChainOfDefinitions_resolvesToTheRightNumber() {
        val letters = "abcdefghijklmnopqrstuvw"                                                  // x, y and z cannot be named
        val chain = listOf("a=1") + letters.drop(1).mapIndexed { i, c -> "$c=${letters[i]}+1" }
        val defs = DefinitionResolver.resolve(chain.reversed().mapIndexedNotNull { i, s -> (parser.parse(s) as? ParseResult.Success)?.statement?.let { DefinitionParser.parse(it, i.toLong()) } }, CalcSettings())
        assertEquals(23, defs.values.size); assertEquals("23", NumberFormatter.format(defs.values.getValue("w")).primary); assertTrue(defs.problems.isEmpty())
    }
}
