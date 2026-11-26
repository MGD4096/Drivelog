package dev.drivelog.core.car

import dev.drivelog.core.storage.TripLoader
import dev.drivelog.core.trip.TripMeta

/**
 * The 12 V battery and the tyre pressure warning, as the car's plugin reports them ([CarSnapshot.batteryPercent],
 * [CarSnapshot.tyrePressureWarning]): the battery's history over the latest trips, and when to warn.
 */
object CarHealth {

    /**
     * Below this the battery counts as low. An assumption: a healthy car showed 85–97 % in the scans so far, and a
     * lead-acid battery below about three quarters charged is worth having checked.
     */
    const val LOW_BATTERY_PERCENT = 75.0

    /** How many trips the battery history looks back over. */
    const val HISTORY_TRIPS = 8

    data class BatteryReading(val atMs: Long, val percent: Double)

    /**
     * The battery's charge over the car's latest trips, oldest first: the reading at the start of each trip (after
     * the car stood, which says most about the battery), or at its end when the start had none. Reads trip files, so
     * call it from a background thread.
     */
    fun batteryHistory(carId: String, trips: List<TripMeta>, store: TripLoader, max: Int = HISTORY_TRIPS): List<BatteryReading> =
        trips.filter { it.carId.equals(carId, ignoreCase = true) }
            .sortedByDescending { it.startMs }
            .asSequence()
            .mapNotNull { meta ->
                val trip = store.load(meta.id) ?: return@mapNotNull null
                val pct = trip.startCar.batteryPercent ?: trip.endCar.batteryPercent ?: return@mapNotNull null
                BatteryReading(meta.startMs, pct)
            }
            .take(max)
            .toList()
            .reversed()

    fun isLow(percent: Double?): Boolean = percent != null && percent < LOW_BATTERY_PERCENT

    /** True when the newest reading is low and the one before was not (or there was none): warn once, not every trip. */
    fun batteryJustGotLow(history: List<BatteryReading>): Boolean {
        val last = history.lastOrNull() ?: return false
        val before = history.getOrNull(history.size - 2)
        return isLow(last.percent) && !isLow(before?.percent)
    }

    /** "97 → 96 → 85 %", or null with fewer than two readings. */
    fun trendText(history: List<BatteryReading>): String? =
        if (history.size < 2) null else history.joinToString(" → ") { "%.0f".format(java.util.Locale.ROOT, it.percent) } + " %"
}
