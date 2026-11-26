package dev.drivelog.core.trip

import dev.drivelog.core.car.CarSnapshot
import dev.drivelog.core.car.DriveTotals
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
    /** Added up from the car's own speed and fuel counter (plugin), when it sends them. */
    val carTotals: DriveTotals = DriveTotals.NONE,
    /** Business or private, purpose, route and driver, for the mileage log ([MileageLog]). */
    val log: TripLog = TripLog(),
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
    /** Fuel burnt according to the car's fuel counter, before calibration ([CounterFuel]). */
    val counterFuelL: Double? = null,
    /** The trip computer's average speed at the end of the trip. */
    val carAvgSpeedKmh: Double? = null,
    /** [maxSpeedKmh] and [movingTimeMs] come from the car's speed: only for a trip without a GPS track (see [TripStats]). */
    val speedFromCar: Boolean = false,
)

object TripStats {
    /** A segment faster than this (about 306 km/h) is a GPS glitch and is ignored. */
    const val MAX_PLAUSIBLE_SPEED_MPS = 85.0
    /** Segments slower than this are GPS drift while standing still. */
    const val MOVING_MIN_SPEED_MPS = 0.5
    /** The average over the moving time may lie this much above the top speed (rounding, a missed peak); more is a broken moving time. */
    const val MOVING_AVERAGE_SLACK = 1.1
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

        // GPS first. The plugin delivers the car's speed only now and then (on a change, sometimes many seconds apart),
        // so the car's top speed and moving time miss parts of the drive (seen: 28 s of a 6-minute trip, 208 km/h on
        // average). The car's figures are used only for a trip without a GPS track (no location permission, no fix),
        // and then only an average that does not lie above the top speed.
        val car = trip.carTotals
        val carMax = car.maxSpeedKmh
        val speedFromCar = movingMs == 0L && carMax != null && car.movingMs > 0
        val moving = if (speedFromCar) car.movingMs else movingMs
        val movingKmh = when {
            speedFromCar -> (distanceKm / (car.movingMs / 3_600_000.0)).takeIf { carMax != null && it <= carMax * MOVING_AVERAGE_SLACK + 1.0 }
            movingMs > 0 -> gpsKm / (movingMs / 3_600_000.0)
            else -> null
        }

        return TripSummary(
            durationMs = max(0L, trip.endMs - trip.startMs),
            distanceKm = distanceKm,
            gpsDistanceKm = gpsKm,
            odometerDistanceKm = odoKm,
            movingTimeMs = moving,
            avgMovingSpeedKmh = movingKmh,
            maxSpeedKmh = if (speedFromCar) car.maxSpeedKmh else maxKmh,
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
            counterFuelL = car.counterFuelL?.takeIf { it > 0 },
            carAvgSpeedKmh = trip.endCar.avgSpeedKmh,
            speedFromCar = speedFromCar,
        )
    }

    /** Short trips (moving the car in a car park, a Bluetooth glitch) are not worth keeping. */
    fun isWorthKeeping(summary: TripSummary): Boolean = summary.distanceKm >= 0.2
}

/**
 * Consumption from the car's fuel counter, for trips too short for the tank level (whole litres) to say anything. The
 * counter's unit is only estimated in the plugin, so it is scaled by how the counter compared with the tank level over
 * the car's earlier trips ([factor]).
 */
object CounterFuel {
    /** Tank-level litres the comparison needs before it is trusted. */
    const val MIN_TANK_LITERS = 20.0
    const val MIN_KM = 1.0

    /** Tank litres per counter litre over the trips with both; 1.0 until there is enough to compare. */
    fun factor(trips: List<TripMeta>): Double {
        val both = trips.map { it.summary }.filter { it.consumptionLPer100Km != null && it.fuelUsedL != null && (it.counterFuelL ?: 0.0) > 0 }
        val tank = both.sumOf { it.fuelUsedL ?: 0.0 }
        val counter = both.sumOf { it.counterFuelL ?: 0.0 }
        if (tank < MIN_TANK_LITERS || counter <= 0) return 1.0
        return (tank / counter).coerceIn(0.25, 4.0)
    }

    /** L/100 km: the tank level's when it has one, otherwise the counter's (second = true). */
    fun consumption(s: TripSummary, factor: Double): Pair<Double, Boolean>? {
        s.consumptionLPer100Km?.let { return it to false }
        val litres = s.counterFuelL?.takeIf { it > 0 } ?: return null
        if (s.distanceKm < MIN_KM) return null
        return litres * factor / s.distanceKm * 100.0 to true
    }

    /** Litres burnt: the tank level's, or the counter's scaled by [factor]. */
    fun used(s: TripSummary, factor: Double): Double? = s.fuelUsedL?.takeIf { s.consumptionLPer100Km != null }
        ?: s.counterFuelL?.let { it * factor } ?: s.fuelUsedL
}
