package dev.drivelog

import dev.drivelog.core.trn
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.drivelog.core.Csv
import dev.drivelog.core.CounterFuel
import dev.drivelog.core.TripKind
import dev.drivelog.core.TripLog
import dev.drivelog.core.TripStore
import dev.drivelog.core.Gpx
import dev.drivelog.core.Trip
import dev.drivelog.core.TripMeta
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import dev.drivelog.core.tr

/**
 * What can be done with one recorded trip: look at its figures, show it on a map, share it as GPX or CSV, delete it.
 * Shared by the trip list and the list of one car's trips. [onChanged] is called after a trip is deleted.
 */
class TripActions(
    private val activity: Activity,
    private val ui: Ui,
    private val store: TripStore?,
    private val io: ExecutorService,
    private val onChanged: () -> Unit,
) {
    private val stores = Stores(activity)

    private val carDetails = stores.details

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
            logLine(m)?.let { line -> addView(ui.body().apply { text = line; textSize = 13f; setTextColor(if (m.log.kind == TripKind.BUSINESS) strong else Colors.subtle); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) }
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
            s.maxSpeedKmh?.let { append("\n" + tr("Top speed: {0} km/h", ui.f0(it))) }
            s.startOdometerKm?.let { from -> append("\n" + tr("Odometer: {0} → {1} km", ui.f0(from), ui.f0(s.endOdometerKm ?: from))) }
            s.startFuelL?.let { from -> append("\n" + tr("Fuel in tank: {0} → {1} L", ui.f0(from), ui.f0(s.endFuelL ?: from))) }
            s.fuelUsedL?.let { append("\n" + tr("Fuel used: {0} L (tank level is in whole litres)", ui.f1(it))) }
            if (s.refueledL > 0) append("\n" + tr("Refuelled during the trip: {0} L", ui.f0(s.refueledL)))
            s.consumptionLPer100Km?.let { append("\n" + tr("Consumption: {0} L/100 km", ui.f1(it))) }
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
            addView(logBox(m) { dialog?.dismiss(); editLog(m) })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Trip"))
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setPositiveButton(tr("Map")) { _, _ -> openMap(m) }
            .setNeutralButton(tr("Share…")) { _, _ -> chooseExport(m) }
            .setNegativeButton(tr("Delete")) { _, _ -> confirmDelete(m) }
            .show()
    }

    private var dialog: AlertDialog? = null

    // ---- mileage log ---------------------------------------------------------------------------------------------------

    /** "Business · Client visit · Warszawa – Radom" for the card; an unmarked trip shows as private. */
    private fun logLine(m: TripMeta): String? {
        val l = m.log
        return listOfNotNull(l.kindOrDefault.label, l.purpose, l.route).joinToString("  ·  ")
    }

    /** The trip's mileage-log details in the details dialog, tapped to change them. */
    private fun logBox(m: TripMeta, onEdit: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        background = GradientDrawable().apply { setColor(soft); cornerRadius = ui.dp(12).toFloat() }
        addView(ui.body().apply { text = tr("Mileage log"); textSize = 12f; setTextColor(strong); setTypeface(typeface, Typeface.BOLD) })
        val l = m.log
        val lines = listOfNotNull(
            l.kindOrDefault.label + if (l.kind == null) tr(" (default)") else "",
            l.purpose?.let { tr("Purpose: {0}", it) },
            l.route?.let { tr("Route: {0}", it) },
            l.driver?.let { tr("Driver: {0}", it) },
        )
        addView(ui.body().apply {
            text = lines.joinToString("\n") + "\n" + tr("Tap to change.")
            textSize = 14f; setTextColor(strong)
        })
        isClickable = true
        setOnClickListener { onEdit() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
    }

    /** Business or private, purpose, route and driver of one trip. */
    fun editLog(m: TripMeta) {
        val old = m.log
        var kind: TripKind = old.kindOrDefault
        fun field(hint: String, value: String?) = android.widget.EditText(activity).apply {
            this.hint = hint
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            setText(value ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val purpose = field(tr("e.g. visit to a client"), old.purpose)
        val route = field(tr("e.g. Warszawa – Radom"), old.route)
        val driver = field(tr("Name and surname"), old.driver ?: AppSettings.driver(activity))
        val kinds = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val pills = ArrayList<Pair<TripKind, TextView>>()
        fun showKinds() {
            for ((k, pill) in pills) {
                val on = k == kind
                pill.background = GradientDrawable().apply { setColor(if (on) strong else Colors.card); cornerRadius = ui.dp(20).toFloat() }
                pill.setTextColor(if (on) Color.WHITE else Colors.text)
            }
        }
        for ((i, k) in listOf(TripKind.BUSINESS, TripKind.PRIVATE).withIndex()) {
            val pill = ui.body().apply {
                text = k.label
                gravity = android.view.Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                isClickable = true
                setOnClickListener { kind = k; showKinds() }
            }
            pills += k to pill
            kinds.addView(pill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
        showKinds()
        fun label(text: String) = ui.body().apply { this.text = text; textSize = 13f; setTextColor(Colors.subtle); setPadding(0, ui.dp(12), 0, 0) }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            addView(kinds)
            addView(label(tr("Purpose of the trip")))
            addView(purpose)
            addView(label(tr("Route (from – to)")))
            addView(route)
            addView(label(tr("Driver")))
            addView(driver)
        }
        // The route in words from the start and end of the GPS track (the phone's own geocoder), when it is empty
        val s = m.summary
        val aLat = s.startLat; val aLon = s.startLon; val bLat = s.endLat; val bLon = s.endLon
        if (old.route == null && aLat != null && aLon != null && bLat != null && bLon != null) {
            io.execute {
                val text = Places.route(activity, aLat, aLon, bLat, bLon)
                activity.runOnUiThread { if (text != null && route.text.isEmpty()) route.setText(text) }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(tr("Mileage log"))
            .setView(android.widget.ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("Save")) { _, _ ->
                val log = TripLog(kind, purpose.text.toString(), route.text.toString(), driver.text.toString()).cleaned()
                log.driver?.let { if (AppSettings.driver(activity) == null) AppSettings.setDriver(activity, it) }
                saveLog(m.id, log)
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun saveLog(id: String, log: TripLog) {
        val s = store ?: return
        io.execute {
            val ok = try { s.updateLog(id, log) != null } catch (e: IOException) { false }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (!ok) toast(tr("Could not save the trip.")) else onChanged()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun openMap(m: TripMeta) {
        if (m.summary.startLat == null) {
            toast(tr("This trip has no GPS track to show."))
            return
        }
        activity.startActivity(Intent(activity, MapActivity::class.java).putExtra(MapActivity.EXTRA_TRIP_ID, m.id))
    }

    private fun chooseExport(m: TripMeta) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Share as"))
            .setItems(arrayOf(tr("GPX (route and car values)"), tr("CSV (spreadsheet)"))) { _, which -> export(m, gpx = which == 0) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
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

    private fun export(m: TripMeta, gpx: Boolean) {
        val s = store ?: return
        io.execute {
            val trip: Trip? = s.load(m.id)
            var uri: Uri? = null
            if (trip != null) {
                try {
                    val dir = File(activity.cacheDir, ExportProvider.EXPORT_DIR).apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }           // keep only the file being shared
                    val file = File(dir, "${trip.id}.${if (gpx) "gpx" else "csv"}")
                    file.writeText(if (gpx) Gpx.write(trip) else Csv.write(trip))
                    uri = Uri.parse("content://${activity.packageName}.exports/${file.name}")
                } catch (e: IOException) {
                    uri = null
                }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (uri == null) {
                    toast(tr("Could not prepare the file."))
                } else {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(if (gpx) "application/gpx+xml" else "text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(send, tr("Share trip")))
                }
            }
        }
    }
}
