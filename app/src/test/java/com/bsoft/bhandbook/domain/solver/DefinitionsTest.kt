package com.bsoft.bhandbook.domain.solver

import com.bsoft.bhandbook.core.math.*
import org.junit.Assert.*
import org.junit.Test

class DefinitionsTest {
    private val parser = DefaultMathParser()
    private fun st(src: String) = (parser.parse(src) as ParseResult.Success).statement
    private fun expr(src: String) = (st(src) as MathStatement.Expression).expr
    private fun def(src: String, order: Long = 0) = DefinitionParser.parse(st(src), order)
    private fun resolve(vararg src: String, calc: CalcSettings = CalcSettings()): Definitions =
        DefinitionResolver.resolve(src.mapIndexedNotNull { i, s -> def(s, i.toLong()) }, calc)
    private fun value(d: Definitions, name: String): String = NumberFormatter.format(d.values.getValue(name)).primary
    private fun evalWith(src: String, d: Definitions): String {
        val e = Substitution.expandCalls(expr(src), d.functions)
        return (ExactCalculationEngine().evaluate(e, d.values) as CalcResult.Success).value.let { NumberFormatter.format(it).primary }
    }

    // ---------------------------------------------------------------- what counts as a definition
    @Test fun aValueDefinition() { val d = def("a=5") as ValueDefinition; assertEquals("a", d.name); assertEquals(Rational.of(5), ((d.expr) as Num).value) }
    @Test fun aFunctionDefinition() { val d = def("f(x)=2x+1") as FunctionDefinition; assertEquals("f", d.name); assertEquals("x", d.param) }
    @Test fun theUnknownsOfEquations_cannotBeDefined() { for (s in listOf("x=5", "y=2", "z=1", "x=a+1")) assertNull(s, def(s)) }
    @Test fun otherShapes_areNotDefinitions() {
        for (s in listOf("2a=6", "a+b=5", "a=a+1", "f(f)=2", "f(x)=f(x)+1", "x(y)=3", "2a=b", "5=a", "a")) assertNull(s, runCatching { def(s) }.getOrNull())
    }
    @Test fun aFunctionMayUseAnyLetterAsItsParameter() { assertEquals("t", (def("g(t)=t^2") as FunctionDefinition).param) }
    @Test fun statementsThatAreExpressions_areNotDefinitions() { assertNull(DefinitionParser.parse(st("2+3"), 0)) }

    // ---------------------------------------------------------------- resolving values
    @Test fun values_areEvaluatedExactly() { val d = resolve("a=5", "b=a*2", "c=b/4"); assertEquals("5", value(d, "a")); assertEquals("10", value(d, "b")); assertEquals("5/2", value(d, "c")) }
    @Test fun valuesMayUseTrigInTheChosenUnit() { assertEquals("1/2", value(resolve("s=sin(30)", calc = CalcSettings(angle = AngleMode.DEGREES)), "s")) }
    @Test fun theLatestDefinitionOfANameWins() { assertEquals("9", value(resolve("a=5", "a=9"), "a")); assertEquals("5", value(DefinitionResolver.resolve(listOf(def("a=5", order = 7)!!, def("a=9", order = 3)!!), CalcSettings()), "a")) }
    @Test fun aValueThatNeedsSomethingUndefined_isSimplyNotADefinition() { val d = resolve("a=2*q"); assertFalse("a" in d.values); assertTrue("not an error: it may be an ordinary equation (a = 2q)", d.problems.isEmpty()) }
    @Test fun circularValues_areReportedOnEveryNameInTheCycle() {
        val d = resolve("a=b+1", "b=a+1", "c=5"); assertFalse("a" in d.values || "b" in d.values); assertTrue("a" in d.problems && "b" in d.problems); assertEquals("5", value(d, "c"))
        assertTrue(d.problems.getValue("a").contains("circular", ignoreCase = true))
    }
    @Test fun aValueThatDependsOnACircularOne_isDropped() { val d = resolve("a=b+1", "b=a+1", "c=a*2"); assertFalse("c" in d.values) }
    @Test fun aValueThatCannotBeComputed_isReported() { val d = resolve("a=1/0"); assertFalse("a" in d.values); assertTrue("a" in d.problems) }
    @Test fun valuesDependingOnFunctions() { assertEquals("7", value(resolve("f(x)=2x+1", "a=f(3)"), "a")) }

    // ---------------------------------------------------------------- resolving functions
    @Test fun functionsAreAcceptedWhenTheirBodyUsesOnlyTheParameterAndDefinedNames() {
        val d = resolve("f(x)=2x+a", "a=1", "g(t)=f(t)+1"); assertEquals(setOf("f", "g"), d.functions.keys)
    }
    @Test fun aFunctionUsingAnUndefinedName_isNotADefinition() { assertTrue(resolve("f(x)=x+q").functions.isEmpty()) }
    @Test fun mutuallyRecursiveFunctions_areReported() { val d = resolve("f(x)=g(x)", "g(x)=f(x)"); assertTrue(d.functions.isEmpty()); assertTrue("f" in d.problems && "g" in d.problems) }
    @Test fun aFunctionAndAValueMayShareALetter_asADifferentKindOfThing() { val d = resolve("f(x)=x+1", "g=4"); assertTrue("f" in d.functions && "g" in d.values) }

    // ---------------------------------------------------------------- using them
    @Test fun callingAFunction() { val d = resolve("f(x)=2x+1"); assertEquals("7", evalWith("f(3)", d)); assertEquals("14", evalWith("2f(3)", d)); assertEquals("8", evalWith("f(3)+1", d)); assertEquals("1", evalWith("f(0)", d)) }
    @Test fun callingAFunctionWithAnExpression() { val d = resolve("f(x)=x^2-4", "a=2"); assertEquals("5", evalWith("f(a+1)", d)); assertEquals("0", evalWith("f(a)", d)); assertEquals("-4", evalWith("f(0)", d)) }
    @Test fun nestedCalls() { val d = resolve("f(x)=2x+1", "g(t)=f(t)*3"); assertEquals("21", evalWith("g(3)", d)); assertEquals("15", evalWith("f(f(1))*3-6", d)) }
    @Test fun valuesInExpressions() { val d = resolve("a=5", "b=a*2"); assertEquals("11", evalWith("2a+1", d)); assertEquals("20", evalWith("b+b", d)); assertEquals("50", evalWith("a*b", d)) }
    @Test fun aNameThatIsNotAFunction_isStillJustMultiplication() { val d = resolve("a=5"); assertEquals("15", evalWith("3a", d)); assertEquals("15", evalWith("a(3)", d)) }
    @Test fun anUndefinedFunctionCall_isLeftAlone_soItFailsClearly() { val e = Substitution.expandCalls(expr("h(3)"), resolve("f(x)=2x+1").functions); assertEquals(Substitution.namesIn(expr("h(3)")), Substitution.namesIn(e)) }
    @Test fun expansionStopsOnRunawayRecursion() {
        val bad = mapOf("f" to FunctionDefinition("f", "x", expr("f(x)+1")))
        val e = Substitution.expandCalls(expr("f(1)"), bad); assertNotNull(e)                       // must terminate, whatever it returns
    }

    // ---------------------------------------------------------------- substituting numbers
    @Test fun withValues_replacesDefinedNamesByNumbers() {
        val d = resolve("a=5", "r=2/3", "n=-4", "t=0.25")
        assertEquals("5", Printer.print(Substitution.withValues(expr("a"), d)))
        assertEquals(setOf("x"), Substitution.namesIn(Substitution.withValues(expr("a*x+r+n+t"), d)))
        assertEquals(Rational.of(-4), ((ExactCalculationEngine().evaluate(Substitution.withValues(expr("n"), d)) as CalcResult.Success).value as Value.Exact).value)
        assertEquals(Rational.of(2, 3), ((ExactCalculationEngine().evaluate(Substitution.withValues(expr("r"), d)) as CalcResult.Success).value as Value.Exact).value)
    }
    @Test fun withValues_neverTouchesTheUnknownsXYZ() { val d = resolve("a=5"); assertEquals(setOf("x", "y", "z"), Substitution.namesIn(Substitution.withValues(expr("a+x+y+z"), d))) }
    @Test fun withValues_keepsPrecedence() { val d = resolve("n=-4"); assertEquals(-24.0, ((ExactCalculationEngine().evaluate(Substitution.withValues(expr("3*n*2"), d)) as CalcResult.Success).value).toDouble(), 0.0); assertEquals(16.0, ((ExactCalculationEngine().evaluate(Substitution.withValues(expr("n^2"), d)) as CalcResult.Success).value).toDouble(), 0.0) }
    @Test fun approximateValuesBecomeDecimals() { val d = resolve("r=sqrt(2)"); assertEquals(1.41421356237, ((ExactCalculationEngine().evaluate(Substitution.withValues(expr("r"), d)) as CalcResult.Success).value).toDouble(), 1e-9) }

    // ---------------------------------------------------------------- describing them
    @Test fun describe_listsValuesAndFunctions_andProblems() {
        val d = resolve("a=5", "f(x)=2x+1", "p=q+1", "m=n+1", "n=m+1")
        assertTrue(d.describe().contains("a = 5")); assertTrue(d.describe().any { it.startsWith("f(x) = ") && it.contains("2x") })
        assertTrue("a circular pair is listed as a problem", d.describe().any { it.startsWith("m: ") && it.contains("circular") }); assertTrue("an undefined name is not a problem, just not a definition", d.describe().none { it.startsWith("p") })
    }
    @Test fun signature_changesWhenAnythingChanges_andOnlyThen() {
        val a = resolve("a=5", "f(x)=2x+1"); assertEquals(a.signature(), resolve("a=5", "f(x)=2x+1").signature())
        assertNotEquals(a.signature(), resolve("a=6", "f(x)=2x+1").signature()); assertNotEquals(a.signature(), resolve("a=5", "f(x)=2x+2").signature()); assertNotEquals(a.signature(), resolve("a=5").signature())
        assertEquals("", Definitions.EMPTY.signature())
    }


    @Test fun aPowerOfACall_belongsToTheCall() { val d = resolve("f(x)=2x+1"); assertEquals("49", evalWith("f(3)^2", d)); assertEquals("98", evalWith("2f(3)^2", d)); assertEquals("1", evalWith("f(0)^3", d)) }
    @Test fun aCallPrintsAsACall() { assertEquals("f(x)", Printer.print(expr("f(x)"))); assertEquals("2f(3) + 1", Printer.print(expr("2f(3)+1"))) }
}
