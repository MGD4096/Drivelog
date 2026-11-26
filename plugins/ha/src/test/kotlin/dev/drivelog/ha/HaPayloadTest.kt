package dev.drivelog.ha

import dev.drivelog.carapi.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaPayloadTest {
    private val state = """{"format":1,"atMs":3000,"vehicles":[
        {"id":"a1b2c3d4e5f6","name":"BMW 228i","kind":"car","values":{"odometerKm":108323,"fuelLiters":25,"openParts":[]},
         "readAtMs":1000,"parked":{"lat":52.4,"lon":22.2,"atMs":2000},
         "services":[{"type":"inspection","label":"Technical inspection","dueDate":"2027-07-15","daysLeft":231,"level":"ok"}]},
        {"id":"0f0f0f0f0f0f","name":"S 1000 RR","kind":"motorcycle","future":"kept"}]}"""

    @Test
    fun onlyWhatThePersonAllowsIsSent() {
        val all = Json.parseObject(HaPayload.of(state, HaChoice())!!)!!
        val car = (all["vehicles"] as List<*>).first() as Map<*, *>
        assertTrue(car.keys.containsAll(listOf("values", "readAtMs", "parked", "services")))

        val noParking = HaPayload.of(state, HaChoice(parking = false))!!
        assertFalse("the location stays at home", noParking.contains("\"lat\""))
        val onlyServices = Json.parseObject(HaPayload.of(state, HaChoice(values = false, parking = false))!!)!!
        val c = (onlyServices["vehicles"] as List<*>).first() as Map<*, *>
        assertEquals(setOf("id", "name", "kind", "services"), c.keys)
        val bike = (onlyServices["vehicles"] as List<*>)[1] as Map<*, *>
        assertEquals("kept", bike["future"])                                     // newer keys go on as they are
        assertNull(HaPayload.of("not json", HaChoice()))
    }

    @Test
    fun theYamlHasASetOfSensorsPerVehicle() {
        val vehicles = HaPayload.vehicles(state)
        assertEquals(listOf("a1b2c3d4e5f6" to "BMW 228i", "0f0f0f0f0f0f" to "S 1000 RR"), vehicles)
        val yaml = HaYaml.of("drivelog_x1", vehicles, HaChoice())
        assertTrue(yaml.contains("webhook_id: drivelog_x1"))
        assertTrue(yaml.contains("unique_id: drivelog_a1b2c3d4e5f6_odometer"))
        assertTrue(yaml.contains("unique_id: drivelog_0f0f0f0f0f0f_parked"))
        assertTrue(yaml.contains("unique_id: drivelog_a1b2c3d4e5f6_oil_change"))
        assertEquals(2, Regex("unique_id: drivelog_\\w+_odometer").findAll(yaml).count())
        val noParking = HaYaml.of("drivelog_x1", vehicles, HaChoice(parking = false))
        assertFalse(noParking.contains("latitude"))
        assertTrue(HaYaml.of("drivelog_x1", emptyList(), HaChoice()).contains("No vehicle yet"))
    }
}
