package dev.idrivetrips.carapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarValuesTest {
    private val full = CarValues(12345.0, 31.0, 420.0, 14.2, 6.5, 21.0, listOf("driver door", "sunroof (tilted)"))

    @Test
    fun plausibleValuesPassUnchanged() {
        assertEquals(full, full.checked())
        assertEquals(CarValues.EMPTY, CarValues.EMPTY.checked())
    }

    @Test
    fun implausibleValuesBecomeUnknown() {
        val bad = CarValues(-1.0, 301.0, Double.NaN, 0.5, -70.0, 95.0, listOf("driver door", "rocket hatch", "driver door", "bonnet (tilted)"))
        val c = bad.checked()
        assertNull(c.odometerKm)
        assertNull(c.fuelLiters)
        assertNull(c.rangeKm)
        assertNull(c.avgConsumptionKmPerL)
        assertNull(c.outsideC)
        assertNull(c.insideC)
        assertEquals(listOf("driver door", "bonnet (tilted)"), c.openParts)
        assertNull(CarValues(fuelLiters = 0.0).checked().fuelLiters)                 // zero = no reading
        assertNull(CarValues(odometerKm = Double.POSITIVE_INFINITY).checked().odometerKm)
    }

    @Test
    fun partLabels() {
        assertEquals(11, CarValues.PART_LABELS.size)
        assertTrue(CarValues.isPartLabel("rear passenger window (tilted)"))
        assertFalse(CarValues.isPartLabel("Driver door"))
    }

    @Test
    fun jsonRoundTrip() {
        assertEquals(full, CarValues.fromJson(CarValues.toJson(full)))
        assertEquals("{}", CarValues.toJson(CarValues.EMPTY))
        assertEquals(CarValues.EMPTY, CarValues.fromJson("{}"))
        assertEquals(CarValues(odometerKm = 5.0), CarValues.fromJson("""{"odometerKm":5,"fuelLiters":"x","future":1}"""))
        assertNull(CarValues.fromJson("[1]"))
        assertNull(CarValues.fromJson("nope"))
    }
}
