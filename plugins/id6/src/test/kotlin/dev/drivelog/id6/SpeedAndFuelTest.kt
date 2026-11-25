package dev.drivelog.id6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedAndFuelTest {

    @Test
    fun speedAndTheTripComputersAverage() {
        val v = CdsParser.toCarValues(mapOf(
            CdsProperty.SPEED to """{"speedActual":87}""",
            CdsProperty.AVG_SPEED to """{"averageSpeed":{"averageSpeed1":41.5,"averageSpeed2":38.2,"unit":1}}""",
        ))
        assertEquals(87.0, v.speedKmh!!, 1e-9)
        assertEquals(38.2, v.avgSpeedKmh!!, 1e-9)
        assertEquals(0.0, CdsParser.toCarValues(mapOf(CdsProperty.SPEED to """{"speedActual":0}""")).speedKmh!!, 1e-9)
        assertNull(CdsParser.toCarValues(mapOf(CdsProperty.SPEED to """{"speedActual":65535}""")).speedKmh)
        val mph = CdsParser.toCarValues(mapOf(CdsProperty.AVG_SPEED to """{"averageSpeed":{"averageSpeed2":30.0,"unit":2}}"""))
        assertEquals(48.28, mph.avgSpeedKmh!!, 0.01)
    }

    @Test
    fun theFuelCounterIsAddedUpAcrossItsWrapAround() {
        val cds = CdsState()
        assertNull(cds.values().fuelUsedTotalL)
        cds.receive(CdsProperty.FUEL_COUNTER, """{"consumption":60000}""")
        assertEquals(0.0, cds.values().fuelUsedTotalL!!, 1e-12)               // the first value is only the start
        cds.receive(CdsProperty.FUEL_COUNTER, """{"consumption":65000}""")
        cds.receive(CdsProperty.FUEL_COUNTER, """{"consumption":2000}""")       // wrapped: 536 + 2000
        assertEquals((5_000 + 2_536) * FuelCounter.LITERS_PER_STEP, cds.values().fuelUsedTotalL!!, 1e-12)
    }

    @Test
    fun aGapIsSkippedNotGuessedAndResetStartsAgain() {
        val c = FuelCounter()
        c.add(1_000)
        c.add(1_000)                                    // the same value: nothing burnt
        c.add(40_000)                                   // 39 000 steps at once: a gap in the connection
        c.add(40_100)
        assertEquals(100 * FuelCounter.LITERS_PER_STEP, c.liters()!!, 1e-12)
        c.add(null)
        c.reset()
        assertNull(c.liters())
    }
}
