package dev.drivelog.core.trip

import dev.drivelog.carapi.CarValues
import dev.drivelog.core.car.CarDetails
import java.util.Locale

/**
 * Values a plugin hands over after the trip (`CarPluginContract.ACTION_READING`): a plugin that reads them from
 * another app, which gets them from the vehicle only when it disconnects. This decides which vehicle they are for, and
 * whether they are the end values of the trip that vehicle made last.
 */
object LateReading {
    /** How long after a trip's end a reading may still be its end values. */
    const val MAX_DELAY_MS = 12 * 3_600_000L

    /** Odometer slack over the GPS distance: the odometer counts whole kilometres, GPS loses tunnels. */
    const val ODOMETER_SLACK_KM = 5.0

    /**
     * The vehicle (Bluetooth address) a reading of [pluginId] is for: of the vehicles that use that plugin and are not
     * archived, the one whose latest trip ended last. Null when no vehicle uses it.
     */
    fun vehicleFor(pluginId: String, vehicles: Map<String, CarDetails>, trips: List<TripMeta>): String? {
        val candidates = vehicles.filter { (_, d) -> d.usesPlugin && d.plugin == pluginId && !d.archived }.keys
        if (candidates.size <= 1) return candidates.firstOrNull()
        val lastEnd = trips.groupBy { key(it.carId) }.mapValues { (_, list) -> list.maxOf { it.endMs } }
        return candidates.maxByOrNull { lastEnd[key(it)] ?: Long.MIN_VALUE }
    }

    /**
     * [last] (the vehicle's newest trip) with [values] as its end values, or null when they do not belong to it: the
     * reading is from before the trip or long after it, or its odometer does not fit the distance driven (an older
     * reading, or rides recorded without the phone in between). Null as well when nothing would change.
     */
    fun endOf(last: Trip, values: CarValues, atMs: Long): Trip? {
        if (atMs < last.startMs || atMs > last.endMs + MAX_DELAY_MS) return null
        val v = values.checked()
        val odometer = v.odometerKm
        if (odometer != null) {
            val summary = TripStats.summarize(last)
            val start = summary.startOdometerKm
            if (start != null) {
                val driven = odometer - start
                if (driven < 0) return null
                if (driven > summary.gpsDistanceKm * 1.2 + ODOMETER_SLACK_KM) return null
            }
        }
        val end = last.endCar
        val newEnd = end.copy(
            odometerKm = odometer ?: end.odometerKm,
            fuelLiters = v.fuelLiters ?: end.fuelLiters,
            rangeKm = v.rangeKm ?: end.rangeKm,
            tyrePressuresBar = v.tyrePressuresBar ?: end.tyrePressuresBar,
        )
        return if (newEnd == end) null else last.copy(endCar = newEnd)
    }

    private fun key(id: String) = id.lowercase(Locale.ROOT)
}
