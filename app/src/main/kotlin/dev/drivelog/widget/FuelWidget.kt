package dev.drivelog

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.drivelog.car.CarActivity
import dev.drivelog.core.car.FuelLog
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.map.WidgetMap
import java.time.LocalDate

/**
 * The home-screen widget with the fuel of one vehicle ([FuelLog]): the average consumption over its latest
 * [AVERAGE_OVER] full tanks, what is in the tank now and how far it goes, the last refuelling and its price, and the
 * fuel bill of the year per kilometre. The vehicle is picked when the widget is placed ([WidgetCarActivity]), or
 * else the one used last; tapping opens its page. Redrawn with the other widgets ([CarWidget.update]).
 * Never rename or move this class: placed widgets are kept by its name.
 */
class FuelWidget : AppWidgetProvider() {

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
        private const val REQUEST_BASE = 6 shl 24
        const val AVERAGE_OVER = 5

        fun update(context: Context) = WidgetKit.inBackground(context, FuelWidget::class.java, ::render)

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val app = WidgetKit.prepare(context)
            val ui = Ui(app)
            val stores = Stores(app)
            val trips = stores.trips?.list().orEmpty()
            val vehicles = CarWidget.vehicles(app)
            for (id in ids) {
                val views = RemoteViews(app.packageName, R.layout.widget_fuel)
                val car = CarWidget.vehicleOf(app, id, vehicles, trips)
                views.setTextViewText(R.id.fuel_title, car?.let { stores.carName(it.address, it.name) } ?: "Drivelog")
                val entries = car?.let { stores.fuel.list(it.address) }.orEmpty()
                val reading = car?.let { stores.readings.get(it.address) }?.values
                val average = FuelLog.average(FuelLog.intervals(entries).sortedBy { it.toMs }.takeLast(AVERAGE_OVER))
                val last = entries.maxByOrNull { it.atMs }
                val nothing = average == null && last == null && reading?.fuelLiters == null
                views.setViewVisibility(R.id.fuel_empty, if (nothing) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.fuel_empty, tr("No refuellings logged yet. They are added on the vehicle's page, or confirmed when the plugin notices them."))
                for (row in listOf(R.id.fuel_average, R.id.fuel_average_label, R.id.fuel_tank, R.id.fuel_last, R.id.fuel_year)) {
                    views.setViewVisibility(row, if (nothing) View.GONE else View.VISIBLE)
                }
                views.setTextViewText(R.id.fuel_average, average?.let { tr("{0} L/100 km", ui.f1(it)) } ?: "—")
                views.setTextViewText(R.id.fuel_average_label, tr("average over the latest full tanks"))
                views.setTextViewText(R.id.fuel_tank, listOfNotNull(
                    reading?.fuelLiters?.let { tr("In the tank {0} L", ui.f0(it)) },
                    reading?.rangeKm?.let { tr("range {0} km", ui.f0(it)) },
                ).joinToString(" · ").ifEmpty { tr("Tank: not known") })
                views.setTextViewText(R.id.fuel_last, last?.let { e ->
                    listOfNotNull(
                        tr("Refuelled {0}", ui.date(e.atMs)),
                        tr("{0} L", ui.f1(e.liters)),
                        e.pricePerLiter?.let { tr("{0}/L", ui.money(it)) },
                    ).joinToString(" · ")
                } ?: tr("No refuelling logged"))
                val year = LocalDate.now().year
                val bill = FuelLog.totalCost(entries, year)
                val yearKm = car?.let { VehicleSummary.of(it.address, trips, year).yearKm } ?: 0.0
                views.setTextViewText(R.id.fuel_year, if (bill > 0) {
                    tr("Fuel in {0}: {1}", year, ui.money(bill)) + (FuelLog.perKm(bill, yearKm)?.let { " · " + tr("{0} per km", ui.money(it)) } ?: "")
                } else "")
                val open = if (car != null) Intent(app, CarActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, car.address) else Intent(app, MainActivity::class.java)
                views.setOnClickPendingIntent(R.id.fuel_root, WidgetKit.open(app, REQUEST_BASE + id, open))
                WidgetKit.push(manager, id, views)
            }
        }
    }
}
