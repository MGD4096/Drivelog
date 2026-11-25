package dev.drivelog.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarHealthTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("health").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun trip(id: String, carId: String, startMs: Long, start: Double?, end: Double?) = Trip(
        id = id, carId = carId, carName = "c", startMs = startMs, endMs = startMs + 1000,
        points = emptyList(), samples = emptyList(),
        startCar = CarSnapshot(batteryPercent = start), endCar = CarSnapshot(batteryPercent = end, tyrePressureWarning = true),
    )

    @Test
    fun batteryHistoryOldestFirstFromTheStartOfEachTrip() {
        val store = FileTripStore(dir)
        store.save(trip("a", "AA", 1_000, 97.0, 96.0))
        store.save(trip("b", "AA", 2_000, null, 95.0))         // no start reading: the end one
        store.save(trip("c", "AA", 3_000, null, null))         // nothing: skipped
        store.save(trip("d", "BB", 4_000, 50.0, 50.0))         // another car
        store.save(trip("e", "AA", 5_000, 74.0, 80.0))
        val h = CarHealth.batteryHistory("aa", store.list(), store)
        assertEquals(listOf(97.0, 95.0, 74.0), h.map { it.percent })
        assertEquals(listOf(1_000L, 2_000L, 5_000L), h.map { it.atMs })
        assertEquals("97 → 95 → 74 %", CarHealth.trendText(h))
        assertTrue(CarHealth.batteryJustGotLow(h))
        assertEquals(listOf(95.0, 74.0), CarHealth.batteryHistory("AA", store.list(), store, max = 2).map { it.percent })
        // stored and read back with the trip
        assertEquals(true, store.load("e")!!.endCar.tyrePressureWarning)
    }

    @Test
    fun batteryHistoryNeedsOnlyALoaderNotTheFiles() {
        val store = FileTripStore(dir)
        val trips = listOf(trip("a", "AA", 1_000, 90.0, 89.0), trip("b", "AA", 2_000, 60.0, 61.0))
        trips.forEach { store.save(it) }
        val inMemory = trips.associateBy { it.id }
        val loaded = ArrayList<String>()
        val loader = TripLoader { id -> loaded += id; inMemory[id] }
        assertEquals(listOf(90.0, 60.0), CarHealth.batteryHistory("AA", store.list(), loader).map { it.percent })
        assertEquals(listOf("b", "a"), loaded)                           // newest first, only what it needs
    }

    @Test
    fun theFirstBatteryReadingOfATripIsItsStartReading() {
        val r = TripRecorder()
        r.begin("AA", "c", 0, CarSnapshot.EMPTY)                    // as TripService does: before any plugin value
        r.onCar(1_000, CarSnapshot(odometerKm = 10.0))
        r.onCar(2_000, CarSnapshot(odometerKm = 10.0, batteryPercent = 88.0))
        r.onCar(3_000, CarSnapshot(odometerKm = 11.0, batteryPercent = 92.0))
        val trip = r.finish(600_000)!!
        assertEquals(88.0, trip.startCar.batteryPercent!!, 0.0)
        assertEquals(92.0, trip.endCar.batteryPercent!!, 0.0)
        assertNull(trip.startCar.odometerKm)
    }

    @Test
    fun warnsOnceWhenTheBatteryGetsLow() {
        fun h(vararg p: Double) = p.mapIndexed { i, v -> CarHealth.BatteryReading(i.toLong(), v) }
        assertFalse(CarHealth.batteryJustGotLow(h()))
        assertFalse(CarHealth.batteryJustGotLow(h(90.0, 86.0)))
        assertTrue(CarHealth.batteryJustGotLow(h(70.0)))
        assertTrue(CarHealth.batteryJustGotLow(h(90.0, 74.9)))
        assertFalse("already low last time", CarHealth.batteryJustGotLow(h(74.0, 70.0)))
        assertNull(CarHealth.trendText(h(90.0)))
        assertFalse(CarHealth.isLow(null))
    }
}
