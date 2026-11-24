package dev.idrivetrips.core

import dev.idrivetrips.carapi.CarValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarStateTest {
    @Test
    fun keepsTheLatestCheckedReading() {
        var now = 1000L
        val state = CarState { now }
        assertEquals(0L, state.lastUpdateMillis)
        assertEquals(CarValues.EMPTY, state.snapshot())

        state.receive(CarValues(odometerKm = 500.0, fuelLiters = 400.0, openParts = listOf("bonnet", "hatch")))
        assertEquals(1000L, state.lastUpdateMillis)
        assertEquals(500.0, state.snapshot().odometerKm!!, 0.0)
        assertNull("implausible: dropped", state.snapshot().fuelLiters)
        assertEquals(listOf("bonnet"), state.snapshot().openParts)

        now = 2000L
        state.receive(CarValues(odometerKm = 501.0))                // a whole new reading replaces the old one
        assertEquals(501.0, state.snapshot().odometerKm!!, 0.0)
        assertNull(state.snapshot().openParts)
        assertEquals(2000L, state.lastUpdateMillis)

        state.reset()
        assertEquals(0L, state.lastUpdateMillis)
        assertEquals(CarValues.EMPTY, state.snapshot())
    }

    @Test
    fun theAppKnowsEveryPartAPluginMayReport() {
        // CarPart (drawing, names, translations) and the contract's list must be the same parts
        assertEquals(CarValues.PART_LABELS.toSet(), CarPart.values().map { it.label }.toSet())
        assertEquals(CarValues.TILTED, " (tilted)")
    }
}
