package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.carapi.Json
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.Reminders
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.export.ExportState
import dev.drivelog.core.export.ExportVehicle
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportStateTest {
    @Test
    fun theStateCarriesValuesParkingAndServicesButNotTheAddress() {
        val today = LocalDate.of(2026, 11, 26)
        val dues = Reminders.upcoming(listOf(CarEvent("i", EventType.INSPECTION, LocalDate.of(2026, 7, 15), dueDate = LocalDate.of(2027, 7, 15))), today, 108_000.0)
        val json = ExportState.toJson(listOf(
            ExportVehicle("38:81:D7:93:66:8C", "BMW 228i", VehicleKind.CAR, CarValues(odometerKm = 108_323.0, fuelLiters = 25.0), 1_000L,
                52.4, 22.2, 2_000L, dues),
            ExportVehicle("BC:42:8C:CC:E3:91", "S 1000 RR", VehicleKind.MOTORCYCLE),
        ), 3_000L)
        assertFalse("the Bluetooth address stays in the app", json.contains("38:81", ignoreCase = true))
        val root = Json.parseObject(json)!!
        assertEquals(1.0, root["format"])
        val (car, bike) = (root["vehicles"] as List<*>).map { it as Map<*, *> }
        assertEquals(ExportState.id("38:81:d7:93:66:8c"), car["id"])                 // case does not matter
        assertEquals(12, (car["id"] as String).length)
        assertEquals("car", car["kind"])
        assertEquals(108_323.0, (car["values"] as Map<*, *>)["odometerKm"])
        assertEquals(52.4, (car["parked"] as Map<*, *>)["lat"])
        val service = (car["services"] as List<*>).single() as Map<*, *>
        assertEquals("inspection", service["type"])
        assertEquals("2027-07-15", service["dueDate"])
        assertEquals(231.0, service["daysLeft"])
        assertEquals("ok", service["level"])
        assertEquals("motorcycle", bike["kind"])
        assertTrue("nothing known: nothing written", bike.keys == setOf("id", "name", "kind"))
        assertNull(bike["values"])
    }
}
