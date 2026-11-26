package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarState
import dev.drivelog.core.car.DriveTotals
import dev.drivelog.core.trip.CounterFuel
import dev.drivelog.core.trip.TrackPoint
import dev.drivelog.core.trip.Trip
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.core.trip.TripStats
import dev.drivelog.core.trip.TripSummary
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

    @Test
    fun theCarsSpeedWinsOverGpsWhenItIsThere() {
        val s = TripStats.summarize(trip(DriveTotals(maxSpeedKmh = 95.0, movingMs = 240_000, counterFuelL = 0.4), CarValues(avgSpeedKmh = 44.0)))
        assertTrue(s.speedFromCar)
        assertEquals(95.0, s.maxSpeedKmh!!, 1e-9)
        assertEquals(240_000L, s.movingTimeMs)
        assertEquals(s.distanceKm / (240_000 / 3_600_000.0), s.avgMovingSpeedKmh!!, 1e-9)
        assertEquals(0.4, s.counterFuelL!!, 1e-9)
        assertEquals(44.0, s.carAvgSpeedKmh!!, 1e-9)

        val gps = TripStats.summarize(trip(DriveTotals.NONE))
        assertFalse(gps.speedFromCar)
        assertEquals(36.0, gps.maxSpeedKmh!!, 1e-9)                // the fixes' own 10 m/s
        assertNull(gps.counterFuelL)
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
