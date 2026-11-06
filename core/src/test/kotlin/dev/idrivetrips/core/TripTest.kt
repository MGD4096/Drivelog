package dev.idrivetrips.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Helpers shared by the trip tests. */
object Geo {
    /** Metres per degree of latitude on the sphere used by [TripStats.haversineMeters]. */
    const val METERS_PER_DEGREE = 111_195.08

    const val T0 = 1_780_000_000_000L

    /** A point `northMeters` due north of a fixed origin in central Europe. */
    fun fix(tSec: Long, northMeters: Double, speedMps: Double? = 20.0, accuracy: Double? = 5.0, eastMeters: Double = 0.0) =
        TripRecorder.Fix(
            timeMs = T0 + tSec * 1000,
            lat = 52.0 + northMeters / METERS_PER_DEGREE,
            lon = 21.0 + eastMeters / (METERS_PER_DEGREE * Math.cos(Math.toRadians(52.0))),
            altitudeM = 100.0,
            speedMps = speedMps,
            accuracyM = accuracy,
        )

    fun car(odo: Double? = null, fuel: Double? = null, outside: Double? = null, avg: Double? = null) =
        CarSnapshot(odometerKm = odo, fuelLiters = fuel, outsideC = outside, avgConsumptionKmPerL = avg)

    /** A trip driven north at a constant speed, one fix per second. */
    fun drive(recorder: TripRecorder, seconds: Int, speed: Double = 20.0) {
        for (t in 0..seconds) recorder.onFix(fix(t.toLong(), t * speed, speed))
    }
}

class TripRecorderTest {

    @Test
    fun straightDriveMeasuresTheRightDistance() {
        val r = TripRecorder()
        r.begin("AA:BB", "Mini", Geo.T0, CarSnapshot.EMPTY)
        Geo.drive(r, 100)
        val trip = r.finish(Geo.T0 + 100_000)!!
        val s = TripStats.summarize(trip)
        // 1 Hz fixes are thinned to every 3 s, so the last stored point is at t = 99 s = 1980 m
        assertEquals(1.98, s.gpsDistanceKm, 0.01)
        assertEquals(1.98, s.distanceKm, 0.01)
        assertEquals(72.0, s.maxSpeedKmh!!, 0.01)
        assertEquals(72.0, s.avgMovingSpeedKmh!!, 1.0)
        assertEquals(100_000L, s.durationMs)
        assertEquals(52.0, s.startLat!!, 1e-6)
        assertTrue(s.endLat!! > s.startLat!!)
        assertFalse(r.isRecording)
    }

    @Test
    fun jitterWhileStandingStillAddsNoDistance() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        val jitter = listOf(0.0, 5.0, -4.0, 6.0, -6.0, 3.0)
        for (t in 0..120) r.onFix(Geo.fix(t.toLong(), jitter[t % jitter.size], speedMps = 0.0, eastMeters = jitter[(t + 2) % jitter.size]))
        val s = TripStats.summarize(r.finish(Geo.T0 + 120_000)!!)
        assertEquals(0.0, s.gpsDistanceKm, 1e-9)
        assertEquals(0L, s.movingTimeMs)
        assertFalse("a car that never moved is not worth keeping", TripStats.isWorthKeeping(s))
    }

    @Test
    fun stationaryFixesAreThinnedToOneEvery30Seconds() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        for (t in 0..120) r.onFix(Geo.fix(t.toLong(), 0.0, speedMps = 0.0))
        assertEquals(5, r.pointCount)      // t = 0, 30, 60, 90, 120
    }

    @Test
    fun aSingleGpsJumpIsIgnoredInTheDistance() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        for (t in 0..100) {
            val north = if (t == 30) t * 20.0 + 5_000.0 else t * 20.0
            r.onFix(Geo.fix(t.toLong(), north))
        }
        val s = TripStats.summarize(r.finish(Geo.T0 + 100_000)!!)
        assertTrue("distance was ${s.gpsDistanceKm}", s.gpsDistanceKm in 1.8..2.0)
    }

    @Test
    fun badFixesAreRejected() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        assertFalse("poor accuracy", r.onFix(Geo.fix(0, 0.0, accuracy = 80.0)))
        assertFalse("null island", r.onFix(TripRecorder.Fix(Geo.T0, 0.0, 0.0)))
        assertFalse("impossible latitude", r.onFix(TripRecorder.Fix(Geo.T0, 91.0, 10.0)))
        assertTrue(r.onFix(Geo.fix(1, 0.0)))
        assertFalse("same time", r.onFix(Geo.fix(1, 50.0)))
        assertFalse("too soon", r.onFix(Geo.fix(2, 40.0)))
        assertFalse("time going backwards", r.onFix(Geo.fix(0, 60.0)))
        assertEquals(1, r.pointCount)
    }

    @Test
    fun nothingIsRecordedBeforeBeginOrAfterFinish() {
        val r = TripRecorder()
        assertFalse(r.onFix(Geo.fix(0, 0.0)))
        assertNull(r.finish(Geo.T0))
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        assertNotNull(r.finish(Geo.T0 + 1000))
        assertFalse(r.onFix(Geo.fix(5, 0.0)))
        assertNull(r.finish(Geo.T0 + 2000))
    }

    @Test
    fun carSamplesAreStoredOnlyWhenSomethingChanges() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, Geo.car(odo = 1000.0, fuel = 40.0))
        r.onCar(Geo.T0 + 1000, Geo.car(odo = 1000.0, fuel = 40.0))
        r.onCar(Geo.T0 + 2000, Geo.car(odo = 1000.0, fuel = 40.0, avg = 9.0))      // avg consumption is not tracked per sample
        r.onCar(Geo.T0 + 3000, Geo.car(odo = 1001.0, fuel = 40.0))
        val trip = r.finish(Geo.T0 + 4000)!!
        assertEquals(listOf(1000.0, 1001.0), trip.samples.map { it.odometerKm })
        assertEquals("the end snapshot is the last one seen", 1001.0, trip.endCar.odometerKm!!, 0.0)
    }

    @Test
    fun emptyCarSnapshotsCreateNoSamples() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        r.onCar(Geo.T0 + 1000, CarSnapshot.EMPTY)
        assertEquals(0, r.finish(Geo.T0 + 2000)!!.samples.size)
    }

    @Test
    fun snapshotDoesNotStopTheRecording() {
        val r = TripRecorder()
        r.begin("c", "Mini", Geo.T0, CarSnapshot.EMPTY)
        Geo.drive(r, 30)
        val draft = r.snapshotTrip(Geo.T0 + 30_000)
        assertTrue(r.isRecording)
        assertTrue(draft.points.isNotEmpty())
        Geo.drive(r, 31)       // continue; time-ordering keeps the earlier points
        assertTrue(r.snapshotTrip(Geo.T0 + 40_000).points.size >= draft.points.size)
    }

    @Test
    fun idIsFileSafeAndStable() {
        val id = TripIds.make(Geo.T0, "AA:BB:CC:DD:EE:FF")
        assertEquals(id, TripIds.make(Geo.T0, "AA:BB:CC:DD:EE:FF"))
        assertTrue(id, Regex("[0-9]{8}T[0-9]{6}Z-AABBCCDDEEFF").matches(id))
        assertTrue(Regex("[0-9]{8}T[0-9]{6}Z").matches(TripIds.make(Geo.T0, "")))
    }
}

class TripStatsTest {

    private fun trip(
        start: CarSnapshot = CarSnapshot.EMPTY,
        end: CarSnapshot = CarSnapshot.EMPTY,
        samples: List<CarSample> = emptyList(),
        points: List<TrackPoint> = emptyList(),
    ) = Trip("id", "car", "Mini", Geo.T0, Geo.T0 + 3_600_000, points, samples, start, end)

    private fun line(km: Double): List<TrackPoint> {
        // a track of 1 km steps due north, 40 s apart (90 km/h)
        val steps = km.toInt()
        return (0..steps).map { i ->
            TrackPoint(Geo.T0 + i * 40_000L, 52.0 + i * 1000.0 / Geo.METERS_PER_DEGREE, 21.0, null, null, 5.0)
        }
    }

    @Test
    fun haversineKnownDistance() {
        // one degree of latitude
        assertEquals(Geo.METERS_PER_DEGREE, TripStats.haversineMeters(52.0, 21.0, 53.0, 21.0), 1.0)
        assertEquals(0.0, TripStats.haversineMeters(52.0, 21.0, 52.0, 21.0), 0.0)
    }

    @Test
    fun segmentSpeedGivesMaxSpeedWhenTheGpsReportsNone() {
        val s = TripStats.summarize(trip(points = line(5.0)))
        assertEquals(5.0, s.gpsDistanceKm, 0.01)
        assertEquals(90.0, s.maxSpeedKmh!!, 0.5)
    }

    @Test
    fun odometerIsUsedWhenThereIsNoGps() {
        val s = TripStats.summarize(trip(start = Geo.car(odo = 1000.0), end = Geo.car(odo = 1012.0)))
        assertEquals(12.0, s.distanceKm, 0.0)
        assertEquals(12.0, s.odometerDistanceKm!!, 0.0)
        assertEquals(0.0, s.gpsDistanceKm, 0.0)
        assertTrue(TripStats.isWorthKeeping(s))
    }

    @Test
    fun odometerWinsWhenGpsClearlyLostPartOfTheTrip() {
        val s = TripStats.summarize(trip(points = line(6.0), start = Geo.car(odo = 1000.0), end = Geo.car(odo = 1012.0)))
        assertEquals(12.0, s.distanceKm, 0.0)
        assertEquals(6.0, s.gpsDistanceKm, 0.01)
    }

    @Test
    fun gpsWinsWhenItAgreesWithTheOdometer() {
        val s = TripStats.summarize(trip(points = line(12.0), start = Geo.car(odo = 1000.0), end = Geo.car(odo = 1012.0)))
        assertEquals(12.0, s.distanceKm, 0.01)
        assertEquals(12.0, s.gpsDistanceKm, 0.01)
    }

    @Test
    fun aShortOdometerTripDoesNotOverrideGoodGps() {
        // odometer counts whole km: 3 km of GPS vs 4 km on the odometer must stay at GPS
        val s = TripStats.summarize(trip(points = line(3.0), start = Geo.car(odo = 1000.0), end = Geo.car(odo = 1004.0)))
        assertEquals(3.0, s.distanceKm, 0.01)
    }

    @Test
    fun odometerGoingBackwardsIsIgnored() {
        val s = TripStats.summarize(trip(start = Geo.car(odo = 1012.0), end = Geo.car(odo = 1000.0)))
        assertNull(s.odometerDistanceKm)
        assertEquals(0.0, s.distanceKm, 0.0)
    }

    @Test
    fun fuelUsedFromTheTankLevelDrop() {
        val s = TripStats.summarize(trip(points = line(100.0), start = Geo.car(fuel = 40.0), end = Geo.car(fuel = 34.0)))
        assertEquals(6.0, s.fuelUsedL!!, 1e-9)
        assertEquals(0.0, s.refueledL, 0.0)
        assertEquals(6.0, s.consumptionLPer100Km!!, 0.05)
    }

    @Test
    fun aRefuellingInTheMiddleIsAddedBack() {
        val samples = listOf(
            CarSample(Geo.T0 + 1000, null, 38.0, null, null),
            CarSample(Geo.T0 + 2000, null, 36.0, null, null),
            CarSample(Geo.T0 + 3000, null, 56.0, null, null),      // +20 L at a station
            CarSample(Geo.T0 + 4000, null, 54.0, null, null),
        )
        val s = TripStats.summarize(trip(points = line(100.0), start = Geo.car(fuel = 40.0), end = Geo.car(fuel = 54.0), samples = samples))
        assertEquals(20.0, s.refueledL, 1e-9)
        assertEquals(6.0, s.fuelUsedL!!, 1e-9)       // 40 - 54 + 20
        assertEquals(40.0, s.startFuelL!!, 0.0)
        assertEquals(54.0, s.endFuelL!!, 0.0)
    }

    @Test
    fun fuelSloshingIsNotARefuelling() {
        val samples = listOf(40.0, 41.0, 40.0, 39.0).mapIndexed { i, f -> CarSample(Geo.T0 + i * 1000L, null, f, null, null) }
        val s = TripStats.summarize(trip(points = line(20.0), start = Geo.car(fuel = 40.0), end = Geo.car(fuel = 39.0), samples = samples))
        assertEquals(0.0, s.refueledL, 0.0)
        assertEquals(1.0, s.fuelUsedL!!, 1e-9)
    }

    @Test
    fun fuelUsedIsNeverNegative() {
        val s = TripStats.summarize(trip(start = Geo.car(fuel = 30.0), end = Geo.car(fuel = 33.0)))
        assertEquals(0.0, s.fuelUsedL!!, 0.0)
    }

    @Test
    fun noConsumptionFigureForTripsTooShortToMeasure() {
        val short = TripStats.summarize(trip(points = line(3.0), start = Geo.car(fuel = 40.0), end = Geo.car(fuel = 39.0)))
        assertNull("3 km", short.consumptionLPer100Km)
        val tiny = TripStats.summarize(trip(points = line(50.0), start = Geo.car(fuel = 40.0), end = Geo.car(fuel = 40.0)))
        assertEquals(0.0, tiny.fuelUsedL!!, 0.0)
        assertNull("less than 1 L used", tiny.consumptionLPer100Km)
    }

    @Test
    fun noFuelDataMeansNoFuelFigures() {
        val s = TripStats.summarize(trip(points = line(10.0)))
        assertNull(s.fuelUsedL)
        assertNull(s.consumptionLPer100Km)
        assertNull(s.startFuelL)
    }

    @Test
    fun theCarsOwnAverageIsKept() {
        val s = TripStats.summarize(trip(end = Geo.car(avg = 14.2)))
        assertEquals(14.2, s.carAvgConsumptionKmPerL!!, 0.0)
    }

    @Test
    fun emptyTripSummarizesWithoutErrors() {
        val s = TripStats.summarize(trip())
        assertEquals(0.0, s.distanceKm, 0.0)
        assertNull(s.maxSpeedKmh)
        assertNull(s.avgMovingSpeedKmh)
        assertNull(s.startLat)
        assertFalse(TripStats.isWorthKeeping(s))
    }
}
