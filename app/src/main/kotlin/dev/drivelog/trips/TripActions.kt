package dev.drivelog.trips

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.drivelog.Colors
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.core.storage.TripStore
import dev.drivelog.core.text.tr
import dev.drivelog.core.text.trn
import dev.drivelog.core.trip.CounterFuel
import dev.drivelog.core.trip.TripKind
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.map.MapActivity
import dev.drivelog.map.RouteLoader
import dev.drivelog.map.RouteView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * The list of trips and what can be done with one: look at its figures, show it on a map, delete it; its mileage-log
 * details ([TripLogEditor]) and sharing it ([TripShare]) are parts of their own. Shared by the trip list and the list
 * of one car's trips. [onChanged] is called after a trip is changed or deleted.
 */
class TripActions(
    private val activity: Activity,
    private val ui: Ui,
    private val store: TripStore?,
    private val io: ExecutorService,
    private val onChanged: () -> Unit,
) {
    private val stores = Stores(activity)
    private val logEditor = TripLogEditor(activity, ui, store, io, onChanged)
    private val share = TripShare(activity, ui, store, io)

    /** The model of the car when it is filled in, otherwise the name of its Bluetooth device. */
    private fun carLabel(m: TripMeta): String =
        stores.carName(m.carId, m.carName)

    private val strong = Colors.strong
    private val soft = Colors.soft

    /** The fuel counter's correction per car ([CounterFuel.factor]), worked out from the list being shown. */
    private var counterFactors: Map<String, Double> = emptyMap()

    private fun factor(m: TripMeta): Double = counterFactors[m.carId.lowercase(Locale.ROOT)] ?: 1.0

    /** L/100 km of a trip and whether it comes from the car's fuel counter (short trips) rather than the tank level. */
    private fun consumption(m: TripMeta): Pair<Double, Boolean>? = CounterFuel.consumption(m.summary, factor(m))

    /** Remembers the correction of each car's fuel counter from these trips. */
    fun learnFactors(list: List<TripMeta>) {
        counterFactors = list.groupBy { it.carId.lowercase(Locale.ROOT) }.mapValues { (_, trips) -> CounterFuel.factor(trips) }
    }

    /** Three tiles: how many trips, how far, how long on the road. */
    fun summaryTiles(list: List<TripMeta>): View {
        fun tile(caption: String, value: String) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = GradientDrawable().apply { setColor(soft); cornerRadius = ui.dp(14).toFloat() }
            addView(ui.body().apply { text = caption; textSize = 12f; setTextColor(strong) })
            addView(ui.body().apply { text = value; textSize = 18f; setTextColor(strong); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = listOf(
            tr("Trips") to list.size.toString(),
            tr("Distance") to tr("{0} km", ui.km(list.sumOf { it.summary.distanceKm })),
            tr("Driving time") to ui.duration(list.sumOf { it.summary.durationMs }),
        )
        cells.forEachIndexed { i, (caption, value) ->
            row.addView(tile(caption, value), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = ui.dp(4)
                if (i < cells.size - 1) rightMargin = ui.dp(4)
            })
        }
        return row
    }

    /** Fills [box] with the trips, newest first, under a heading for each month. [showCar]: name the car on every card. */
    fun fill(box: LinearLayout, list: List<TripMeta>, showCar: Boolean) {
        learnFactors(list)
        val zone = ZoneId.systemDefault()
        val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
        var month: String? = null
        for ((i, meta) in list.withIndex()) {
            val m = monthFormat.format(Instant.ofEpochMilli(meta.startMs).atZone(zone)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            if (m != month) {
                month = m
                val inMonth = list.filter { monthFormat.format(Instant.ofEpochMilli(it.startMs).atZone(zone)).replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } == m }
                box.addView(ui.body().apply {
                    text = "$m  ·  " + trn(inMonth.size, "{0} trip", "{0} trips") + "  ·  " + tr("{0} km", ui.km(inMonth.sumOf { it.summary.distanceKm }))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Colors.muted)
                    setPadding(ui.dp(2), if (i == 0) ui.dp(4) else ui.dp(16), 0, ui.dp(2))
                })
            }
            box.addView(card(meta, showCar), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
        }
    }

    private fun whenText(ms: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val time = ui.time(ms)
        return when (day) {
            today -> tr("Today, {0}", time)
            today.minusDays(1) -> tr("Yesterday, {0}", time)
            else -> ui.dateTime(ms)
        }
    }

    /** One trip as a card: when it was (and in which car) on the left, the distance big on the right, a line of figures below. */
    fun card(m: TripMeta, showCar: Boolean): View {
        val sm = m.summary
        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.body().apply { text = whenText(m.startMs); setTypeface(typeface, Typeface.BOLD) })
            if (showCar) addView(ui.body().apply { text = carLabel(m); textSize = 13f; setTextColor(Colors.subtle) })
            logEditor.line(m).let { line -> addView(ui.body().apply { text = line; textSize = 13f; setTextColor(if (m.log.kind == TripKind.BUSINESS) strong else Colors.subtle); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
        }
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            addView(ui.body().apply { text = "${ui.f1(sm.distanceKm)} km"; textSize = 20f; setTextColor(strong); setTypeface(typeface, Typeface.BOLD) })
        }
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
        }
        val figures = ArrayList<String>()
        figures += ui.duration(sm.durationMs)
        sm.avgMovingSpeedKmh?.let { figures += tr("avg {0} km/h", ui.f0(it)) }
        sm.maxSpeedKmh?.let { figures += tr("top {0} km/h", ui.f0(it)) }
        consumption(m)?.let { (v, counter) -> figures += "${ui.f1(v)} L/100 km" + if (counter) "*" else "" }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(ui.body().apply { text = figures.joinToString("  ·  "); textSize = 13f; setTextColor(Colors.muted); setPadding(0, ui.dp(4), 0, 0) })
            if (sm.startLat == null) addView(ui.body().apply { text = tr("No GPS track"); textSize = 12f; setTextColor(Colors.warn); setPadding(0, ui.dp(2), 0, 0) })
        }
        val card = ui.card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(10), ui.dp(10), ui.dp(14), ui.dp(10))
            if (sm.startLat != null) {
                // The route as a line; it appears a moment later, when the outline has been read
                val thumb = RouteView(activity)
                addView(thumb, LinearLayout.LayoutParams(ui.dp(84), ui.dp(72)).apply { rightMargin = ui.dp(12) })
                RouteLoader.load(activity, store, m.id, io) { thumb.setShape(it) }
            } else {
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isClickable = true
            setOnClickListener { showDetails(m) }
        }
        return card
    }

    fun describe(m: TripMeta): String {
        val s = m.summary
        return buildString {
            append("${ui.dateTime(m.startMs)} · ${carLabel(m)}\n")
            append("${ui.f1(s.distanceKm)} km · ${ui.duration(s.durationMs)}")
            consumption(m)?.let { append(" · ${ui.f1(it.first)} L/100 km") }
            if (s.startLat == null) append(" · " + tr("no GPS track"))
        }
    }

    fun showDetails(m: TripMeta) {
        val s = m.summary
        val message = buildString {
            append(tr("{0}\n{1} to {2}", carLabel(m), ui.dateTime(m.startMs), ui.dateTime(m.endMs)) + "\n\n")
            append(tr("Distance: {0} km", ui.f1(s.distanceKm)))
            s.odometerDistanceKm?.let { odo ->
                if (s.gpsDistanceKm > 0) append(tr(" (GPS {0}, odometer {1})", ui.f1(s.gpsDistanceKm), ui.f0(odo)))
            }
            append("\n" + tr("Duration: {0}, moving {1}", ui.duration(s.durationMs), ui.duration(s.movingTimeMs)))
            s.avgMovingSpeedKmh?.let { append("\n" + tr("Average speed while moving: {0} km/h", ui.f0(it))) }
            s.maxSpeedKmh?.let { append("\n" + tr("Top speed: {0} km/h", ui.f0(it)) + if (s.speedFromCar) tr(" (car's speed)") else tr(" (GPS)")) }
            s.carAvgSpeedKmh?.let { append("\n" + tr("Car's own average speed: {0} km/h", ui.f0(it))) }
            s.startOdometerKm?.let { from -> append("\n" + tr("Odometer: {0} → {1} km", ui.f0(from), ui.f0(s.endOdometerKm ?: from))) }
            s.startFuelL?.let { from -> append("\n" + tr("Fuel in tank: {0} → {1} L", ui.f0(from), ui.f0(s.endFuelL ?: from))) }
            s.fuelUsedL?.let { append("\n" + tr("Fuel used: {0} L (tank level is in whole litres)", ui.f1(it))) }
            if (s.refueledL > 0) append("\n" + tr("Refuelled during the trip: {0} L", ui.f0(s.refueledL)))
            // without a tank comparison (short trips), the car's fuel counter, scaled by the earlier trips ([CounterFuel])
            if (s.fuelUsedL == null) s.counterFuelL?.let { append("\n" + tr("Fuel used by the car's fuel counter: {0} L (estimate)", ui.f1(it * factor(m)))) }
            consumption(m)?.let { (v, counter) -> append("\n" + tr("Consumption: {0} L/100 km", ui.f1(v)) + if (counter) tr(" (fuel counter, estimate)") else "") }
            s.carAvgConsumptionKmPerL?.let { append("\n" + tr("Car's own average: {0} km/L", ui.f1(it))) }
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            if (s.startLat != null) {
                val preview = RouteView(activity)
                addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(190)).apply { bottomMargin = ui.dp(12) })
                RouteLoader.load(activity, store, m.id, io) { preview.setShape(it) }
                preview.isClickable = true
                preview.setOnClickListener { openMap(m) }
            }
            addView(ui.body().apply { text = message; textSize = 14f })
            addView(logEditor.box(m) { dialog?.dismiss(); logEditor.edit(m) })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Trip"))
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton(tr("Map")) { _, _ -> openMap(m) }
            .setNeutralButton(tr("Share…")) { _, _ -> share.choose(m) }
            .setNegativeButton(tr("Delete")) { _, _ -> confirmDelete(m) }
            .show()
    }

    private var dialog: AlertDialog? = null

    private fun openMap(m: TripMeta) {
        if (m.summary.startLat == null) {
            ui.toast(tr("This trip has no GPS track to show."))
            return
        }
        activity.startActivity(Intent(activity, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, m.id))
    }

    private fun confirmDelete(m: TripMeta) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Delete this trip?"))
            .setMessage(describe(m))
            .setPositiveButton(tr("Delete")) { _, _ ->
                val s = store ?: return@setPositiveButton
                io.execute {
                    s.delete(m.id)
                    RouteLoader.forget(activity, m.id)
                    activity.runOnUiThread { if (!activity.isDestroyed) onChanged() }
                }
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

}
