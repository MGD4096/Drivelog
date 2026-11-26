package dev.drivelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.car.CarActivity
import dev.drivelog.core.car.CarHealth
import dev.drivelog.core.car.OpenPartsText
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.car.Silhouette
import dev.drivelog.core.car.WidgetChoice
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripMeta
import java.util.Locale

/**
 * The home-screen widget: the car's name on top, then an icon and a value a line (fuel, range, the 12 V battery, whether
 * everything is closed), and when it was read at the bottom; of the car picked when the widget was placed
 * ([WidgetCarActivity]), or else the car that reported last; live while a trip of that car runs.
 * The trip service calls [update] whenever it saves new values; tapping the widget opens that car's page.
 */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = prefs(context).edit()
        for (id in ids) edit.remove(KEY_CAR + id)
        edit.apply()
    }

    companion object {
        private const val KEY_CAR = "car_"

        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        /** The cars a widget can show: the followed ones with the plugin, not archived. */
        fun cars(context: Context): List<CarRef> {
            val details = Stores(context).details
            return Prefs.cars(context).filter { val d = details.get(it.address); !d.archived && d.usesPlugin }
        }

        /** The vehicles the parking and fuel widgets can show: every followed one in use, with a plugin or without. */
        fun vehicles(context: Context): List<CarRef> {
            val details = Stores(context).details
            return Prefs.cars(context).filter { !details.get(it.address).archived }
        }

        /**
         * The vehicle a parking or fuel widget [id] shows: the one picked for it ([setCar]), or else the one whose latest
         * trip ended last (null when there is none).
         */
        fun vehicleOf(context: Context, id: Int, vehicles: List<CarRef>, trips: List<TripMeta>): CarRef? {
            val picked = carOf(context, id)
            vehicles.firstOrNull { it.address.equals(picked, ignoreCase = true) }?.let { return it }
            val latest = trips.filter { t -> vehicles.any { it.address.equals(t.carId, ignoreCase = true) } }.maxByOrNull { it.endMs }
            return vehicles.firstOrNull { it.address.equals(latest?.carId, ignoreCase = true) } ?: vehicles.firstOrNull()
        }

        /** The car (its address) widget [id] shows, or null: the car that reported last. */
        fun carOf(context: Context, id: Int): String? = prefs(context).getString(KEY_CAR + id, null)

        fun setCar(context: Context, id: Int, address: String?) {
            prefs(context).edit().apply { if (address == null) remove(KEY_CAR + id) else putString(KEY_CAR + id, address) }.apply()
        }

        /** Redraws every Drivelog widget on the home screen; cheap when there is none. Safe from any thread. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CarWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
            CarsWidget.update(context)
            ServicesWidget.update(context)
            LastTripWidget.update(context)
            ParkingWidget.update(context)
            MonthWidget.update(context)
            FuelWidget.update(context)
        }

        /**
         * The car's model when it is filled in. Never the Bluetooth name: many cars call themselves by their make and the
         * end of the VIN there, which has no place on a home screen.
         */
        internal fun title(model: String?): String = model?.takeIf { it.isNotBlank() } ?: "Drivelog"

        /** Shows a vehicle's picture in view [id]: a car's top view ([outline]) or the motorcycle, in the text colour. */
        internal fun showPicture(context: Context, views: RemoteViews, id: Int, kind: VehicleKind, openParts: List<String>?) {
            val colour = context.getColor(R.color.widget_text)
            if (kind == VehicleKind.MOTORCYCLE) {
                views.setImageViewResource(id, R.drawable.motorcycle_side)
                views.setInt(id, "setColorFilter", colour)                     // the drawing is a mask
            } else {
                views.setImageViewBitmap(id, outline(context, openParts, colour))
                views.setInt(id, "setColorFilter", 0)                          // coloured already; 0 changes nothing
            }
        }

        /**
         * The car page's top-view drawing as lines in [colour] on nothing (its white body and background clear, so no
         * panel shows behind it), with what is open painted orange as on the car page; turned a quarter to the left
         * (front to the left, like the motorcycle, so it lies across the widget); faded when [openParts] is unknown.
         */
        private fun outline(context: Context, openParts: List<String>?, colour: Int): Bitmap {
            val w = 200
            val h = (w * Silhouette.HEIGHT).toInt()
            val upright = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(upright)
            BitmapFactory.decodeResource(context.resources, R.drawable.car_top)?.let {
                canvas.drawBitmap(it, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(w * h)
            upright.getPixels(px, 0, w, 0, 0, w, h)
            val fade = if (openParts == null) 0.45 else 1.0
            for (i in px.indices) {
                val p = px[i]
                val dark = 255 - minOf(Color.red(p), Color.green(p), Color.blue(p))
                px[i] = ((dark * fade).toInt() shl 24) or (colour and 0xFFFFFF)
            }
            upright.setPixels(px, 0, w, 0, 0, w, h)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0xE6, 0x51, 0x00) }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = context.getColor(R.color.widget_alert)
                strokeWidth = 2f
            }
            for (z in Silhouette.zones(OpenPartsText.parts(openParts).map { it.part })) {
                if (!z.open) continue
                val box = RectF(z.x * w, z.y * w, (z.x + z.w) * w, (z.y + z.h) * w)
                canvas.drawRoundRect(box, z.radius * w, z.radius * w, fill)
                canvas.drawRoundRect(box, z.radius * w, z.radius * w, line)
            }
            val turned = Bitmap.createBitmap(h, w, Bitmap.Config.ARGB_8888)
            Canvas(turned).apply {
                translate(0f, w.toFloat())
                rotate(-90f)
                drawBitmap(upright, 0f, 0f, null)
            }
            upright.recycle()
            return turned
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = context.applicationContext
            // the launcher follows the phone's night mode, and so do the widget's colours (values-night)
            Colors.dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            AppSettings.applyLanguage(app)
            val ui = Ui(app)
            val stores = Stores(app)
            val details = stores.details
            val readings = stores.readings.all()
            val cars = cars(app)
            val status = AppState.status
            val liveCar = cars.firstOrNull { status.phase != Phase.IDLE && status.carName == it.name && AppState.car.lastUpdateMillis > 0 }
            for (id in ids) {
                val picked = WidgetChoice.pick(carOf(app, id), cars.map { it.address }, liveCar?.address, readings.mapValues { it.value.atMs })
                val car = cars.firstOrNull { it.address == picked }
                val live = car != null && car == liveCar
                val reading = car?.let { readings[it.address.lowercase(Locale.ROOT)] }
                val values = if (live) AppState.car.snapshot() else reading?.values
                val motorcycle = car != null && details.get(car.address).kind == VehicleKind.MOTORCYCLE
                // the picture on top, the values below: a car's top view with what is open, or a motorcycle's drawing
                val views = RemoteViews(app.packageName, R.layout.widget_car)
                fun showOutline(openParts: List<String>?) = showPicture(app, views, R.id.widget_outline, if (motorcycle) VehicleKind.MOTORCYCLE else VehicleKind.CAR, openParts)
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
                    showOutline(null)
                    views.setTextViewText(R.id.widget_fuel, "—")
                    for (r in listOf(R.id.widget_range_row, R.id.widget_battery_row, R.id.widget_state_row, R.id.widget_tyres_row)) views.setViewVisibility(r, View.GONE)
                    views.setViewVisibility(R.id.widget_note, View.VISIBLE)
                    views.setTextViewText(R.id.widget_note, tr("No car values yet. They appear after the first trip with the plugin."))
                    views.setTextViewText(R.id.widget_time, "")
                } else {
                    views.setViewVisibility(R.id.widget_note, View.GONE)
                    views.setTextViewText(R.id.widget_car, title(details.get(car.address).model))
                    showOutline(values.openParts)
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
                    views.setTextViewText(R.id.widget_time, if (live) tr("live") else reading?.let { tr("last read\n{0}", ui.dateTime(it.atMs)) } ?: "")
                }
                val open = if (car != null) Intent(app, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address)
                    else Intent(app, MainActivity::class.java)
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // one request code per widget, so that each keeps its own car
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                try {
                    manager.updateAppWidget(id, views)
                } catch (e: RuntimeException) {
                    // the launcher went away: it asks again when it comes back
                }
            }
        }
    }
}
