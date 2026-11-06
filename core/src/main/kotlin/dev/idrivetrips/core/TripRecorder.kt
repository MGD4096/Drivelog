package dev.idrivetrips.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns a stream of GPS fixes and car snapshots into a [Trip]. Not thread-safe: call it from one thread
 * (the service's main thread).
 */
class TripRecorder(
    private val minPointIntervalMs: Long = 3_000,
    private val minPointDistanceM: Double = 5.0,
    private val maxIdleIntervalMs: Long = 30_000,
    private val maxAccuracyM: Double = 50.0,
    private val stationarySpeedMps: Double = 0.5,
) {
    class Fix(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val altitudeM: Double? = null,
        val speedMps: Double? = null,
        val accuracyM: Double? = null,
    )

    private var carId = ""
    private var carName = ""
    private var startMs = 0L
    private var startCar = CarSnapshot.EMPTY
    private var lastCar = CarSnapshot.EMPTY
    private var recording = false
    private val points = ArrayList<TrackPoint>()
    private val samples = ArrayList<CarSample>()

    val isRecording: Boolean get() = recording
    val carIdInProgress: String get() = carId
    val pointCount: Int get() = points.size

    fun begin(carId: String, carName: String, nowMs: Long, car: CarSnapshot) {
        points.clear()
        samples.clear()
        this.carId = carId
        this.carName = carName
        startMs = nowMs
        startCar = car
        lastCar = car
        recording = true
        recordSample(nowMs, car)
    }

    /** Returns true when the fix was added to the track. */
    fun onFix(fix: Fix): Boolean {
        if (!recording) return false
        if (fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0 || (fix.lat == 0.0 && fix.lon == 0.0)) return false
        if (fix.accuracyM != null && fix.accuracyM > maxAccuracyM) return false
        val last = points.lastOrNull()
        val accept = if (last == null) {
            true
        } else {
            val dt = fix.timeMs - last.timeMs
            when {
                dt <= 0 || dt < minPointIntervalMs -> false
                fix.speedMps != null && fix.speedMps < stationarySpeedMps -> dt >= maxIdleIntervalMs
                else -> dt >= maxIdleIntervalMs ||
                    TripStats.haversineMeters(last.lat, last.lon, fix.lat, fix.lon) >= minPointDistanceM
            }
        }
        if (accept) points.add(TrackPoint(fix.timeMs, fix.lat, fix.lon, fix.altitudeM, fix.speedMps, fix.accuracyM))
        return accept
    }

    /** Feed the latest car snapshot now and then; a sample is stored only when a tracked value changed. */
    fun onCar(nowMs: Long, car: CarSnapshot) {
        if (!recording) return
        lastCar = car
        recordSample(nowMs, car)
    }

    private fun recordSample(nowMs: Long, car: CarSnapshot) {
        val sample = CarSample(nowMs, car.odometerKm, car.fuelLiters, car.outsideC, car.insideC)
        val last = samples.lastOrNull()
        val allEmpty = sample.odometerKm == null && sample.fuelLiters == null && sample.outsideC == null && sample.insideC == null
        if (allEmpty) return
        if (last == null || last.copy(timeMs = 0) != sample.copy(timeMs = 0)) samples.add(sample)
    }

    /** The trip as it stands now, for the live display and for crash-safe drafts. */
    fun snapshotTrip(nowMs: Long): Trip = Trip(
        id = TripIds.make(startMs, carId),
        carId = carId,
        carName = carName,
        startMs = startMs,
        endMs = maxOf(nowMs, startMs),
        points = points.toList(),
        samples = samples.toList(),
        startCar = startCar,
        endCar = lastCar,
    )

    fun liveSummary(nowMs: Long): TripSummary = TripStats.summarize(snapshotTrip(nowMs))

    /** Ends the trip and returns it, or null when nothing was being recorded. */
    fun finish(nowMs: Long): Trip? {
        if (!recording) return null
        val trip = snapshotTrip(nowMs)
        recording = false
        points.clear()
        samples.clear()
        return trip
    }

    fun cancel() {
        recording = false
        points.clear()
        samples.clear()
    }
}

object TripIds {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** A file-name-safe id such as `20261104T201203Z-AABBCCDDEEFF`. */
    fun make(startMs: Long, carId: String): String {
        val car = carId.filter { it.isLetterOrDigit() }.take(24)
        return FORMAT.format(Instant.ofEpochMilli(startMs)) + if (car.isEmpty()) "" else "-$car"
    }
}
