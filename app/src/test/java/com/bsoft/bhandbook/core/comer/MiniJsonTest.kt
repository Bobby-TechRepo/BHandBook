package com.bsoft.bhandbook.core.comer

import com.bsoft.bhandbook.core.codec.MiniJson
import org.junit.Assert.*
import org.junit.Test

class MiniJsonTest {
    @Test fun parsesObjectsArraysNumbersStringsLiterals() {
        val v = ComerTestData.map(MiniJson.parse("""{"a":[1,2.5,-3e2],"b":{"c":"x\"y\\z\u221a"},"d":true,"e":null,"f":false}"""))
        assertEquals(listOf(1.0, 2.5, -300.0), ComerTestData.list(v["a"]))
        assertEquals("x\"y\\z√", ComerTestData.map(v["b"])["c"]); assertEquals(true, v["d"]); assertNull(v["e"]); assertEquals(false, v["f"])
    }
    @Test fun keepsBackslashesInVocabStyleKeys() { assertEquals(1.0, ComerTestData.map(MiniJson.parse("""{"\\frac":1}"""))["\\frac"]) }
    @Test fun acceptsWindowsLineEndingsAndWhitespace() { assertEquals(listOf(1.0), ComerTestData.list(MiniJson.parse("\r\n [ 1 ]\r\n"))) }
    @Test fun rejectsMalformedInput() {
        listOf("", "{", "[1,", "{\"a\"}", "{\"a\":}", "tru", "[1 2]", "\"abc", "{\"a\":1,}", "01x", "[1]x").forEach {
            try { MiniJson.parse(it); fail("should reject: $it") } catch (_: MiniJson.ParseFailure) {}
        }
    }
    @Test fun rejectsExcessiveNesting() {
        try { MiniJson.parse("[".repeat(10_000)); fail() } catch (_: MiniJson.ParseFailure) {}
    }
}
