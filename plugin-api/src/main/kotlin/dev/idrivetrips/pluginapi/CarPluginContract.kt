package dev.idrivetrips.pluginapi

import android.os.Bundle
import dev.idrivetrips.carapi.CarValues

/**
 * How Drivelog finds and talks to a car plugin app.
 *
 * A plugin app declares a service like this (the permission keeps every app not signed with the same key out):
 * ```
 * <service android:name=".MyPluginService" android:exported="true"
 *          android:permission="dev.idrivetrips.permission.CAR_PLUGIN">
 *     <intent-filter><action android:name="dev.idrivetrips.CAR_PLUGIN" /></intent-filter>
 *     <meta-data android:name="dev.idrivetrips.plugin.id" android:value="bmw" />
 *     <meta-data android:name="dev.idrivetrips.plugin.label" android:value="Drivelog BMW ID6" />
 *     <meta-data android:name="dev.idrivetrips.plugin.api" android:value="1" />
 * </service>
 * ```
 * Its onBind returns an [ICarPlugin]. Drivelog binds it while a trip of a car that uses it runs, calls
 * [ICarPlugin.start] with an [ICarDataSink], and gets [CarValues] (as a Bundle, [CarValuesBundle]) and status changes.
 */
object CarPluginContract {
    const val ACTION = "dev.idrivetrips.CAR_PLUGIN"

    /**
     * Optional: an activity of the plugin with this action (category DEFAULT, protected by [PERMISSION]) is its own
     * screen. Drivelog offers it on the car's page ("Open <plugin>"), so a plugin needs no icon in the app drawer.
     */
    const val ACTION_SETTINGS = "dev.idrivetrips.CAR_PLUGIN_SETTINGS"
    const val PERMISSION = "dev.idrivetrips.permission.CAR_PLUGIN"
    const val META_ID = "dev.idrivetrips.plugin.id"
    const val META_LABEL = "dev.idrivetrips.plugin.label"
    const val META_API = "dev.idrivetrips.plugin.api"

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

    fun toBundle(v: CarValues): Bundle = Bundle().apply {
        v.odometerKm?.let { putDouble(ODOMETER, it) }
        v.fuelLiters?.let { putDouble(FUEL, it) }
        v.rangeKm?.let { putDouble(RANGE, it) }
        v.avgConsumptionKmPerL?.let { putDouble(AVG, it) }
        v.outsideC?.let { putDouble(OUTSIDE, it) }
        v.insideC?.let { putDouble(INSIDE, it) }
        v.openParts?.let { putStringArrayList(OPEN, ArrayList(it)) }
    }

    /** Never throws: a value of the wrong type counts as unknown. The receiver still runs [CarValues.checked]. */
    fun fromBundle(b: Bundle?): CarValues {
        if (b == null) return CarValues.EMPTY
        fun num(key: String): Double? = try {
            if (b.containsKey(key)) b.getDouble(key, Double.NaN).takeIf { !it.isNaN() } else null   // wrong type: NaN
        } catch (e: RuntimeException) {
            null
        }
        val open = try { b.getStringArrayList(OPEN) } catch (e: RuntimeException) { null }
        return CarValues(num(ODOMETER), num(FUEL), num(RANGE), num(AVG), num(OUTSIDE), num(INSIDE), open?.filterNotNull())
    }
}
