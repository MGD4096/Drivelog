package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.car.CarActivity
import dev.drivelog.core.car.CarHealth
import dev.drivelog.core.car.OpenPartsText
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.text.tr
import java.util.Locale

/**
 * The home-screen widget with several vehicles: a tile each, side by side (up to [MAX]) with its picture, name, fuel, range, the 12 V
 * battery and whether it is locked, and when it was read. Which vehicles: picked when the widget is placed ([WidgetCarsActivity]),
 * or else the first [MAX] of the list. Redrawn with the one-vehicle widget ([CarWidget.update]); tapping a tile opens
 * that vehicle's page.
 */
class CarsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        for (id in ids) edit.remove(KEY_CARS + id)
        edit.apply()
    }

    companion object {
        const val MAX = 3
        private const val KEY_CARS = "cars_"

        private val TILES = intArrayOf(R.id.cars_tile1, R.id.cars_tile2, R.id.cars_tile3)
        private val OUTLINES = intArrayOf(R.id.cars_outline1, R.id.cars_outline2, R.id.cars_outline3)
        private val NAMES = intArrayOf(R.id.cars_name1, R.id.cars_name2, R.id.cars_name3)
        private val FUEL_ICONS = intArrayOf(R.id.cars_fuel_icon1, R.id.cars_fuel_icon2, R.id.cars_fuel_icon3)
        private val FUELS = intArrayOf(R.id.cars_fuel1, R.id.cars_fuel2, R.id.cars_fuel3)
        private val RANGE_ICONS = intArrayOf(R.id.cars_range_icon1, R.id.cars_range_icon2, R.id.cars_range_icon3)
        private val RANGES = intArrayOf(R.id.cars_range1, R.id.cars_range2, R.id.cars_range3)
        private val BATTERY_ICONS = intArrayOf(R.id.cars_battery_icon1, R.id.cars_battery_icon2, R.id.cars_battery_icon3)
        private val BATTERIES = intArrayOf(R.id.cars_battery1, R.id.cars_battery2, R.id.cars_battery3)
        private val LOCK_ICONS = intArrayOf(R.id.cars_lock_icon1, R.id.cars_lock_icon2, R.id.cars_lock_icon3)
        private val LOCKS = intArrayOf(R.id.cars_lock1, R.id.cars_lock2, R.id.cars_lock3)
        private val FUEL_ROWS = intArrayOf(R.id.cars_fuel_row1, R.id.cars_fuel_row2, R.id.cars_fuel_row3)
        private val RANGE_ROWS = intArrayOf(R.id.cars_range_row1, R.id.cars_range_row2, R.id.cars_range_row3)
        private val BATTERY_ROWS = intArrayOf(R.id.cars_battery_row1, R.id.cars_battery_row2, R.id.cars_battery_row3)
        private val LOCK_ROWS = intArrayOf(R.id.cars_lock_row1, R.id.cars_lock_row2, R.id.cars_lock_row3)
        private val TIMES = intArrayOf(R.id.cars_time1, R.id.cars_time2, R.id.cars_time3)

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        /** The vehicles (addresses) widget [id] shows, in order; null when none were picked (the first [MAX] then). */
        fun carsOf(context: Context, id: Int): List<String>? =
            prefs(context).getString(KEY_CARS + id, null)?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

        fun setCars(context: Context, id: Int, addresses: List<String>) {
            prefs(context).edit().putString(KEY_CARS + id, addresses.take(MAX).joinToString(",")).apply()
        }

        /** Redraws every widget of this kind; cheap when there is none. Safe from any thread. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CarsWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            Colors.dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            AppSettings.applyLanguage(app)
            val ui = Ui(app)
            val stores = Stores(app)
            val details = stores.details
            val readings = stores.readings.all()
            val cars = CarWidget.cars(app)
            val status = AppState.status
            val liveCar = cars.firstOrNull { status.phase != Phase.IDLE && status.carName == it.name && AppState.car.lastUpdateMillis > 0 }
            val normal = app.getColor(R.color.widget_text)
            val alert = app.getColor(R.color.widget_alert)
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_cars)
                val picked = carsOf(app, id)
                val shown = (picked?.mapNotNull { a -> cars.firstOrNull { it.address.equals(a, ignoreCase = true) } } ?: cars).take(MAX)
                views.setViewVisibility(R.id.cars_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.cars_empty, tr("No vehicles with a plugin yet."))
                for (slot in 0 until MAX) {
                    val car = shown.getOrNull(slot)
                    views.setViewVisibility(TILES[slot], if (car == null) View.GONE else View.VISIBLE)
                    if (car == null) continue
                    val d = details.get(car.address)
                    val live = car == liveCar
                    val reading = readings[car.address.lowercase(Locale.ROOT)]
                    val values = if (live) AppState.car.snapshot() else reading?.values
                    CarWidget.showPicture(app, views, OUTLINES[slot], d.kind, values?.openParts)
                    views.setTextViewText(NAMES[slot], stores.carName(car.address, car.name))
                    // the values one under another, each with its icon: fuel, range, the 12 V battery, the lock. A value the
                    // vehicle does not report is hidden; one that needs attention is in the alert colour.
                    fun row(row: Int, icon: Int, text: Int, value: String?, warn: Boolean, description: String) {
                        views.setViewVisibility(row, if (value == null) View.GONE else View.VISIBLE)
                        if (value == null) return
                        val colour = if (warn) alert else normal
                        views.setInt(icon, "setColorFilter", colour)
                        views.setContentDescription(icon, description)
                        views.setTextViewText(text, value)
                        views.setTextColor(text, colour)
                    }
                    val fuel = values?.fuelLiters
                    val range = values?.rangeKm
                    val battery = values?.batteryPercent
                    val parts = values?.openParts
                    row(FUEL_ROWS[slot], FUEL_ICONS[slot], FUELS[slot], fuel?.let { "${ui.f0(it)} L" } ?: "—", false, tr("Fuel"))
                    row(RANGE_ROWS[slot], RANGE_ICONS[slot], RANGES[slot], range?.let { tr("{0} km", ui.f0(it)) }, false, tr("Range"))
                    row(BATTERY_ROWS[slot], BATTERY_ICONS[slot], BATTERIES[slot], battery?.let { tr("{0} %", ui.f0(it)) }, battery != null && CarHealth.isLow(battery), tr("12 V battery"))
                    row(LOCK_ROWS[slot], LOCK_ICONS[slot], LOCKS[slot], parts?.let { if (it.isEmpty()) tr("Closed") else OpenPartsText.describe(it) },
                        parts != null && parts.isNotEmpty(), tr("Doors and windows"))
                    if (parts != null) views.setImageViewResource(LOCK_ICONS[slot], if (parts.isEmpty()) R.drawable.ic_widget_locked else R.drawable.ic_widget_unlocked)
                    views.setTextViewText(TIMES[slot], when {
                        live -> tr("live")
                        reading != null -> ui.date(reading.atMs) + " " + ui.time(reading.atMs)
                        else -> ""
                    })
                    val intent = Intent(app, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    // one request code per widget and row, so that each tile opens its own vehicle
                    views.setOnClickPendingIntent(TILES[slot], PendingIntent.getActivity(app, id * MAX + slot + REQUEST_BASE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                if (shown.isEmpty()) {
                    val main = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    views.setOnClickPendingIntent(R.id.cars_root, PendingIntent.getActivity(app, id * MAX + REQUEST_BASE, main, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
                try {
                    manager.updateAppWidget(id, views)
                } catch (e: RuntimeException) {
                    // the launcher went away: it asks again when it comes back
                }
            }
        }

        /** Keeps these request codes apart from the one-vehicle widget's (its widget ids). */
        private const val REQUEST_BASE = 1 shl 24
    }
}
