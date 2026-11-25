package dev.drivelog.pluginapi

import android.os.Bundle
import dev.drivelog.carapi.CarValues

/**
 * How Drivelog finds and talks to a car plugin app.
 *
 * A plugin app declares a service like this (the permission keeps every app not signed with the same key out):
 * ```
 * <service android:name=".MyPluginService" android:exported="true"
 *          android:permission="dev.drivelog.permission.CAR_PLUGIN">
 *     <intent-filter><action android:name="dev.drivelog.CAR_PLUGIN" /></intent-filter>
 *     <meta-data android:name="dev.drivelog.plugin.id" android:value="id6" />
 *     <meta-data android:name="dev.drivelog.plugin.label" android:value="Drivelog ID6" />
 *     <meta-data android:name="dev.drivelog.plugin.api" android:value="1" />
 * </service>
 * ```
 * Its onBind returns an [ICarPlugin]. Drivelog binds it while a trip of a car that uses it runs, calls
 * [ICarPlugin.start] with an [ICarDataSink], and gets [CarValues] (as a Bundle, [CarValuesBundle]) and status changes.
 */
object CarPluginContract {
    const val ACTION = "dev.drivelog.CAR_PLUGIN"

    /**
     * Optional: an activity of the plugin with this action (category DEFAULT, protected by [PERMISSION]) is its own
     * screen. Drivelog offers it on the car's page ("Open <plugin>"), so a plugin needs no icon in the app drawer.
     */
    const val ACTION_SETTINGS = "dev.drivelog.CAR_PLUGIN_SETTINGS"
    const val PERMISSION = "dev.drivelog.permission.CAR_PLUGIN"
    const val META_ID = "dev.drivelog.plugin.id"
    const val META_LABEL = "dev.drivelog.plugin.label"
    const val META_API = "dev.drivelog.plugin.api"

    /** Raised only for changes either side cannot ignore; new Bundle keys and states need no new version. */
    const val API_VERSION = 1

    const val STATE_STOPPED = 0
    /** The car (its network, its adapter ...) is not there right now: not an error. */
    const val STATE_NO_CAR = 1
    const val STATE_CONNECTING = 2
    const val STATE_CONNECTED = 3
    /** The car is there but does not answer; the text says why. The plugin keeps trying. */
    const val STATE_ERROR = 4
}

/** [CarValues] in a Bundle: one key per value, a missing key = unknown. Unknown keys are ignored (newer plugins). */
object CarValuesBundle {
    private const val ODOMETER = "odometerKm"
    private const val FUEL = "fuelLiters"
    private const val RANGE = "rangeKm"
    private const val AVG = "avgConsumptionKmPerL"
    private const val OUTSIDE = "outsideC"
    private const val INSIDE = "insideC"
    private const val OPEN = "openParts"
    private const val BATTERY = "batteryPercent"
    private const val TYRES = "tyrePressureWarning"
    private const val SPEED = "speedKmh"
    private const val AVG_SPEED = "avgSpeedKmh"
    private const val FUEL_TOTAL = "fuelUsedTotalL"

    fun toBundle(v: CarValues): Bundle = Bundle().apply {
        v.odometerKm?.let { putDouble(ODOMETER, it) }
        v.fuelLiters?.let { putDouble(FUEL, it) }
        v.rangeKm?.let { putDouble(RANGE, it) }
        v.avgConsumptionKmPerL?.let { putDouble(AVG, it) }
        v.outsideC?.let { putDouble(OUTSIDE, it) }
        v.insideC?.let { putDouble(INSIDE, it) }
        v.openParts?.let { putStringArrayList(OPEN, ArrayList(it)) }
        v.batteryPercent?.let { putDouble(BATTERY, it) }
        v.tyrePressureWarning?.let { putBoolean(TYRES, it) }
        v.speedKmh?.let { putDouble(SPEED, it) }
        v.avgSpeedKmh?.let { putDouble(AVG_SPEED, it) }
        v.fuelUsedTotalL?.let { putDouble(FUEL_TOTAL, it) }
    }

    /** Never throws: a value of the wrong type counts as unknown. The receiver still runs [CarValues.checked]. */
    fun fromBundle(b: Bundle?): CarValues {
        if (b == null) return CarValues.EMPTY
        fun num(key: String): Double? = try {
            if (b.containsKey(key)) b.getDouble(key, Double.NaN).takeIf { !it.isNaN() } else null   // wrong type: NaN
        } catch (e: RuntimeException) {
            null
        }
        fun flag(key: String): Boolean? = try {
            // a missing key or another type gives each default back: only a real Boolean gives the same answer twice
            val a = b.getBoolean(key, false)
            if (a == b.getBoolean(key, true)) a else null
        } catch (e: RuntimeException) {
            null
        }
        val open = try { b.getStringArrayList(OPEN) } catch (e: RuntimeException) { null }
        return CarValues(
            num(ODOMETER), num(FUEL), num(RANGE), num(AVG), num(OUTSIDE), num(INSIDE), open?.filterNotNull(),
            num(BATTERY), flag(TYRES), num(SPEED), num(AVG_SPEED), num(FUEL_TOTAL),
        )
    }
}
