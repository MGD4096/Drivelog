package dev.drivelog.carapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {

    @Test
    fun parsesNestedValuesAndEscapes() {
        val o = Json.parseObject("""{"a":1,"b":[true,null,"xA\n\"q\""],"c":{"d":-1.5e2}}""")!!
        assertEquals(1.0, o["a"] as Double, 0.0)
        val b = o["b"] as List<*>
        assertEquals(true, b[0])
        assertNull(b[1])
        assertEquals("xA\n\"q\"", b[2])
        assertEquals(-150.0, (o["c"] as Map<*, *>)["d"] as Double, 0.0)
    }

    @Test
    fun roundTripsThroughStringify() {
        val original = linkedMapOf(
            "s" to "a\"b\\c\n\u0001é",
            "ms" to 1_700_000_000_123L,
            "d" to 0.1,
            "tiny" to 1.0E-7,
            "list" to listOf(1, null, "x"),
        )
        val text = Json.stringify(original)
        assertTrue("integral numbers are written without exponent: $text", text.contains("1700000000123"))
        val back = Json.parseObject(text)!!
        assertEquals("a\"b\\c\n\u0001é", back["s"])
        assertEquals(1_700_000_000_123.0, back["ms"] as Double, 0.0)
        assertEquals(0.1, back["d"] as Double, 0.0)
        assertEquals(1.0E-7, back["tiny"] as Double, 0.0)
        assertEquals(listOf(1.0, null, "x"), back["list"])
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("[null,null]", Json.stringify(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("""{"a":}""", "[1,]", """{"a":1} x""", "\"open", "{", """{"a" 1}""", "tru", "-", "1.", "[1 2]")) {
            assertNull("should reject: $bad", Json.parseObject(bad))
            try { Json.parse(bad); fail("parse should throw: $bad") } catch (_: Json.JsonException) {}
        }
    }

    @Test
    fun parseObjectOnlyAcceptsObjects() {
        assertNull(Json.parseObject("[1]"))
        assertNull(Json.parseObject("5"))
        assertNotNull(Json.parseObject("{}"))
    }

    @Test
    fun deepNestingIsRejectedInsteadOfOverflowingTheStack() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        try { Json.parse(deep); fail("should throw") } catch (_: Json.JsonException) {}
    }
}
