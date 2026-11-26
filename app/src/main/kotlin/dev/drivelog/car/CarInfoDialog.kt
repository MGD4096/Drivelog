package dev.drivelog.car

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.drivelog.AppState
import dev.drivelog.BuildConfig
import dev.drivelog.CarRef
import dev.drivelog.Colors
import dev.drivelog.Phase
import dev.drivelog.Ui
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarHealth
import dev.drivelog.core.car.LastKnownCar
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.plugin.CarPluginRegistry

/** The "i" popup of the car page: the Bluetooth device and, for a car with a plugin, everything the plugin has reported. */
class CarInfoDialog(private val activity: Activity, private val ui: Ui, private val views: CarPageViews, private val widgets: CarWidgets) {

    /** What the dialog shows, as the page knows it now. */
    class Facts(
        val car: CarRef,
        val details: CarDetails,
        val summary: VehicleSummary,
        val known: LastKnownCar?,
        val batteryHistory: List<CarHealth.BatteryReading>,
    )

    fun show(f: Facts) {
        val c = f.car
        val d = f.details
        val known = f.known
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(8)) }
        fun row(label: String, value: String?) {
            if (value == null) return
            form.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(5), 0, ui.dp(5))
                addView(ui.body().apply { text = label; textSize = 14f; setTextColor(Colors.muted) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.body().apply { text = value; textSize = 14f; gravity = Gravity.END; setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            })
        }
        form.addView(views.caption(tr("Bluetooth device"), 14, 4))
        row(tr("Name"), c.name)
        row(tr("Address"), c.address)
        if (d.usesPlugin) {
            val live = AppState.status.phase != Phase.IDLE && AppState.status.carName == c.name
            val snap = if (live) AppState.car.snapshot() else known?.snapshot
            form.addView(views.caption(tr("Data from the plugin"), 14, 4))
            val source = when {
                live -> tr("Live: a trip is in progress")
                known == null -> tr("Nothing received yet: it appears after the first trip with the plugin")
                known.fromReading -> tr("Last read from the car on {0}", ui.dateTime(known.atMs))
                else -> tr("As reported at the end of the trip on {0}", ui.date(known.atMs))
            }
            form.addView(ui.body().apply { text = source; textSize = 12f; setTextColor(Colors.muted); setPadding(0, 0, 0, ui.dp(4)) })
            val plugin = CarPluginRegistry.installed(activity).firstOrNull { it.plugin.id == d.plugin }
            row(tr("Plugin"), plugin?.let { p -> listOfNotNull(p.plugin.label, versionOf(p.component.packageName)).joinToString(" ") }
                ?: tr("{0} (not installed)", d.plugin))
            // The plugin has no icon of its own: its screen (state of the car connection) is opened from here
            if (plugin != null) CarPluginRegistry.settingsIntent(activity, plugin)?.let { intent ->
                form.addView(ui.button(tr("Open {0}", plugin.plugin.label)) {
                    try { activity.startActivity(intent) } catch (e: RuntimeException) { ui.toast(tr("Could not open: {0}", e.message)) }
                })
            }
            val odo = (snap?.odometerKm ?: f.summary.odometerKm)
            row(tr("Odometer"), odo?.let { tr("{0} km", ui.km(it)) })
            row(tr("Fuel in tank"), snap?.fuelLiters?.let { "${ui.f0(it)} L" })
            row(tr("Range"), snap?.rangeKm?.let { tr("{0} km", ui.f0(it)) })
            row(tr("Average consumption"), snap?.avgConsumptionKmPerL?.takeIf { it > 0 }?.let { "${ui.f1(100.0 / it)} L/100 km" })
            row(tr("Outside temperature"), snap?.outsideC?.let { "${ui.f1(it)} °C" })
            row(tr("Inside temperature"), snap?.insideC?.let { "${ui.f1(it)} °C" })
            row(tr("Speed"), snap?.speedKmh?.takeIf { live }?.let { tr("{0} km/h", ui.f0(it)) })
            row(tr("Average speed (trip computer)"), snap?.avgSpeedKmh?.let { tr("{0} km/h", ui.f0(it)) })
            val battery = snap?.batteryPercent ?: f.batteryHistory.lastOrNull()?.percent
            row(tr("12 V battery"), battery?.let { tr("{0} %", ui.f0(it)) + if (CarHealth.isLow(it)) " · " + tr("low") else "" })
            row(tr("Battery over the latest trips"), CarHealth.trendText(f.batteryHistory))
            row(tr("Tyre pressure"), snap?.tyrePressureWarning?.let { if (it) tr("Warning") else tr("No warning") })
            row(tr("Tyre pressures"), snap?.tyrePressuresBar?.let { p -> tr("{0} bar", p.joinToString(" / ") { ui.f1(it) }) })
            val doors = widgets.doorsStatus(c, known)
            row(tr("Doors and windows"), if (doors.closed == null) null else doors.text)
            if (snap == null && f.summary.odometerKm == null) form.addView(ui.body().apply { text = tr("No data yet."); textSize = 14f })
        }
        row(tr("Drivelog"), BuildConfig.VERSION_NAME)
        AlertDialog.Builder(activity)
            .setTitle(tr("Car information"))
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("OK"), null)
            .show()
    }

    /** The version name of an installed app (the plugin), so an old one is easy to spot. */
    private fun versionOf(packageName: String): String? = try {
        activity.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }
}
