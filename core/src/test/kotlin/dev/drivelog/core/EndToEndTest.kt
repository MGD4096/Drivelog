package dev.drivelog.core

import dev.drivelog.carapi.CarValues
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
 * A whole drive without a car: the car plugin delivers neutral car values (as its link does: always the whole
 * reading), GPS fixes arrive, the trip is recorded, saved, read back and exported. How a plugin gets the values from
 * its car is tested in the plugin (plugin-id6).
 */
class EndToEndTest {
    private lateinit var dir: File
    private val car = CarState()
    private var reading = CarValues.EMPTY

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("e2e").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // The plugin's next delivery: everything it knows, with one thing changed.
    private fun push(change: (CarValues) -> CarValues) {
        reading = change(reading)
        car.receive(reading)
    }

    @Test
    fun fifteenKilometreDriveWithOneLitreBurnt() {
        // 1. First values after the head unit starts
        push { it.copy(odometerKm = 12345.0) }
        push { it.copy(fuelLiters = 30.0, rangeKm = 420.0) }
        push { it.copy(avgConsumptionKmPerL = 14.2) }
        push { it.copy(outsideC = 6.5) }
        push { it.copy(openParts = emptyList()) }

        // 2. Drive 600 s at 25 m/s = 15 km, the car values changing on the way
        val recorder = TripRecorder()
        recorder.begin("AA:BB:CC:DD:EE:FF", "Mini", Geo.T0, car.snapshot())
        for (t in 1..600) {
            val fix = Geo.fix(t.toLong(), t * 25.0, speedMps = 25.0)
            recorder.onFix(TripRecorder.Fix(fix.timeMs, fix.lat, fix.lon, fix.altitudeM, fix.speedMps, fix.accuracyM))
            if (t % 24 == 0) push { it.copy(odometerKm = (12345 + t * 25 / 1000).toDouble()) }      // whole km
            if (t == 300) push { it.copy(fuelLiters = 29.0, rangeKm = 405.0) }
            if (t % 5 == 0) recorder.onCar(Geo.T0 + t * 1000L, car.snapshot())
        }
        push { it.copy(openParts = listOf("driver door")) }     // the driver opens the door
        val trip = recorder.finish(Geo.T0 + 600_000)!!

        // 3. Numbers
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

        // 4. Saved and read back
        val store = FileTripStore(dir)
        store.save(trip)
        assertEquals(trip, store.load(trip.id))
        val listed = store.list().single()
        assertEquals(s, listed.summary)
        assertEquals(s.endLat, listed.summary.endLat)       // "where I parked" comes from here

        // 5. Exported
        val gpx = Gpx.write(store.load(trip.id)!!)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(gpx.byteInputStream())
        assertNotNull(doc.documentElement)
        assertTrue(gpx.contains("<dl:fuel>30.0</dl:fuel>"))
        assertTrue(gpx.contains("<dl:fuel>29.0</dl:fuel>"))
        val csv = Csv.write(trip).trim().lines()
        assertEquals(trip.points.size + 1, csv.size)
    }
}
