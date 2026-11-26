package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarState
import dev.drivelog.core.car.DriveTotals
import dev.drivelog.core.trip.CounterFuel
import dev.drivelog.core.trip.FileTripStore
import dev.drivelog.core.trip.TrackPoint
import dev.drivelog.core.trip.Trip
import dev.drivelog.core.trip.TripJson
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.core.trip.TripStats
import dev.drivelog.core.trip.TripSummary
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTotalsTest {

    @Test
    fun topSpeedMovingTimeAndFuelAreAddedUpFromEveryDelivery() {
        var now = 1_000L
        val car = CarState { now }
        car.receive(CarValues(speedKmh = 0.0, fuelUsedTotalL = 10.0))
        now += 1_000; car.receive(CarValues(speedKmh = 50.0, fuelUsedTotalL = 10.01))
        now += 1_000; car.receive(CarValues(speedKmh = 120.0, fuelUsedTotalL = 10.03))   // moving for this second
        now += 1_000; car.receive(CarValues(speedKmh = 0.0, fuelUsedTotalL = 10.05))     // and this one
        now += 1_000; car.receive(CarValues(speedKmh = 0.0, fuelUsedTotalL = 0.002))     // plugin restarted: no step
        now += 1_000; car.receive(CarValues(speedKmh = 0.0, fuelUsedTotalL = 0.004))
        val t = car.totals()
        assertEquals(120.0, t.maxSpeedKmh!!, 1e-9)
        assertEquals(2_000L, t.movingMs)
        assertEquals(0.052, t.counterFuelL!!, 1e-9)
        car.reset()
        assertEquals(DriveTotals.NONE, car.totals())
    }

    @Test
    fun aLongGapIsNotCountedAsMoving() {
        var now = 1_000L
        val car = CarState { now }
        car.receive(CarValues(speedKmh = 80.0))
        now += 60_000; car.receive(CarValues(speedKmh = 80.0))
        assertEquals(0L, car.totals().movingMs)
        assertNull(car.totals().counterFuelL)
    }

    private fun trip(totals: DriveTotals, endCar: CarValues = CarValues.EMPTY) = Trip(
        id = "t", carId = "AA", carName = "car", startMs = 0, endMs = 600_000,
        points = listOf(TrackPoint(0, 52.0, 21.0, null, 10.0, 5.0), TrackPoint(300_000, 52.03, 21.0, null, 10.0, 5.0)),
        samples = emptyList(), startCar = CarValues.EMPTY, endCar = endCar, carTotals = totals,
    )

    /** The plugin delivers the car's speed only now and then: with a GPS track, GPS gives the speeds. */
    @Test
    fun gpsGivesTheSpeedsWheneverThereIsATrack() {
        val s = TripStats.summarize(trip(DriveTotals(maxSpeedKmh = 24.0, movingMs = 28_000, counterFuelL = 0.4), CarValues(avgSpeedKmh = 44.0)))
        assertFalse(s.speedFromCar)
        assertEquals(36.0, s.maxSpeedKmh!!, 1e-9)                       // the fixes' own 10 m/s, not the car's 24
        assertEquals(300_000L, s.movingTimeMs)                          // the 5 minutes between the fixes, not 28 s
        assertEquals(s.gpsDistanceKm / (300_000 / 3_600_000.0), s.avgMovingSpeedKmh!!, 1e-9)
        assertEquals(0.4, s.counterFuelL!!, 1e-9)                       // the fuel counter and the car's average stay
        assertEquals(44.0, s.carAvgSpeedKmh!!, 1e-9)
        assertNull(TripStats.summarize(trip(DriveTotals.NONE)).counterFuelL)
    }

    /** Without a track (no location permission, no fix) the car's speed is all there is. */
    @Test
    fun withoutATrackTheCarsSpeedIsUsedIfItsAverageCanBeTrue() {
        fun noTrack(totals: DriveTotals) = TripStats.summarize(trip(totals).copy(points = emptyList(), endCar = CarValues(odometerKm = 1010.0), startCar = CarValues(odometerKm = 1000.0)))
        val s = noTrack(DriveTotals(maxSpeedKmh = 95.0, movingMs = 600_000))
        assertTrue(s.speedFromCar)
        assertEquals(95.0, s.maxSpeedKmh!!, 1e-9)
        assertEquals(600_000L, s.movingTimeMs)
        assertEquals(60.0, s.avgMovingSpeedKmh!!, 1e-9)                 // 10 km by the odometer in 10 minutes
        assertNull("an average above the top speed is not shown", noTrack(DriveTotals(maxSpeedKmh = 24.0, movingMs = 28_000)).avgMovingSpeedKmh)
    }

    @Test
    fun aSavedSummaryFromAnOlderVersionIsWorkedOutAgainOnce() {
        val dir = Files.createTempDirectory("trips").toFile()
        try {
            val store = FileTripStore(dir)
            store.save(trip(DriveTotals(maxSpeedKmh = 24.0, movingMs = 28_000)))
            // the meta file as the old version wrote it: no "sv", the car's 28 s as the moving time
            val saved = store.list().single()
            val old = TripJson.metaToJson(saved.copy(summary = saved.summary.copy(movingTimeMs = 28_000))).replace(Regex("\"sv\":\\s*\\d+(\\.0)?,"), "")
            val file = File(dir, "t.meta.json")
            file.writeText(old)
            assertEquals(1, TripJson.summaryVersion(file.readText()))
            assertEquals(300_000L, store.list().single().summary.movingTimeMs)
            assertEquals(TripJson.SUMMARY_VERSION, TripJson.summaryVersion(file.readText()))   // saved: not again
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theCounterGivesConsumptionForShortTripsScaledByTheTankComparison() {
        val s = TripStats.summarize(trip(DriveTotals(maxSpeedKmh = 50.0, movingMs = 1_000, counterFuelL = 0.3)))
        assertNull(s.consumptionLPer100Km)                          // 3.3 km: too short for the tank level
        val (lPer100, fromCounter) = CounterFuel.consumption(s, 1.0)!!
        assertTrue(fromCounter)
        assertEquals(0.3 / s.distanceKm * 100, lPer100, 1e-9)
        assertEquals(0.6 / s.distanceKm * 100, CounterFuel.consumption(s, 2.0)!!.first, 1e-9)
        assertEquals(0.6, CounterFuel.used(s, 2.0)!!, 1e-9)
    }

    private fun meta(tank: Double?, counter: Double?) = TripMeta(
        "t", "AA", "car", 0, 1, TripSummary(
            durationMs = 1, distanceKm = 100.0, gpsDistanceKm = 100.0, odometerDistanceKm = null, movingTimeMs = 1,
            avgMovingSpeedKmh = null, maxSpeedKmh = null, startOdometerKm = null, endOdometerKm = null, startFuelL = null,
            endFuelL = null, fuelUsedL = tank, refueledL = 0.0, consumptionLPer100Km = tank?.let { it }, carAvgConsumptionKmPerL = null,
            startLat = null, startLon = null, endLat = null, endLon = null, counterFuelL = counter,
        ),
    )

    @Test
    fun theFactorNeedsEnoughTankLitresAndStaysWithinLimits() {
        assertEquals(1.0, CounterFuel.factor(listOf(meta(8.0, 4.0))), 1e-9)               // only 8 L to compare
        assertEquals(2.0, CounterFuel.factor(listOf(meta(12.0, 6.0), meta(10.0, 5.0), meta(9.0, null))), 1e-9)
        assertEquals(4.0, CounterFuel.factor(listOf(meta(30.0, 1.0))), 1e-9)
    }
}
