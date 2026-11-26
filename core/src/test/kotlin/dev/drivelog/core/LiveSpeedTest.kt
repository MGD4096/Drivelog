package dev.drivelog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSpeedTest {
    @Test
    fun theCarsSpeedWinsWhileFreshThenGps() {
        assertEquals(52.0, LiveSpeed.pick(52.0, 100_000, 50.0, 100_000, 105_000)!!, 1e-9)
        assertEquals(50.0, LiveSpeed.pick(52.0, 80_000, 50.0, 100_000, 105_000)!!, 1e-9)     // the car's is old
        assertEquals(50.0, LiveSpeed.pick(null, 0, 50.0, 100_000, 105_000)!!, 1e-9)          // a car without a plugin
        assertNull(LiveSpeed.pick(52.0, 80_000, 50.0, 80_000, 105_000))                       // both old
        assertNull(LiveSpeed.pick(52.0, 0, null, 0, 5_000))                                   // nothing ever arrived
    }
}
