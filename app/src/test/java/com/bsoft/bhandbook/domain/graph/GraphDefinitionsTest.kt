package com.bsoft.bhandbook.domain.graph

import com.bsoft.bhandbook.core.math.*
import com.bsoft.bhandbook.domain.solver.*
import org.junit.Assert.*
import org.junit.Test

class GraphDefinitionsTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun defs(vararg src: String) = DefinitionResolver.resolve(src.mapIndexedNotNull { i, s -> DefinitionParser.parse(st(s), i.toLong()) }, CalcSettings())
    private fun spec(src: String, d: Definitions) = GraphBuilder.build(listOf(st(src)), AngleMode.RADIANS, d)
    private fun curve(src: String, d: Definitions) = spec(src, d)!!.series[0] as GraphSeries.Curve

    @Test fun aDefinedFunctionCanBeGraphed() { val c = curve("y=f(x)", defs("f(x)=x^2-4")); assertEquals(0.0, c.valueAt(2.0), 1e-9); assertEquals(5.0, c.valueAt(3.0), 1e-9); assertEquals("y = f(x)", c.label) }
    @Test fun definedValuesActAsConstants() { val c = curve("y=a*x+b", defs("a=2", "b=1")); assertEquals(7.0, c.valueAt(3.0), 1e-9); assertEquals("y = " + Printer.print(normalizeLiterals((st("y=a*x+b") as MathStatement.Equation).rhs)), c.label) }
    @Test fun aLinearEquationWithDefinedCoefficients() { val s = spec("a*x+y=b", defs("a=2", "b=10"))!!; assertEquals("y = -2x + 10", s.series[0].label); assertEquals(4.0, (s.series[0] as GraphSeries.Curve).valueAt(3.0), 1e-9) }
    @Test fun functionsMayCallFunctionsAndValues() { val c = curve("y=g(x)", defs("a=1", "f(t)=2t+a", "g(x)=f(x)^2")); assertEquals(49.0, c.valueAt(3.0), 1e-9) }
    @Test fun withoutTheDefinition_itCannotBeGraphed() { assertNull(spec("y=f(x)", Definitions.EMPTY)); assertNull(spec("y=a*x", Definitions.EMPTY)) }
    @Test fun canGraph_seesThroughDefinitions() { assertTrue(GraphBuilder.canGraph(listOf(st("y=f(x)")), AngleMode.RADIANS, defs("f(x)=x+1"))); assertFalse(GraphBuilder.canGraph(listOf(st("y=f(x)")), AngleMode.RADIANS, Definitions.EMPTY)) }
    @Test fun aFunctionPlusAnotherCurveFindsTheirIntersection() { val m = GraphBuilder.build(listOf(st("y=f(x)"), st("y=x")), AngleMode.RADIANS, defs("f(x)=x^2-2"))!!.markers.map { Math.round(it.x * 100) / 100.0 }.sorted(); assertEquals(listOf(-1.0, 2.0), m) }
    @Test fun definitionsStillWorkInTheChosenAngleUnit() { val d = DefinitionResolver.resolve(listOf(DefinitionParser.parse(st("w(x)=sin(x)"))!!), CalcSettings(angle = AngleMode.DEGREES)); assertEquals(0.5, ((GraphBuilder.build(listOf(st("y=w(x)")), AngleMode.DEGREES, d)!!.series[0]) as GraphSeries.Curve).valueAt(30.0), 1e-12) }
    @Test fun graphsWithoutDefinitionsAreUnchanged() { assertEquals(7.0, (GraphBuilder.build(listOf(st("y=2x+1")))!!.series[0] as GraphSeries.Curve).valueAt(3.0), 1e-9) }
    @Test fun aGraphOfADefinedFunctionIsFramedNormally() { val d = GraphModel.layout(spec("y=f(x)", defs("f(x)=x^2-4"))!!); assertEquals(-10.0, d.viewport.xMin, 1e-9); assertTrue(d.curves[0].segments.isNotEmpty()) }

    @Test fun theDefinitionOfAFunctionGraphsAsOneCurve() {
        val s = spec("f(x)=x^2-4", Definitions.EMPTY)!!; assertEquals(1, s.series.size); val c = s.series[0] as GraphSeries.Curve; assertEquals("y = f(x)", c.label); assertEquals(5.0, c.valueAt(3.0), 1e-9)
        assertEquals(9.0, (spec("g(t)=t^2", Definitions.EMPTY)!!.series[0] as GraphSeries.Curve).valueAt(3.0), 1e-9)          // any parameter letter is graphed against x
        assertEquals(7.0, (spec("h(x)=a*x+1", defs("a=2"))!!.series[0] as GraphSeries.Curve).valueAt(3.0), 1e-9)
        assertNull(spec("h(x)=q*x", Definitions.EMPTY))
    }
}
