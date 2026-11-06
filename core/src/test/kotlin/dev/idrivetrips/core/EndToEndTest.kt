package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A whole drive without a car: the "plugin" pushes car values over a real socket, GPS fixes arrive, the trip is
 * recorded, saved, read back and exported.
 */
class EndToEndTest {
    private lateinit var dir: File
    private val car = CarState()
    private lateinit var server: BridgeHttpServer

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("e2e").toFile()
        server = BridgeHttpServer(0, BridgeProtocol(car, "1.0", 1_780_000_000L))
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun push(property: String, json: String) =
        assertEquals("pushing $property", 200, PluginClient.push(server.boundPort, property, json))

    @Test
    fun fifteenKilometreDriveWithOneLitreBurnt() {
        val port = server.boundPort

        // 1. The plugin finds the phone and checks the handshake
        val hello = PluginClient.get(port, "/ponte")
        assertTrue(PluginClient.body(hello).startsWith("IDRIVEPONTE"))

        // 2. First values after the head unit starts (formats as the plugin sends them)
        push("driving.odometer", """{"odometer":12345}""")
        push("sensors.fuel", """{"fuel":{"tanklevel":30,"range":420,"reserve":0}}""")
        push("driving.averageConsumption", """{"averageConsumption":{"averageConsumption1":13.8,"averageConsumption2":14.2,"unit":4}}""")
        push("sensors.temperatureExterior", """{"temperatureExterior":6.5}""")
        push("sensors.doors", """{"doors":{"driver":0,"passenger":0,"driverRear":0,"passengerRear":0}}""")
        push("controls.windowDriverFront", """{"windowDriverFront":{"position":0,"status":0,"drive":1}}""")

        // 3. Drive 600 s at 25 m/s = 15 km, the car values changing on the way
        val recorder = TripRecorder()
        recorder.begin("AA:BB:CC:DD:EE:FF", "Mini", Geo.T0, car.snapshot())
        for (t in 1..600) {
            val fix = Geo.fix(t.toLong(), t * 25.0, speedMps = 25.0)
            recorder.onFix(TripRecorder.Fix(fix.timeMs, fix.lat, fix.lon, fix.altitudeM, fix.speedMps, fix.accuracyM))
            if (t % 24 == 0) push("driving.odometer", """{"odometer":${12345 + t * 25 / 1000}}""")      // whole km
            if (t == 300) push("sensors.fuel", """{"fuel":{"tanklevel":29,"range":405,"reserve":0}}""")
            if (t % 5 == 0) recorder.onCar(Geo.T0 + t * 1000L, car.snapshot())
        }
        push("sensors.doors", """{"doors":{"driver":1,"passenger":0,"driverRear":0,"passengerRear":0}}""")     // the driver opens the door
        val trip = recorder.finish(Geo.T0 + 600_000)!!

        // 4. Numbers
        val s = TripStats.summarize(trip)
        assertEquals(15.0, s.gpsDistanceKm, 0.1)
        assertEquals(15.0, s.distanceKm, 0.1)
        assertEquals(15.0, s.odometerDistanceKm!!, 1.0)
        assertEquals(1.0, s.fuelUsedL!!, 1e-9)
        assertEquals(6.67, s.consumptionLPer100Km!!, 0.2)
        assertEquals(90.0, s.maxSpeedKmh!!, 0.01)
        assertEquals(600_000L, s.durationMs)
        assertEquals(14.2, s.carAvgConsumptionKmPerL!!, 1e-9)
        assertEquals(30.0, s.startFuelL!!, 0.0)
        assertEquals(29.0, s.endFuelL!!, 0.0)
        assertEquals(6.5, trip.samples.first().outsideC!!, 0.0)
        assertEquals(listOf("driver door"), car.snapshot().openParts)

        // 5. Saved and read back
        val store = FileTripStore(dir)
        store.save(trip)
        assertEquals(trip, store.load(trip.id))
        val listed = store.list().single()
        assertEquals(s, listed.summary)
        assertEquals(s.endLat, listed.summary.endLat)       // "where I parked" comes from here

        // 6. Exported
        val gpx = Gpx.write(store.load(trip.id)!!)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(gpx.byteInputStream())
        assertNotNull(doc.documentElement)
        assertTrue(gpx.contains("<idt:fuel>30.0</idt:fuel>"))
        assertTrue(gpx.contains("<idt:fuel>29.0</idt:fuel>"))
        val csv = Csv.write(trip).trim().lines()
        assertEquals(trip.points.size + 1, csv.size)
    }
}
