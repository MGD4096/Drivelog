package dev.drivelog.ride

import dev.drivelog.carapi.CarValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The fixtures are `uiautomator dump`s of the vehicle app's two screens on a real phone (Polish), with the app's
 * package name and the weather card's town replaced.
 */
class RideScreenTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3_600_000L

    private fun screen(name: String): ScreenNode {
        val doc = javaClass.classLoader!!.getResourceAsStream(name)!!.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        fun node(e: Element): ScreenNode {
            val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().map { node(it) }
            return ScreenNode(ScreenNode.shortId(e.getAttribute("resource-id")), e.getAttribute("text").ifEmpty { null }, kids)
        }
        return node(doc.documentElement)
    }

    @Test
    fun mainScreenGivesFuelAndRange() {
        val r = RideScreen.read(screen("main.xml"), now, tankLiters = 16.5)!!
        assertEquals(42.0 / 100 * 16.5, r.values.fuelLiters!!, 1e-9)
        assertEquals(74.0, r.values.rangeKm!!, 0.0)
        assertNull(r.values.odometerKm)
        assertEquals(now - 14 * day, r.atMs)                                        // "2 tygodnie temu"
    }

    @Test
    fun withoutTheTankSizeTheFuelIsUnknown() {
        val r = RideScreen.read(screen("main.xml"), now, tankLiters = null)!!
        assertNull(r.values.fuelLiters)
        assertEquals(74.0, r.values.rangeKm!!, 0.0)
    }

    @Test
    fun whichScreen() {
        assertFalse(RideScreen.showsDetails(screen("main.xml")))
        assertTrue(RideScreen.showsDetails(screen("details.xml")))
        assertTrue(screen("main.xml").find(RideScreen.VEHICLE_IMAGE_ID) != null)
    }

    @Test
    fun detailsGiveTheOdometerButNoTyresWhileTheyShowDashes() {
        val r = RideScreen.read(screen("details.xml"), now, tankLiters = 16.5)!!
        assertEquals(9_667.0, r.values.odometerKm!!, 0.0)
        assertNull(r.values.tyrePressuresBar)
    }

    @Test
    fun tyres() {
        fun tile(id: String, text: String) = ScreenNode(id, null, listOf(ScreenNode("value_content_row1", text)))
        val root = ScreenNode(null, null, listOf(
            tile("total_mileage_tile", "9 667 km"),
            tile("current_tire_pressure_front_tile", "2,5 bar"),
            tile("current_tire_pressure_rear_tile", "2.9 bar"),
        ))
        assertEquals(listOf(2.5, 2.9), RideScreen.read(root, now, null)!!.values.tyrePressuresBar)
        assertEquals(36 * 0.0689476, RideScreen.pressureBar("36 psi")!!, 1e-9)
        assertEquals(2.5, RideScreen.pressureBar("250 kPa")!!, 1e-9)
        assertNull(RideScreen.pressureBar("--"))
    }

    @Test
    fun distances() {
        assertEquals(9_667.0, RideScreen.distanceKm("9 667 km")!!, 0.0)
        assertEquals(9_667.0, RideScreen.distanceKm("9,667 km")!!, 0.0)
        assertEquals(1_609.344, RideScreen.distanceKm("1,000 mi")!!, 1e-9)
        assertNull(RideScreen.distanceKm("--"))
    }

    @Test
    fun otherScreensGiveNothing() {
        assertNull(RideScreen.read(ScreenNode(null, null, listOf(ScreenNode("card_name", "POGODA"))), now, 16.5))
    }

    @Test
    fun updatedAgo() {
        val min = 60_000L
        assertEquals(now, UpdatedAgo.parse("Zaktualizowano: przed chwilą", now))
        assertEquals(now - 5 * min, UpdatedAgo.parse("Zaktualizowano: 5 min temu", now))
        assertEquals(now - 60 * min, UpdatedAgo.parse("Zaktualizowano: godzinę temu", now))
        assertEquals(now - 3 * 60 * min, UpdatedAgo.parse("Zaktualizowano: 3 godz. temu", now))
        assertEquals(now - day, UpdatedAgo.parse("Zaktualizowano: wczoraj", now))
        assertEquals(now - 2 * day, UpdatedAgo.parse("Zaktualizowano: 2 dni temu", now))
        assertEquals(now - 60 * day, UpdatedAgo.parse("Zaktualizowano: 2 miesiące temu", now))
        assertEquals(now - 2 * 60 * min, UpdatedAgo.parse("Updated: 2 hours ago", now))
        assertEquals(now - 7 * day, UpdatedAgo.parse("Updated: a week ago", now))
        assertNull(UpdatedAgo.parse("Połączono", now))
    }
}

class RideReadingsTest {
    @Test
    fun bothScreensAddUp() {
        val main = RideReading(CarValues(fuelLiters = 6.9, rangeKm = 74.0), 1_000L)
        val details = RideReading(CarValues(odometerKm = 9_667.0), 1_000L)
        val both = RideReadings.merge(main, details)
        assertEquals(CarValues(odometerKm = 9_667.0, fuelLiters = 6.9, rangeKm = 74.0), both.values)
        assertTrue(RideReadings.isNew(main, details))
        assertFalse(RideReadings.isNew(both, details))                 // seen again: nothing to send
        assertFalse(RideReadings.isNew(both, main.copy(atMs = 5_000L))) // only the time text moved on
        assertTrue(RideReadings.isNew(both, RideReading(CarValues(odometerKm = 9_801.0), 9_000L)))
    }
}
