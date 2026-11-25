package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.drivelog.core.CarHealth
import dev.drivelog.core.FileCarDetailsStore
import dev.drivelog.core.FileLastReadingStore
import dev.drivelog.core.OpenPartsText
import dev.drivelog.core.tr
import java.io.File
import java.util.Locale

/**
 * The home-screen widget: fuel and range, odometer, the 12 V battery and whether everything is closed, of the car that
 * reported last (live while a trip runs). The trip service calls [update] whenever it saves new values; tapping the
 * widget opens that car's page.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        /** Redraws every Drivelog widget on the home screen; cheap when there is none. Safe from any thread. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CarWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            AppSettings.applyLanguage(app)
            val ui = Ui(app)
            val views = RemoteViews(app.packageName, R.layout.widget_car)
            val details = FileCarDetailsStore(File(app.filesDir, "cars.json"))
            val readings = try { FileLastReadingStore(File(app.filesDir, FileLastReadingStore.FILE_NAME)).all() } catch (e: Exception) { emptyMap() }
            val cars = Prefs.cars(app).filter { !details.get(it.address).archived && details.get(it.address).usesPlugin }
            val status = AppState.status
            val liveCar = cars.firstOrNull { status.phase != Phase.IDLE && status.carName == it.name && AppState.car.lastUpdateMillis > 0 }
            // the car of the trip running now, otherwise the one that reported last
            val car = liveCar ?: cars.maxByOrNull { readings[it.address.lowercase(Locale.ROOT)]?.atMs ?: Long.MIN_VALUE }
            val reading = car?.let { readings[it.address.lowercase(Locale.ROOT)] }
            val values = if (liveCar != null) AppState.car.snapshot() else reading?.values
            if (car == null || values == null) {
                views.setTextViewText(R.id.widget_car, car?.let { details.get(it.address).model ?: it.name } ?: "Drivelog")
                views.setTextViewText(R.id.widget_fuel, "—")
                views.setTextViewText(R.id.widget_odometer, "")
                views.setTextViewText(R.id.widget_state, tr("No car values yet. They appear after the first trip with the plugin."))
                views.setTextViewText(R.id.widget_time, "")
            } else {
                views.setTextViewText(R.id.widget_car, details.get(car.address).model ?: car.name)
                views.setTextViewText(R.id.widget_fuel, listOfNotNull(
                    values.fuelLiters?.let { "${ui.f0(it)} L" },
                    values.rangeKm?.let { tr("range {0} km", ui.f0(it)) },
                ).joinToString(" · ").ifEmpty { "—" })
                views.setTextViewText(R.id.widget_odometer, values.odometerKm?.let { tr("{0} km", ui.km(it)) } ?: "")
                views.setTextViewText(R.id.widget_state, listOfNotNull(
                    values.batteryPercent?.let { tr("12 V battery") + " " + tr("{0} %", ui.f0(it)) + if (CarHealth.isLow(it)) " · " + tr("low") else "" },
                    values.openParts?.let { OpenPartsText.describe(it) },
                    values.tyrePressureWarning?.takeIf { it }?.let { tr("Tyre pressure warning") },
                ).joinToString(" · "))
                views.setTextViewText(R.id.widget_time, if (liveCar != null) tr("live") else reading?.let { tr("last read {0}", ui.dateTime(it.atMs)) } ?: "")
            }
            val open = if (car != null) Intent(app, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address)
                else Intent(app, MainActivity::class.java)
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(app, 70, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            try {
                manager.updateAppWidget(ids, views)
            } catch (e: RuntimeException) {
                // the launcher went away: it asks again when it comes back
            }
        }
    }
}
