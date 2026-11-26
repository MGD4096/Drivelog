package dev.drivelog

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.core.text.tr
import dev.drivelog.map.MapActivity
import dev.drivelog.map.RouteLoader
import dev.drivelog.map.WidgetMap

/**
 * The home-screen widget with the last trip of any vehicle: the vehicle, its route on a small map, distance and time,
 * then when, the average speed and the consumption. Tapping it opens the route on the full map. Drawn on the widgets'
 * background thread (the map may load tiles); redrawn with the other widgets ([CarWidget.update]).
 * Never rename or move this class: placed widgets are kept by its name.
 */
class LastTripWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        WidgetMap.executor.execute {
            try { render(context.applicationContext, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private const val REQUEST_BASE = 3 shl 24

        fun update(context: Context) = WidgetKit.inBackground(context, LastTripWidget::class.java, ::render)

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = WidgetKit.prepare(context)
            val ui = Ui(app)
            val stores = Stores(app)
            val trips = stores.trips
            val last = trips?.list()?.maxByOrNull { it.endMs }
            val map = last?.let { RouteLoader.shape(app, trips, it.id) }?.let { WidgetMap.route(app, it, 520, 260, Colors.dark) }
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_last_trip)
                views.setViewVisibility(R.id.last_map, if (map == null) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.last_osm, if (map == null) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.last_empty, if (map == null) View.VISIBLE else View.GONE)
                if (map != null) views.setImageViewBitmap(R.id.last_map, map)
                views.setTextViewText(R.id.last_osm, "© OpenStreetMap")
                if (last == null) {
                    views.setTextViewText(R.id.last_title, tr("Last trip"))
                    views.setTextViewText(R.id.last_empty, tr("No trips yet."))
                    views.setTextViewText(R.id.last_main, "")
                    views.setTextViewText(R.id.last_more, "")
                    views.setOnClickPendingIntent(R.id.last_root, WidgetKit.open(app, REQUEST_BASE + id, Intent(app, MainActivity::class.java)))
                } else {
                    val s = last.summary
                    views.setTextViewText(R.id.last_title, tr("Last trip") + " · " + stores.carName(last.carId, last.carName))
                    views.setTextViewText(R.id.last_empty, tr("No route recorded for this trip."))
                    views.setTextViewText(R.id.last_main, tr("{0} km", ui.f1(s.distanceKm)) + " · " + ui.duration(s.durationMs))
                    views.setTextViewText(R.id.last_more, listOfNotNull(
                        ui.date(last.startMs) + " " + ui.time(last.startMs),
                        s.avgMovingSpeedKmh?.let { tr("{0} km/h", ui.f0(it)) },
                        s.consumptionLPer100Km?.let { tr("{0} L/100 km", ui.f1(it)) },
                    ).joinToString(" · "))
                    val open = Intent(app, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, last.id)
                    views.setOnClickPendingIntent(R.id.last_root, WidgetKit.open(app, REQUEST_BASE + id, open))
                }
                WidgetKit.push(manager, id, views)
            }
        }
    }
}
