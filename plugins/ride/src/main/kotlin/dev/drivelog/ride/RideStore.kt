package dev.drivelog.ride

import android.content.Context
import dev.drivelog.carapi.CarValues

/** What the plugin keeps: the vehicle's latest values (both screens put together) and the tank size the user gave. */
class RideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ride", Context.MODE_PRIVATE)

    /** Litres, or null when not set: the vehicle app shows the fuel only in %. */
    var tankLiters: Double?
        get() = prefs.getFloat(TANK, 0f).toDouble().takeIf { it > 0 }
        set(value) { prefs.edit().putFloat(TANK, (value ?: 0.0).toFloat()).apply() }

    /** Whether to open the vehicle app after a ride and read it ([ScreenReaderService]). On unless turned off. */
    var autoOpen: Boolean
        get() = prefs.getBoolean(AUTO, true)
        set(value) { prefs.edit().putBoolean(AUTO, value).apply() }

    /** When a reading was last sent to Drivelog. */
    var sentAtMs: Long
        get() = prefs.getLong(SENT, 0L)
        set(value) { prefs.edit().putLong(SENT, value).apply() }

    var latest: RideReading?
        get() {
            val values = prefs.getString(VALUES, null)?.let { CarValues.fromJson(it) }?.checked() ?: return null
            return RideReading(values, prefs.getLong(AT, 0L))
        }
        set(value) {
            if (value == null) prefs.edit().remove(VALUES).remove(AT).apply()
            else prefs.edit().putString(VALUES, CarValues.toJson(value.values)).putLong(AT, value.atMs).apply()
        }

    private companion object {
        const val TANK = "tankLiters"
        const val VALUES = "values"
        const val AT = "atMs"
        const val SENT = "sentAtMs"
        const val AUTO = "autoOpen"
    }
}
