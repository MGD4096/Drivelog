package dev.drivelog.core

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.trip.LateReading
import dev.drivelog.core.trip.TrackPoint
import dev.drivelog.core.trip.Trip
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.core.trip.TripStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LateReadingTest {
    private val hour = 3_600_000L
    private val start = 1_700_000_000_000L
    private val end = start + hour

    /** A trip of about [km] kilometres to the north, with the odometer the plugin sent at the start. */
    private fun trip(km: Int, startOdometer: Double? = 9_533.0, id: String = "t1", car: String = "AA:BB") = Trip(
        id = id, carId = car, carName = "Bike", startMs = start, endMs = end,
        points = (0..km).map { TrackPoint(start + it * 60_000L, 52.0 + it / 111.2, 21.0, null, 20.0, 5.0) },
        samples = emptyList(),
        startCar = CarValues(odometerKm = startOdometer, fuelLiters = 12.0),
        endCar = CarValues(odometerKm = startOdometer, fuelLiters = 12.0),
    )

    @Test
    fun aReadingAfterTheTripBecomesItsEnd() {
        val late = CarValues(odometerKm = 9_667.0, fuelLiters = 6.9, rangeKm = 74.0, tyrePressuresBar = listOf(2.5, 2.9))
        val t = LateReading.endOf(trip(130), late, end + 10 * 60_000L)!!
        assertEquals(late, t.endCar.copy(outsideC = null))
        assertEquals(134.0, TripStats.summarize(t).odometerDistanceKm!!, 0.01)
        assertEquals(12.0, t.startCar.fuelLiters!!, 0.0)                     // the start stays as it was
    }

    @Test
    fun readingsThatDoNotFitTheTripAreRefused() {
        val t = trip(130)
        val ok = CarValues(odometerKm = 9_667.0)
        assertNull(LateReading.endOf(t, ok, start - 1))                         // from before the trip
        assertNull(LateReading.endOf(t, ok, end + LateReading.MAX_DELAY_MS + 1))
        assertNull(LateReading.endOf(t, CarValues(odometerKm = 9_500.0), end))  // lower than at the start
        assertNull(LateReading.endOf(t, CarValues(odometerKm = 9_900.0), end))  // rides without the phone in between
        assertNull(LateReading.endOf(t, CarValues(odometerKm = 9_533.0, fuelLiters = 12.0), end))   // nothing new
    }

    @Test
    fun withoutAStartOdometerOnlyTheTimeCounts() {
        val t = LateReading.endOf(trip(10, startOdometer = null), CarValues(odometerKm = 9_667.0, fuelLiters = 8.0), end)!!
        assertEquals(9_667.0, t.endCar.odometerKm!!, 0.0)
        assertEquals(8.0, t.endCar.fuelLiters!!, 0.0)
    }

    @Test
    fun theVehicleIsTheOneThatUsesThePluginAndRodeLast() {
        val ride = CarDetails(plugin = "ride")
        val vehicles = mapOf("AA:BB" to ride, "cc:dd" to ride, "ee:ff" to CarDetails(), "11:22" to ride.copy(archived = true))
        fun meta(car: String, endMs: Long) = trip(1, car = car).let { TripMeta(it.id, car, "x", it.startMs, endMs, TripStats.summarize(it)) }
        val trips = listOf(meta("aa:bb", end), meta("CC:DD", end + hour), meta("11:22", end + 2 * hour))
        assertEquals("cc:dd", LateReading.vehicleFor("ride", vehicles, trips))
        assertEquals("AA:BB", LateReading.vehicleFor("ride", vehicles - "cc:dd", trips))
        assertEquals("ee:ff", LateReading.vehicleFor("id6", vehicles, trips))
        assertNull(LateReading.vehicleFor("other", vehicles, trips))
    }
}
