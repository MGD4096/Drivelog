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

    /**
     * Optional, for a plugin that learns the vehicle's values only after the trip (it reads them from another app once
     * the vehicle has sent them there): a broadcast to Drivelog ([DRIVELOG_PACKAGE]) with this action and
     * [EXTRA_PLUGIN_ID], [EXTRA_VALUES] (a [CarValuesBundle]) and [EXTRA_AT_MS] (when the vehicle reported them, epoch
     * ms). Drivelog's receiver takes it only from apps that hold [PERMISSION]. It saves the values as the vehicle's latest
     * ones and, when they fit, as the end values of the trip that ended last.
     */
    const val ACTION_READING = "dev.drivelog.CAR_READING"
    const val EXTRA_PLUGIN_ID = "dev.drivelog.extra.PLUGIN_ID"
    const val EXTRA_VALUES = "dev.drivelog.extra.VALUES"
    const val EXTRA_AT_MS = "dev.drivelog.extra.AT_MS"
    const val DRIVELOG_PACKAGE = "dev.drivelog"

    /**
     * Export plugins: apps that take Drivelog's data somewhere else (a home server, …). Such a plugin declares a
     * receiver with this action, protected by [PERMISSION] (so only Drivelog can send to it) and signed like Drivelog;
     * an activity with [ACTION_SETTINGS] is its screen. Drivelog sends it the state of every vehicle in use whenever
     * something changes (at most about once a minute): [EXTRA_STATE], JSON text:
     * ```
     * {"format":1,"atMs":…,"vehicles":[{"id":"3f2a…","name":"…","kind":"car"|"motorcycle",
     *   "values":{…CarValues JSON…},"readAtMs":…,            // the latest reading, if any
     *   "parked":{"lat":…,"lon":…,"atMs":…},                  // where it was left, if known
     *   "services":[{"type":"inspection"|"insurance"|"oil_change","label":"…","dueDate":"2027-07-15",
     *                "daysLeft":233,"nextAtKm":…,"kmLeft":…,"level":"ok"|"soon"|"overdue"}]}]}
     * ```
     * `id` is stable per vehicle and does not reveal its Bluetooth address. Unknown keys are to be ignored.
     */
    const val ACTION_EXPORT = "dev.drivelog.EXPORT"
    const val EXTRA_STATE = "dev.drivelog.extra.STATE"

    /**
     * An export plugin asking for the state now (its "Send now"): a broadcast to Drivelog ([DRIVELOG_PACKAGE]), taken
     * only from apps holding [PERMISSION]. Drivelog answers with [ACTION_EXPORT] at once, without its usual minute.
     */
    const val ACTION_EXPORT_REQUEST = "dev.drivelog.EXPORT_REQUEST"

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
    private const val TYRES_BAR = "tyrePressuresBar"

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
        v.tyrePressuresBar?.let { putDoubleArray(TYRES_BAR, it.toDoubleArray()) }
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
        val tyres = try { b.getDoubleArray(TYRES_BAR)?.toList() } catch (e: RuntimeException) { null }
        return CarValues(
            num(ODOMETER), num(FUEL), num(RANGE), num(AVG), num(OUTSIDE), num(INSIDE), open?.filterNotNull(),
            num(BATTERY), flag(TYRES), num(SPEED), num(AVG_SPEED), num(FUEL_TOTAL), tyres,
        )
    }
}
