package dev.drivelog

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.core.text.tr
import dev.drivelog.core.text.trn
import dev.drivelog.core.trip.MonthlyStats
import dev.drivelog.core.trip.TripKind
import dev.drivelog.map.WidgetMap
import dev.drivelog.trips.StatsActivity
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The home-screen widget with this month on all vehicles ([MonthlyStats]): the kilometres and how they compare with
 * last month, the trips and the time, business and private kilometres, the money spent on fuel and service and per
 * kilometre. Tapping it opens the statistics. Redrawn with the other widgets ([CarWidget.update]).
 * Never rename or move this class: placed widgets are kept by its name.
 */
class MonthWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        WidgetMap.executor.execute {
            try { render(context.applicationContext, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private const val REQUEST_BASE = 5 shl 24

        fun update(context: Context) = WidgetKit.inBackground(context, MonthWidget::class.java, ::render)

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = WidgetKit.prepare(context)
            val ui = Ui(app)
            val stores = Stores(app)
            val trips = stores.trips?.list().orEmpty()
            val cars = Prefs.cars(app)
            val fuel = cars.flatMap { stores.fuel.list(it.address) }
            val events = cars.flatMap { stores.events.list(it.address) }
            val now = YearMonth.now()
            val (before, month) = MonthlyStats.of(trips, fuel, events, now, months = 2)
            val kinds = MonthlyStats.kmByKind(trips, now)
            val monthName = DateTimeFormatter.ofPattern("LLLL", Locale.getDefault())
            val monthOf = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
            val title = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(now).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val change = MonthlyStats.change(month.km, before.km)
            val nothing = month.trips == 0 && month.cost == 0.0
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_month)
                views.setTextViewText(R.id.month_title, title)
                views.setTextViewText(R.id.month_km, tr("{0} km", ui.km(month.km)))
                views.setTextViewText(R.id.month_change, when {
                    change == null -> tr("{0}: {1} km", monthName.format(before.month), ui.km(before.km))
                    // "MMMM" is the month as a sentence needs it ("against October", "względem października")
                    else -> (if (change >= 0) "▲ " else "▼ ") + tr("{0} % against {1}", ui.f0(abs(change) * 100), monthOf.format(before.month))
                })
                views.setViewVisibility(R.id.month_empty, if (nothing) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.month_empty, tr("No trips this month yet."))
                for (row in listOf(R.id.month_trips, R.id.month_kinds, R.id.month_cost, R.id.month_per_km)) {
                    views.setViewVisibility(row, if (nothing) View.GONE else View.VISIBLE)
                }
                views.setTextViewText(R.id.month_trips, trn(month.trips, "{0} trip", "{0} trips") + " · " + ui.duration(month.durationMs))
                views.setTextViewText(R.id.month_kinds, tr("Business {0} km · private {1} km",
                    ui.km(kinds.getValue(TripKind.BUSINESS)), ui.km(kinds.getValue(TripKind.PRIVATE))))
                views.setTextViewText(R.id.month_cost, tr("Fuel {0} · service {1}", ui.money(month.fuelCost), ui.money(month.otherCost)))
                views.setTextViewText(R.id.month_per_km, month.costPerKm?.let { tr("{0} per km", ui.money(it)) } ?: "")
                views.setOnClickPendingIntent(R.id.month_root, WidgetKit.open(app, REQUEST_BASE + id, Intent(app, StatsActivity::class.java)))
                WidgetKit.push(manager, id, views)
            }
        }
    }
}
