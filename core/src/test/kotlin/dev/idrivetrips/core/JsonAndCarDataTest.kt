package dev.idrivetrips.core

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

class CarDataTest {

    private fun snap(vararg pairs: Pair<String, String>) = CarSnapshot.fromRaw(mapOf(*pairs))

    @Test
    fun emptyInputGivesAnEmptySnapshot() {
        assertEquals(CarSnapshot.EMPTY, snap())
        assertNull(snap().openParts)
    }

    @Test
    fun readsOdometerFuelAndTemperatures() {
        val s = snap(
            CdsProperty.ODOMETER to """{"odometer":12345}""",
            CdsProperty.FUEL to """{"fuel":{"tanklevel":30,"range":420,"reserve":0}}""",
            CdsProperty.TEMP_OUTSIDE to """{"temperatureExterior":7.5}""",
            CdsProperty.TEMP_INSIDE to """{"temperatureInterior":21}""",
        )
        assertEquals(12345.0, s.odometerKm!!, 0.0)
        assertEquals(30.0, s.fuelLiters!!, 0.0)
        assertEquals(420.0, s.rangeKm!!, 0.0)
        assertEquals(7.5, s.outsideC!!, 0.0)
        assertEquals(21.0, s.insideC!!, 0.0)
    }

    @Test
    fun rejectsImplausibleValues() {
        val s = snap(
            CdsProperty.ODOMETER to """{"odometer":0}""",
            CdsProperty.FUEL to """{"fuel":{"tanklevel":-1,"range":99999}}""",
            CdsProperty.TEMP_OUTSIDE to """{"temperatureExterior":-80}""",
            CdsProperty.TEMP_INSIDE to """{"temperatureInterior":"hot"}""",
        )
        assertNull(s.odometerKm)
        assertNull(s.fuelLiters)
        assertNull(s.rangeKm)
        assertNull(s.outsideC)
        assertNull(s.insideC)
    }

    @Test
    fun convertsAverageConsumptionFromEveryUnit() {
        fun km(unit: Int, v: Double) = snap(
            CdsProperty.AVG_CONSUMPTION to """{"averageConsumption":{"averageConsumption2":$v,"unit":$unit}}"""
        ).avgConsumptionKmPerL
        assertEquals(14.2, km(4, 14.2)!!, 1e-9)                // km/l
        assertEquals(100.0 / 7.0, km(1, 7.0)!!, 1e-9)          // l/100 km
        assertEquals(40.0 * 0.354006, km(2, 40.0)!!, 1e-9)     // mpg UK
        assertEquals(30.0 * 0.425144, km(3, 30.0)!!, 1e-9)     // mpg US
        assertNull(km(4, 0.0))                                  // no value
        assertNull(km(4, 250.0))                                // out of range
    }

    @Test
    fun fallsBackToTheFirstAverageWhenTheSecondIsMissing() {
        val s = snap(CdsProperty.AVG_CONSUMPTION to """{"averageConsumption":{"averageConsumption1":13.8,"unit":4}}""")
        assertEquals(13.8, s.avgConsumptionKmPerL!!, 1e-9)
    }

    @Test
    fun listsOnlyPartsThatAreNotClosed() {
        val s = snap(
            CdsProperty.DOORS to """{"doors":{"driver":0,"passenger":1,"driverRear":0,"passengerRear":0}}""",
            CdsProperty.TRUNK to """{"trunk":0}""",
            CdsProperty.LID to """{"lid":1}""",
            CdsProperty.SUNROOF to """{"sunroof":{"openPosition":0,"tiltPosition":3,"status":0}}""",
            CdsProperty.WINDOW_DRIVER_FRONT to """{"windowDriverFront":{"position":19,"status":1,"drive":1}}""",
            CdsProperty.WINDOW_PASSENGER_FRONT to """{"windowPassengerFront":{"position":0,"status":0,"drive":1}}""",
        )
        assertEquals(listOf("sunroof (tilted)", "bonnet", "passenger door", "driver window"), s.openParts)
    }

    @Test
    fun allClosedGivesAnEmptyListNotNull() {
        val s = snap(CdsProperty.DOORS to """{"doors":{"driver":0,"passenger":0}}""")
        assertEquals(emptyList<String>(), s.openParts)
    }

    @Test
    fun invalidWindowAndSunroofStatesAreUnknownNotOpen() {
        val s = snap(
            CdsProperty.WINDOW_DRIVER_FRONT to """{"windowDriverFront":{"position":127,"status":3,"drive":0}}""",
            CdsProperty.SUNROOF to """{"sunroof":{"openPosition":0,"tiltPosition":0,"status":255}}""",
        )
        assertNull(s.openParts)
    }

    // ---- CarState ------------------------------------------------------------------------------------------------

    @Test
    fun carStateStoresKnownPropertiesAndRefusesTheRest() {
        var now = 1000L
        val state = CarState { now }
        assertEquals(0L, state.lastUpdateMillis)
        assertEquals(CarState.Result.STORED, state.receive(CdsProperty.ODOMETER, """{"odometer":500}"""))
        assertEquals(1000L, state.lastUpdateMillis)
        assertEquals(500.0, state.snapshot().odometerKm!!, 0.0)

        now = 2000L
        assertEquals(CarState.Result.UNKNOWN_PROPERTY, state.receive("evil.property", "{}"))
        assertEquals(CarState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, "not json"))
        assertEquals(CarState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, ""))
        assertEquals(CarState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, "{\"x\":\"" + "a".repeat(400) + "\"}"))
        assertEquals("rejected values do not count as updates", 1000L, state.lastUpdateMillis)
        assertNull(state.snapshot().fuelLiters)

        state.reset()
        assertEquals(0L, state.lastUpdateMillis)
        assertFalse(state.snapshot().odometerKm != null)
    }

    @Test
    fun aNewerValueReplacesTheOldOne() {
        val state = CarState()
        state.receive(CdsProperty.ODOMETER, """{"odometer":500}""")
        state.receive(CdsProperty.ODOMETER, """{"odometer":501}""")
        assertEquals(501.0, state.snapshot().odometerKm!!, 0.0)
    }
}
