package dev.drivelog.core.car

import dev.drivelog.core.storage.TripLoader
import dev.drivelog.core.trip.TripMeta
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the Cars screen shows about one car, worked out from its recorded trips. */
data class VehicleSummary(
    val tripCount: Int,
    val totalKm: Double,
    /** The same, for trips that started in the calendar year asked for. */
    val yearTripCount: Int,
    val yearKm: Double,
    /** Odometer at the end of the car's latest trip that had one. */
    val odometerKm: Double?,
    val odometerAtMs: Long?,
    /** Where the car is parked: the end of its latest trip with a GPS track, or [CarDetails.parkedLat] when newer. */
    val parkedLat: Double?,
    val parkedLon: Double?,
    val parkedAtMs: Long?,
    /** Start time and distance of each of the car's trips, for adding up what was driven since a manual odometer entry. */
    val tripKms: List<Pair<Long, Double>> = emptyList(),
    /** The odometer comes from the saved latest reading, not from the end of a trip. */
    val odometerFromReading: Boolean = false,
) {
    /** Kilometres driven on trips that started at or after [sinceMs]. */
    fun kmSince(sinceMs: Long): Double = tripKms.filter { it.first >= sinceMs }.sumOf { it.second }

    /**
     * The odometer of a car without a plugin: the reading typed in by hand plus every trip made since it was entered.
     * Null when no reading was ever entered.
     */
    fun manualOdometerNow(d: CarDetails): Double? {
        val base = d.manualOdometerKm ?: return null
        return base + kmSince(d.manualOdometerAtMs ?: Long.MAX_VALUE)
    }

    companion object {
        fun of(
            carId: String,
            trips: List<TripMeta>,
            year: Int = LocalDate.now().year,
            zone: ZoneId = ZoneId.systemDefault(),
            /** The car's details: a parking place remembered after a trip too short to keep. */
            details: CarDetails? = null,
            /** The latest values the car reported ([FileLastReadingStore]): its odometer, when newer than the trips'. */
            reading: LastReading? = null,
        ): VehicleSummary {
            val mine = trips.filter { it.carId.equals(carId, ignoreCase = true) }.sortedByDescending { it.endMs }
            val thisYear = mine.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).year == year }
            val odo = mine.firstOrNull { (it.summary.endOdometerKm ?: it.summary.startOdometerKm) != null }
            val readOdo = reading?.takeIf { r -> r.values.odometerKm != null && r.atMs > (odo?.endMs ?: Long.MIN_VALUE) }
            val park = mine.firstOrNull { it.summary.endLat != null && it.summary.endLon != null }
            val tripParkedAt = park?.endMs ?: Long.MIN_VALUE
            val remembered = details?.takeIf { it.parkedLat != null && it.parkedLon != null && (it.parkedAtMs ?: Long.MIN_VALUE) > tripParkedAt }
            return VehicleSummary(
                tripCount = mine.size,
                totalKm = mine.sumOf { it.summary.distanceKm },
                yearTripCount = thisYear.size,
                yearKm = thisYear.sumOf { it.summary.distanceKm },
                odometerKm = readOdo?.values?.odometerKm ?: odo?.let { it.summary.endOdometerKm ?: it.summary.startOdometerKm },
                odometerAtMs = readOdo?.atMs ?: odo?.endMs,
                odometerFromReading = readOdo != null,
                parkedLat = remembered?.parkedLat ?: park?.summary?.endLat,
                parkedLon = remembered?.parkedLon ?: park?.summary?.endLon,
                parkedAtMs = remembered?.parkedAtMs ?: park?.endMs,
                tripKms = mine.map { it.startMs to it.summary.distanceKm },
            )
        }
    }
}

/**
 * What the car reported last (the saved latest reading, [withReading]) or at the end of its latest trips: fuel and range ([snapshot], at [atMs]) and which doors, windows
 * and so on were open ([openParts], at [openPartsAtMs]). Each comes from the latest trip that had it.
 */
data class LastKnownCar(
    val snapshot: CarSnapshot,
    val atMs: Long,
    val openParts: List<String>? = null,
    val openPartsAtMs: Long? = null,
    /** True when it comes from the saved latest reading ([FileLastReadingStore]) rather than from the end of a trip. */
    val fromReading: Boolean = false,
) {
    companion object {
        /**
         * The saved latest reading when it is newer than what the trips tell (it normally is: it is saved as the values
         * come in, also during trips that are not kept). Doors and windows the reading lacks come from the trips.
         */
        fun withReading(fromTrips: LastKnownCar?, reading: LastReading?): LastKnownCar? {
            if (reading == null || (fromTrips != null && fromTrips.atMs > reading.atMs)) return fromTrips
            val parts = reading.values.openParts
            return LastKnownCar(
                snapshot = reading.values,
                atMs = reading.atMs,
                openParts = parts ?: fromTrips?.openParts,
                openPartsAtMs = if (parts != null) reading.atMs else fromTrips?.openPartsAtMs,
                fromReading = true,
            )
        }

        /**
         * Looks at the car's latest trips (up to [LOOK_BACK]) for ones that ended with a fuel or range reading, or with
         * a doors-and-windows state. Reads trip files, so call it from a background thread.
         */
        fun find(carId: String, trips: List<TripMeta>, store: TripLoader): LastKnownCar? {
            val mine = trips.filter { it.carId.equals(carId, ignoreCase = true) }.sortedByDescending { it.endMs }.take(LOOK_BACK)
            var fuel: Pair<CarSnapshot, Long>? = null
            var parts: Pair<List<String>, Long>? = null
            for (meta in mine) {
                if (fuel != null && parts != null) break
                val car = store.load(meta.id)?.endCar ?: continue
                if (fuel == null && (car.fuelLiters != null || car.rangeKm != null)) fuel = car to meta.endMs
                val open = car.openParts
                if (parts == null && open != null) parts = open to meta.endMs
            }
            val at = fuel?.second ?: parts?.second ?: return null
            return LastKnownCar(fuel?.first ?: CarSnapshot.EMPTY, at, parts?.first, parts?.second)
        }

        const val LOOK_BACK = 5
    }
}

/** The order of the car list: the car driven most recently first. */
object CarOrder {
    /** When the car's latest trip ended, or null when it has none. */
    fun lastDrivenMs(carId: String, trips: List<TripMeta>): Long? =
        trips.filter { it.carId.equals(carId, ignoreCase = true) }.maxOfOrNull { it.endMs }

    /**
     * [items] sorted by the end of their latest trip, newest first; those never driven keep their order at the end.
     * [drivingNow] puts the car being recorded at the top whatever its trips say.
     */
    fun <T> byLastDriven(items: List<T>, trips: List<TripMeta>, carId: (T) -> String, drivingNow: (T) -> Boolean = { false }): List<T> =
        items.sortedByDescending { if (drivingNow(it)) Long.MAX_VALUE else lastDrivenMs(carId(it), trips) ?: Long.MIN_VALUE }
}
