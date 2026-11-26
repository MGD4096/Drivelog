package dev.drivelog.recording

import android.content.Context
import dev.drivelog.CarWidget
import dev.drivelog.Notifications
import dev.drivelog.core.car.CarHealth
import dev.drivelog.core.car.CarState
import dev.drivelog.core.storage.CarDetailsStore
import dev.drivelog.core.storage.LastReadingStore
import dev.drivelog.core.storage.TripStore
import dev.drivelog.core.text.tr
import java.io.IOException
import java.util.Locale

/**
 * What a trip changes in the car's record besides the trip itself: the latest values the car sent, where it was
 * left, and the warning when its 12 V battery has just got low. Main thread only.
 */
class CarRecordKeeper(
    private val context: Context,
    private val details: CarDetailsStore,
    private val readings: LastReadingStore,
) {
    /** The time of the car values saved last, so the same delivery is not written twice. */
    private var savedReadingMs = 0L

    fun newTrip() { savedReadingMs = 0L }

    /**
     * Saves what the car sent last, whether the trip is kept or not, so the car page shows it after the trip. Only
     * when something new arrived since the last save.
     */
    fun saveReading(address: String, car: CarState) {
        val at = car.lastUpdateMillis
        if (at == 0L || at == savedReadingMs) return
        try {
            readings.put(address, car.snapshot(), at)
            savedReadingMs = at
            CarWidget.update(context)
        } catch (e: IOException) {
            // tried again on the next tick
        }
    }

    /**
     * Where the car was left, also after a trip too short to keep (moving the car a few metres is still moving it).
     * The car page and the Cars tab show it when it is newer than the end of the latest saved trip.
     */
    fun rememberParking(address: String, lat: Double?, lon: Double?, atMs: Long) {
        if (lat == null || lon == null) return
        try {
            details.put(address, details.get(address).withParked(lat, lon, atMs))
        } catch (e: IOException) {
            // the latest saved trip still tells where it is
        }
    }

    /** After a trip is saved: a notification when the 12 V battery has just dropped below CarHealth.LOW_BATTERY_PERCENT. */
    fun checkBattery(address: String, name: String, trips: TripStore) {
        val history = CarHealth.batteryHistory(address, trips.list(), trips)
        if (!CarHealth.batteryJustGotLow(history)) return
        Notifications.carWarning(
            context, Notifications.ID_BATTERY, tr("12 V battery low"),
            tr("{0}: the 12 V battery was at {1} % at the start of the trip ({2}). Have it checked or charged.",
                name, String.format(Locale.getDefault(), "%.0f", history.last().percent), CarHealth.trendText(history) ?: ""),
            address,
        )
    }
}
