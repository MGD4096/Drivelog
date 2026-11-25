package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import dev.drivelog.core.Silhouette
import android.widget.RemoteViews
import dev.drivelog.core.CarHealth
import dev.drivelog.core.FileCarDetailsStore
import dev.drivelog.core.FileLastReadingStore
import dev.drivelog.core.OpenPartsText
import dev.drivelog.core.tr
import java.io.File
import java.util.Locale

/**
 * The home-screen widget: the car's name on top, then an icon and a value a line (fuel, range, the 12 V battery, whether
 * everything is closed), and when it was read at the bottom; of the car that reported last (live while a trip runs).
 * The trip service calls [update] whenever it saves new values; tapping the widget opens that car's page.
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

        /**
         * The car's model when it is filled in. Never the Bluetooth name: many cars call themselves by their make and the
         * end of the VIN there, which has no place on a home screen.
         */
        private fun title(model: String?): String = model?.takeIf { it.isNotBlank() } ?: "Drivelog"

        /** The top-view drawing of the car page ([CarSilhouetteView]) as a picture; faded when [openParts] is unknown. */
        private fun outline(context: Context, openParts: List<String>?): Bitmap {
            val w = 160
            val h = (w * Silhouette.HEIGHT).toInt()
            val view = CarSilhouetteView(context)
            view.setState(OpenPartsText.parts(openParts).map { it.part }, openParts != null)
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (openParts == null) canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), 115)
            view.draw(canvas)
            if (openParts == null) canvas.restore()
            return bitmap
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            // the launcher follows the phone's night mode, and so do the widget's colours (values-night)
            Colors.dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
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
            val normal = app.getColor(R.color.widget_text)
            val alert = app.getColor(R.color.widget_alert)
            views.setContentDescription(R.id.widget_fuel_icon, tr("Fuel"))
            views.setContentDescription(R.id.widget_range_icon, tr("Range"))
            /**
             * Shows the row of [row] with [text] (empty: the icon alone), icon and text in the alert colour when [warn];
             * hides it when [text] is null.
             */
            fun row(row: Int, icon: Int, label: Int, text: String?, warn: Boolean, iconRes: Int? = null, description: String? = null) {
                views.setViewVisibility(row, if (text == null) View.GONE else View.VISIBLE)
                if (text == null) return
                iconRes?.let { views.setImageViewResource(icon, it) }
                views.setInt(icon, "setColorFilter", if (warn) alert else normal)
                description?.let { views.setContentDescription(icon, it) }
                views.setTextViewText(label, text)
                views.setTextColor(label, if (warn) alert else normal)
            }
            if (car == null || values == null) {
                views.setTextViewText(R.id.widget_car, car?.let { title(details.get(it.address).model) } ?: "Drivelog")
                views.setImageViewBitmap(R.id.widget_outline, outline(app, null))
                views.setTextViewText(R.id.widget_fuel, "—")
                for (r in listOf(R.id.widget_range_row, R.id.widget_battery_row, R.id.widget_state_row, R.id.widget_tyres_row)) views.setViewVisibility(r, View.GONE)
                views.setViewVisibility(R.id.widget_note, View.VISIBLE)
                views.setTextViewText(R.id.widget_note, tr("No car values yet. They appear after the first trip with the plugin."))
                views.setTextViewText(R.id.widget_time, "")
            } else {
                views.setViewVisibility(R.id.widget_note, View.GONE)
                views.setTextViewText(R.id.widget_car, title(details.get(car.address).model))
                views.setImageViewBitmap(R.id.widget_outline, outline(app, values.openParts))
                views.setTextViewText(R.id.widget_fuel, values.fuelLiters?.let { "${ui.f0(it)} L" } ?: "—")
                row(R.id.widget_range_row, R.id.widget_range_icon, R.id.widget_range, values.rangeKm?.let { tr("{0} km", ui.f0(it)) }, false)
                val battery = values.batteryPercent
                row(R.id.widget_battery_row, R.id.widget_battery_icon, R.id.widget_battery,
                    battery?.let { tr("{0} %", ui.f0(it)) + if (CarHealth.isLow(it)) " · " + tr("low") else "" },
                    battery != null && CarHealth.isLow(battery), description = tr("12 V battery"))
                val parts = values.openParts
                val closed = parts != null && parts.isEmpty()
                row(R.id.widget_state_row, R.id.widget_state_icon, R.id.widget_state, parts?.let { OpenPartsText.describe(it) }, !closed,
                    if (closed) R.drawable.ic_widget_locked else R.drawable.ic_widget_unlocked)
                row(R.id.widget_tyres_row, R.id.widget_tyres_icon, R.id.widget_tyres,
                    values.tyrePressureWarning?.takeIf { it }?.let { tr("Tyre pressure warning") }, true)
                views.setTextViewText(R.id.widget_time, if (liveCar != null) tr("live") else reading?.let { tr("last read\n{0}", ui.dateTime(it.atMs)) } ?: "")
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
