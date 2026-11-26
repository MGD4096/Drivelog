package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.FileLastReadingStore
import dev.drivelog.core.car.LastKnownCar
import dev.drivelog.core.car.LastReading
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.core.trip.TripSummary
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LastReadingTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("readings").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val full = CarValues(
        odometerKm = 108_218.0, fuelLiters = 34.0, rangeKm = 437.0, avgConsumptionKmPerL = 12.5, outsideC = 4.5,
        insideC = 10.0, openParts = listOf("driver window"), batteryPercent = 98.0, tyrePressureWarning = false,
    )

    @Test
    fun keepsEverythingAcrossAReload() {
        val store = FileLastReadingStore(File(dir, "sub/car_readings.json"))
        assertNull(store.get("AA:01"))
        store.put("AA:01", full, 1_000)
        assertEquals(LastReading(full, 1_000), FileLastReadingStore(File(dir, "sub/car_readings.json")).get("aa:01"))
    }

    @Test
    fun aNewReadingKeepsTheValuesItLacks() {
        val store = FileLastReadingStore(File(dir, "r.json"))
        store.put("AA:01", full, 1_000)
        store.put("AA:01", CarValues(odometerKm = 108_230.0, fuelLiters = 33.0, openParts = emptyList()), 2_000)
        val r = store.get("AA:01")!!
        assertEquals(2_000L, r.atMs)
        assertEquals(108_230.0, r.values.odometerKm!!, 1e-9)
        assertEquals(33.0, r.values.fuelLiters!!, 1e-9)
        assertEquals(98.0, r.values.batteryPercent!!, 1e-9)       // not in the new reading: kept
        assertEquals(false, r.values.tyrePressureWarning)
        assertEquals(emptyList<String>(), r.values.openParts)       // all closed now is a value, not a gap
    }

    @Test
    fun anEmptyReadingChangesNothingAndCarsStayApart() {
        val store = FileLastReadingStore(File(dir, "r.json"))
        store.put("AA:01", full, 1_000)
        store.put("AA:01", CarValues.EMPTY, 5_000)
        store.put("BB:02", CarValues(odometerKm = 7_000.0), 3_000)
        assertEquals(1_000L, store.get("AA:01")!!.atMs)
        assertEquals(7_000.0, store.get("bb:02")!!.values.odometerKm!!, 1e-9)
        assertNull(store.get("BB:02")!!.values.fuelLiters)
    }

    @Test
    fun implausibleValuesAreDropped() {
        val store = FileLastReadingStore(File(dir, "r.json"))
        store.put("AA:01", CarValues(odometerKm = 50_000.0, batteryPercent = 255.0), 1_000)
        assertNull(store.get("AA:01")!!.values.batteryPercent)
    }

    @Test
    fun movesAndRemoves() {
        val store = FileLastReadingStore(File(dir, "r.json"))
        store.put("AA:01", full, 1_000)
        store.moveCar("aa:01", "CC:03")
        assertNull(store.get("AA:01"))
        assertEquals(full, store.get("CC:03")!!.values)
        store.removeCar("cc:03")
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun aBrokenFileReadsAsEmptyAndLeavesNoTempFile() {
        val f = File(dir, "r.json")
        f.writeText("{not json")
        val store = FileLastReadingStore(f)
        assertTrue(store.all().isEmpty())
        store.put("AA:01", full, 1_000)
        assertEquals(full, store.get("AA:01")!!.values)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun theSavedReadingWinsOverTheTripsWhenNewer() {
        val fromTrips = LastKnownCar(CarValues(fuelLiters = 40.0), 1_000, listOf("sunroof"), 1_000)
        val reading = LastReading(CarValues(fuelLiters = 30.0, batteryPercent = 97.0), 2_000)
        val k = LastKnownCar.withReading(fromTrips, reading)!!
        assertTrue(k.fromReading)
        assertEquals(30.0, k.snapshot.fuelLiters!!, 1e-9)
        assertEquals(2_000L, k.atMs)
        assertEquals(listOf("sunroof"), k.openParts)                 // the reading has no doors: the trip's stay
        assertEquals(1_000L, k.openPartsAtMs)

        val older = LastReading(CarValues(fuelLiters = 30.0), 500)
        assertEquals(fromTrips, LastKnownCar.withReading(fromTrips, older))
        assertEquals(fromTrips, LastKnownCar.withReading(fromTrips, null))
        assertEquals(30.0, LastKnownCar.withReading(null, older)!!.snapshot.fuelLiters!!, 1e-9)
        assertNull(LastKnownCar.withReading(null, null))
    }

    @Test
    fun theOdometerComesFromTheReadingWhenNewerThanTheTrips() {
        val trip = TripMeta(
            id = "t1", carId = "AA:01", carName = "car", startMs = 1_000, endMs = 2_000,
            summary = TripSummary(
                durationMs = 1_000, distanceKm = 10.0, gpsDistanceKm = 10.0, odometerDistanceKm = null, movingTimeMs = 0,
                avgMovingSpeedKmh = null, maxSpeedKmh = null, startOdometerKm = 49_990.0, endOdometerKm = 50_000.0,
                startFuelL = null, endFuelL = null, fuelUsedL = null, refueledL = 0.0, consumptionLPer100Km = null,
                carAvgConsumptionKmPerL = null, startLat = null, startLon = null, endLat = null, endLon = null,
            ),
        )
        val newer = VehicleSummary.of("AA:01", listOf(trip), reading = LastReading(CarValues(odometerKm = 50_004.0), 9_000))
        assertEquals(50_004.0, newer.odometerKm!!, 1e-9)
        assertEquals(9_000L, newer.odometerAtMs)
        assertTrue(newer.odometerFromReading)
        val older = VehicleSummary.of("AA:01", listOf(trip), reading = LastReading(CarValues(odometerKm = 49_000.0), 1_500))
        assertEquals(50_000.0, older.odometerKm!!, 1e-9)
        assertFalse(older.odometerFromReading)
    }
}
