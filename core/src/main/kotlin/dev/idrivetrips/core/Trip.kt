package dev.idrivetrips.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val accuracyM: Double?,
)

/** The car values at one moment; recorded only when one of them changes. */
data class CarSample(
    val timeMs: Long,
    val odometerKm: Double?,
    val fuelLiters: Double?,
    val outsideC: Double?,
    val insideC: Double?,
)

data class Trip(
    val id: String,
    val carId: String,
    val carName: String,
    val startMs: Long,
    val endMs: Long,
    val points: List<TrackPoint>,
    val samples: List<CarSample>,
    val startCar: CarSnapshot,
    val endCar: CarSnapshot,
)

data class TripSummary(
    val durationMs: Long,
    /** The distance to show: GPS when it looks complete, otherwise the odometer. */
    val distanceKm: Double,
    val gpsDistanceKm: Double,
    val odometerDistanceKm: Double?,
    val movingTimeMs: Long,
    val avgMovingSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val startOdometerKm: Double?,
    val endOdometerKm: Double?,
    val startFuelL: Double?,
    val endFuelL: Double?,
    /** Litres burnt: tank level drop plus any refuelling during the trip. Coarse: the tank level is whole litres. */
    val fuelUsedL: Double?,
    val refueledL: Double,
    /** Only when the trip is long enough (5 km, 1 L) for the coarse tank level to mean something. */
    val consumptionLPer100Km: Double?,
    /** The trip computer's own long-term average at the end of the trip, in km per litre. */
    val carAvgConsumptionKmPerL: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
)

object TripStats {
    /** A segment faster than this (about 306 km/h) is a GPS glitch and is ignored. */
    const val MAX_PLAUSIBLE_SPEED_MPS = 85.0
    /** Segments slower than this are GPS drift while standing still. */
    const val MOVING_MIN_SPEED_MPS = 0.5
    /** A rise in tank level of at least this many litres between two readings is a refuelling, not fuel sloshing. */
    const val REFUEL_MIN_LITERS = 5.0
    const val MIN_KM_FOR_CONSUMPTION = 5.0
    const val MIN_LITERS_FOR_CONSUMPTION = 1.0

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun summarize(trip: Trip): TripSummary {
        val pts = trip.points

        var gpsMeters = 0.0
        var movingMs = 0L
        var maxSegmentSpeed = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val dtSec = (b.timeMs - a.timeMs) / 1000.0
            if (dtSec <= 0) continue
            val meters = haversineMeters(a.lat, a.lon, b.lat, b.lon)
            val speed = meters / dtSec
            if (speed > MAX_PLAUSIBLE_SPEED_MPS || speed < MOVING_MIN_SPEED_MPS) continue
            gpsMeters += meters
            movingMs += b.timeMs - a.timeMs
            maxSegmentSpeed = max(maxSegmentSpeed, speed)
        }
        val gpsKm = gpsMeters / 1000.0

        val reportedMax = pts.mapNotNull { it.speedMps }.filter { it <= MAX_PLAUSIBLE_SPEED_MPS }.maxOrNull()
        val maxMps = reportedMax ?: maxSegmentSpeed.takeIf { it > 0 }
        val maxKmh = maxMps?.times(3.6)

        val odometers = listOf(trip.startCar.odometerKm) + trip.samples.map { it.odometerKm } + listOf(trip.endCar.odometerKm)
        val odoFirst = odometers.firstNotNullOfOrNull { it }
        val odoLast = odometers.lastOrNull { it != null }
        val odoKm = if (odoFirst != null && odoLast != null && odoLast >= odoFirst) odoLast - odoFirst else null

        // GPS is smooth and exact to about 1 %, the odometer only counts whole kilometres. The odometer wins only
        // when GPS clearly lost part of the trip (tunnels, no fix) or there is no GPS at all.
        val distanceKm = when {
            odoKm != null && odoKm >= 5.0 && gpsKm < 0.8 * odoKm -> odoKm
            gpsKm > 0.0 -> gpsKm
            else -> odoKm ?: 0.0
        }

        val fuels = (listOf(trip.startCar.fuelLiters) + trip.samples.map { it.fuelLiters } + listOf(trip.endCar.fuelLiters))
            .filterNotNull()
        var refueled = 0.0
        for (i in 1 until fuels.size) {
            val rise = fuels[i] - fuels[i - 1]
            if (rise >= REFUEL_MIN_LITERS) refueled += rise
        }
        val fuelUsed = if (fuels.size >= 2) max(0.0, fuels.first() - fuels.last() + refueled) else null
        val consumption = if (fuelUsed != null && distanceKm >= MIN_KM_FOR_CONSUMPTION && fuelUsed >= MIN_LITERS_FOR_CONSUMPTION) {
            fuelUsed / distanceKm * 100.0
        } else null

        val movingKmh = if (movingMs > 0) gpsKm / (movingMs / 3_600_000.0) else null

        return TripSummary(
            durationMs = max(0L, trip.endMs - trip.startMs),
            distanceKm = distanceKm,
            gpsDistanceKm = gpsKm,
            odometerDistanceKm = odoKm,
            movingTimeMs = movingMs,
            avgMovingSpeedKmh = movingKmh,
            maxSpeedKmh = maxKmh,
            startOdometerKm = odoFirst,
            endOdometerKm = odoLast,
            startFuelL = fuels.firstOrNull(),
            endFuelL = fuels.lastOrNull(),
            fuelUsedL = fuelUsed,
            refueledL = refueled,
            consumptionLPer100Km = consumption,
            carAvgConsumptionKmPerL = trip.endCar.avgConsumptionKmPerL,
            startLat = pts.firstOrNull()?.lat,
            startLon = pts.firstOrNull()?.lon,
            endLat = pts.lastOrNull()?.lat,
            endLon = pts.lastOrNull()?.lon,
        )
    }

    /** Short trips (moving the car in a car park, a Bluetooth glitch) are not worth keeping. */
    fun isWorthKeeping(summary: TripSummary): Boolean = summary.distanceKm >= 0.2
}
