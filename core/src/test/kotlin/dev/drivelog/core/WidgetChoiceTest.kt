package dev.drivelog.core

import dev.drivelog.core.car.WidgetChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetChoiceTest {
    private val cars = listOf("AA:01", "BB:02", "CC:03")
    private val read = mapOf("aa:01" to 100L, "bb:02" to 300L)

    @Test
    fun theChosenCarWinsWhileItExists() {
        assertEquals("AA:01", WidgetChoice.pick("aa:01", cars, "CC:03", read))
        // gone (removed, archived, moved to another address): falls back
        assertEquals("CC:03", WidgetChoice.pick("DD:04", cars, "cc:03", read))
    }

    @Test
    fun withoutAChoiceTheLiveCarThenTheLatestReading() {
        assertEquals("CC:03", WidgetChoice.pick(null, cars, "cc:03", read))
        assertEquals("BB:02", WidgetChoice.pick(null, cars, null, read))
        assertEquals("BB:02", WidgetChoice.pick(null, cars, "XX:99", read))       // a trip of a car the widget cannot show
        assertEquals("AA:01", WidgetChoice.pick(null, cars, null, emptyMap()))    // nothing read yet: the first car
        assertNull(WidgetChoice.pick("AA:01", emptyList(), null, read))
    }
}
