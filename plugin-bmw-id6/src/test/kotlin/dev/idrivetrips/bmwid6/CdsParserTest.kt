package dev.idrivetrips.bmwid6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class CdsParserTest {

    private fun snap(vararg pairs: Pair<String, String>) = CdsParser.toCarValues(mapOf(*pairs))

    @Test
    fun emptyInputGivesAnEmptySnapshot() {
        assertEquals(dev.idrivetrips.carapi.CarValues.EMPTY, snap())
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

    // ---- CdsState ------------------------------------------------------------------------------------------------

    @Test
    fun carStateStoresKnownPropertiesAndRefusesTheRest() {
        var now = 1000L
        val state = CdsState { now }
        assertEquals(0L, state.lastUpdateMillis)
        assertEquals(CdsState.Result.STORED, state.receive(CdsProperty.ODOMETER, """{"odometer":500}"""))
        assertEquals(1000L, state.lastUpdateMillis)
        assertEquals(500.0, state.values().odometerKm!!, 0.0)

        now = 2000L
        assertEquals(CdsState.Result.UNKNOWN_PROPERTY, state.receive("evil.property", "{}"))
        assertEquals(CdsState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, "not json"))
        assertEquals(CdsState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, ""))
        assertEquals(CdsState.Result.IGNORED_BAD_VALUE, state.receive(CdsProperty.FUEL, "{\"x\":\"" + "a".repeat(CdsState.MAX_VALUE_CHARS) + "\"}"))
        assertEquals("rejected values do not count as updates", 1000L, state.lastUpdateMillis)
        assertNull(state.values().fuelLiters)

        state.reset()
        assertEquals(0L, state.lastUpdateMillis)
        assertFalse(state.values().odometerKm != null)
    }

    @Test
    fun aNewerValueReplacesTheOldOne() {
        val state = CdsState()
        state.receive(CdsProperty.ODOMETER, """{"odometer":500}""")
        state.receive(CdsProperty.ODOMETER, """{"odometer":501}""")
        assertEquals(501.0, state.values().odometerKm!!, 0.0)
        assertEquals(2L, state.version)
    }

    @Test
    fun everyPartTheParserNamesIsInTheContract() {
        val raw = mapOf(
            CdsProperty.DOORS to """{"doors":{"driver":1,"passenger":1,"driverRear":1,"passengerRear":1}}""",
            CdsProperty.TRUNK to """{"trunk":1}""", CdsProperty.LID to """{"lid":1}""",
            CdsProperty.WINDOW_DRIVER_FRONT to """{"windowDriverFront":{"status":2,"position":50}}""",
            CdsProperty.WINDOW_PASSENGER_FRONT to """{"windowPassengerFront":{"status":2,"position":50}}""",
            CdsProperty.WINDOW_DRIVER_REAR to """{"windowDriverRear":{"status":2,"position":50}}""",
            CdsProperty.WINDOW_PASSENGER_REAR to """{"windowPassengerRear":{"status":2,"position":50}}""",
            CdsProperty.SUNROOF to """{"sunroof":{"status":2,"position":50}}""",
        )
        val labels = CdsParser.toCarValues(raw).openParts!!
        assertEquals(dev.idrivetrips.carapi.CarValues.PART_LABELS.toSet(), labels.toSet())
        val tilted = CdsParser.toCarValues(mapOf(CdsProperty.SUNROOF to """{"sunroof":{"openPosition":0,"tiltPosition":3,"status":0}}""")).openParts!!
        assertTrue(tilted.all { dev.idrivetrips.carapi.CarValues.isPartLabel(it) })
        assertEquals(listOf("sunroof (tilted)"), tilted)
    }
}
