package dev.drivelog

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.map.WidgetMap
import java.util.Locale

/**
 * The home-screen widget with where a vehicle is parked: its name, a small map with a pin, since when. Tapping it hands
 * the place to a navigation app (a `geo:` link, so any map app the phone has will do). The vehicle is picked when the
 * widget is placed ([WidgetCarActivity]), or else the one used last. Drawn on the widgets' background thread (the map
 * may load tiles); redrawn with the other widgets ([CarWidget.update]).
 * Never rename or move this class: placed widgets are kept by its name.
 */
class ParkingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        WidgetMap.executor.execute {
            try { render(context.applicationContext, manager, ids) } finally { pending.finish() }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) CarWidget.setCar(context, id, null)
    }

    companion object {
        private const val REQUEST_BASE = 4 shl 24

        fun update(context: Context) = WidgetKit.inBackground(context, ParkingWidget::class.java, ::render)

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = WidgetKit.prepare(context)
            val ui = Ui(app)
            val stores = Stores(app)
            val trips = stores.trips?.list().orEmpty()
            val vehicles = CarWidget.vehicles(app)
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_parking)
                val car = CarWidget.vehicleOf(app, id, vehicles, trips)
                val summary = car?.let { VehicleSummary.of(it.address, trips, details = stores.details.get(it.address)) }
                val lat = summary?.parkedLat
                val lon = summary?.parkedLon
                val name = car?.let { stores.carName(it.address, it.name) } ?: "Drivelog"
                views.setTextViewText(R.id.parking_title, name)
                val map = if (lat != null && lon != null) WidgetMap.pin(app, lat, lon, 420, 260, Colors.dark) else null
                views.setViewVisibility(R.id.parking_map, if (map == null) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.parking_osm, if (map == null) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.parking_empty, if (map == null) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.parking_osm, "© OpenStreetMap")
                if (map != null && lat != null && lon != null) {
                    views.setImageViewBitmap(R.id.parking_map, map)
                    views.setTextViewText(R.id.parking_since, summary.parkedAtMs?.let { tr("Parked {0}", ui.date(it) + " " + ui.time(it)) } ?: "")
                    // to a navigation app: the place, labelled with the vehicle's name
                    val place = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
                    val navigate = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$place?q=$place(" + Uri.encode(name) + ")"))
                    views.setOnClickPendingIntent(R.id.parking_root, WidgetKit.open(app, REQUEST_BASE + id, navigate))
                } else {
                    views.setTextViewText(R.id.parking_empty, tr("Not known yet: it is noted at the end of each trip."))
                    views.setTextViewText(R.id.parking_since, "")
                    views.setOnClickPendingIntent(R.id.parking_root, WidgetKit.open(app, REQUEST_BASE + id, Intent(app, MainActivity::class.java)))
                }
                WidgetKit.push(manager, id, views)
            }
        }
    }
}
