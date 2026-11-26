package dev.drivelog.car

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.drivelog.AppSettings
import dev.drivelog.Colors
import dev.drivelog.Ui
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.DetectedRefuel
import dev.drivelog.core.car.EventCosts
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.car.FuelLog
import dev.drivelog.core.storage.FuelStore
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripMeta
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The fuel part of the car page: refuellings the car's tank level suggests (only the price is left to type), the
 * consumption from one full tank to the next, the price of fuel and what a kilometre costs, and the list of entries.
 */
class FuelPanel(private val activity: Activity, private val ui: Ui, private val store: FuelStore) {

    /** [trips] are this car's, [events] its service log; [onChanged] reloads the page after a change. */
    fun build(carId: String, trips: List<TripMeta>, entries: List<FuelEntry>, events: List<CarEvent>, year: Int, onChanged: () -> Unit): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val since = System.currentTimeMillis() - FuelLog.SUGGEST_DAYS * 86_400_000L
        for (d in FuelLog.detect(carId, trips, entries, AppSettings.dismissedRefuels(activity), since).take(3)) {
            box.addView(suggestion(carId, d, onChanged), params(6))
        }

        val zone = ZoneId.systemDefault()
        fun inYear(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).year == year
        val intervals = FuelLog.intervals(entries)
        val fuelCost = FuelLog.totalCost(entries, year)
        val serviceCost = EventCosts.total(events, year)
        val km = trips.filter { inYear(it.startMs) }.sumOf { it.summary.distanceKm }
        val latestPrice = entries.firstOrNull { it.pricePerLiter != null }?.pricePerLiter

        val tiles = listOf(
            Triple(tr("Consumption (tank to tank)"), FuelLog.average(intervals)?.let { "${ui.f1(it)} L/100 km" } ?: "—",
                if (intervals.isEmpty()) tr("needs two full fills with the odometer") else tr("{0} km", ui.km(intervals.sumOf { it.km }))),
            Triple(tr("Fuel price"), latestPrice?.let { ui.money(it) + "/L" } ?: "—", tr("latest refuelling")),
            Triple(tr("Fuel in {0}", year), if (fuelCost > 0) ui.money(fuelCost) else "—", tr("{0} km driven", ui.km(km))),
            Triple(tr("Cost per km in {0}", year), FuelLog.perKm(fuelCost + serviceCost, km)?.let { ui.money(it) } ?: "—",
                tr("fuel {0} + service {1}", FuelLog.perKm(fuelCost, km)?.let { ui.money(it) } ?: "—", FuelLog.perKm(serviceCost, km)?.let { ui.money(it) } ?: "—")),
        )
        for (row in tiles.chunked(2)) {
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, (caption, value, note) ->
                line.addView(tile(caption, value, note), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i == 0) rightMargin = ui.dp(4) else leftMargin = ui.dp(4)
                })
            }
            box.addView(line, params(8))
        }

        if (entries.isEmpty()) {
            box.addView(ui.body().apply {
                text = tr("No refuellings yet. They are suggested here when the car reports a fuller tank; add the price to them.")
                setTextColor(Colors.muted); setPadding(0, ui.dp(8), 0, 0)
            })
        }
        for (e in entries.take(SHOWN)) box.addView(entryRow(carId, e, onChanged), params(8))
        if (entries.size > SHOWN) {
            box.addView(ui.body().apply {
                text = tr("{0} more, older", entries.size - SHOWN); textSize = 13f; setTextColor(Colors.muted); setPadding(0, ui.dp(6), 0, 0)
            })
        }
        return box
    }

    private fun params(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    private fun tile(caption: String, value: String, note: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(14).toFloat() }
        addView(ui.body().apply { text = caption; textSize = 12f; setTextColor(Colors.muted) })
        addView(ui.body().apply { text = value; textSize = 18f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        addView(ui.body().apply { text = note; textSize = 11f; setTextColor(Colors.subtle) })
    }

    private fun suggestion(carId: String, d: DetectedRefuel, onChanged: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = GradientDrawable().apply { setColor(Colors.soft); cornerRadius = ui.dp(14).toFloat() }
        addView(ui.body().apply {
            text = tr("Refuelled about {0} L on {1}?", ui.f0(d.liters), ui.date(d.atMs))
            setTextColor(Colors.strong); setTypeface(typeface, Typeface.BOLD)
        })
        addView(ui.body().apply { text = tr("The car reported a fuller tank. Add the price to log it."); textSize = 13f; setTextColor(Colors.strong) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.pill(tr("Add"), filled = true, heightDp = 38) { edit(carId, null, d, d.odometerKm, onChanged) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
            addView(ui.pill(tr("Not a refuelling"), heightDp = 38) { AppSettings.dismissRefuel(activity, d.key); onChanged() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
        })
    }

    private fun entryRow(carId: String, e: FuelEntry, onChanged: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(14).toFloat() }
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.body().apply {
                text = "${ui.f1(e.liters)} L" + (e.pricePerLiter?.let { "  ·  ${ui.money(it)}/L" } ?: "") + if (!e.fullTank) "  ·  " + tr("partial") else ""
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(ui.body().apply {
                text = listOfNotNull(ui.date(e.atMs), e.odometerKm?.let { tr("{0} km", ui.km(it)) }, e.note).joinToString("  ·  ")
                textSize = 13f; setTextColor(Colors.subtle)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        e.cost?.let { addView(ui.body().apply { text = ui.money(it); setTypeface(typeface, Typeface.BOLD) }) }
        isClickable = true
        setOnClickListener { edit(carId, e, null, e.odometerKm, onChanged) }
    }

    private fun number(text: String): Double? = text.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

    /** Adds ([existing] null) or changes a refuelling; [detected] fills in what the car reported. */
    fun edit(carId: String, existing: FuelEntry?, detected: DetectedRefuel?, odometer: Double?, onSaved: () -> Unit) {
        val zone = ZoneId.systemDefault()
        var date = Instant.ofEpochMilli(existing?.atMs ?: detected?.atMs ?: System.currentTimeMillis()).atZone(zone).toLocalDate()
        fun field(value: String?, hint: String) = EditText(activity).apply {
            setText(value ?: "")
            this.hint = hint
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val liters = field((existing?.liters ?: detected?.liters)?.let { ui.f1(it) }, tr("litres"))
        val cost = field(existing?.cost?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) }, tr("total paid ({0})", ui.currencySymbol()))
        val km = field((existing?.odometerKm ?: odometer)?.let { ui.f0(it) }, tr("odometer, km"))
        val note = EditText(activity).apply { setText(existing?.note ?: ""); hint = tr("station, fuel type (optional)"); setSingleLine() }
        val full = CheckBox(activity).apply { text = tr("Filled up to the top"); isChecked = existing?.fullTank ?: true }
        val price = ui.body().apply { textSize = 13f; setTextColor(Colors.subtle) }
        fun showPrice() {
            val l = number(liters.text.toString())
            val c = number(cost.text.toString())
            price.text = if (l != null && c != null && l > 0) tr("Price: {0} per litre", ui.money(c / l)) else ""
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = showPrice()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        liters.addTextChangedListener(watcher)
        cost.addTextChangedListener(watcher)
        showPrice()
        val dateButton = ui.pill(ui.date(date), heightDp = 40) {}
        dateButton.setOnClickListener {
            DatePickerDialog(activity, { _, y, m, d -> date = LocalDate.of(y, m + 1, d); dateButton.text = ui.date(date) },
                date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        fun label(t: String) = ui.body().apply { text = t; textSize = 13f; setTextColor(Colors.subtle); setPadding(0, ui.dp(10), 0, 0) }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
            addView(dateButton)
            addView(label(tr("Litres")))
            addView(liters)
            addView(label(tr("Total paid")))
            addView(cost)
            addView(price)
            addView(label(tr("Odometer (km)")))
            addView(km)
            addView(full)
            addView(note)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) tr("Refuelling") else tr("Edit refuelling"))
            .setView(android.widget.ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("Save"), null)
            .setNegativeButton(tr("Cancel"), null)
        if (existing != null) builder.setNeutralButton(tr("Delete")) { _, _ ->
            try { store.delete(carId, existing.id) } catch (e: IOException) { toast(tr("Could not save: {0}", e.message)) }
            onSaved()
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val l = number(liters.text.toString())
            if (l == null || l <= 0 || l >= 1000) { liters.error = tr("Enter the litres."); return@setOnClickListener }
            val c = cost.text.toString().takeIf { it.isNotBlank() }?.let { number(it) }
            if (cost.text.isNotBlank() && (c == null || c < 0)) { cost.error = tr("Enter an amount, e.g. 245.80"); return@setOnClickListener }
            val o = km.text.toString().takeIf { it.isNotBlank() }?.let { number(it) }
            if (km.text.isNotBlank() && (o == null || o < 0)) { km.error = tr("Enter the odometer in km."); return@setOnClickListener }
            // the day picked, at the time of the original (or of the detected refuelling, or now)
            val oldTime = Instant.ofEpochMilli(existing?.atMs ?: detected?.atMs ?: System.currentTimeMillis()).atZone(zone).toLocalTime()
            val entry = FuelEntry(
                id = existing?.id ?: FuelEntry.newId(),
                atMs = date.atTime(oldTime).atZone(zone).toInstant().toEpochMilli(),
                liters = l, cost = c, odometerKm = o, fullTank = full.isChecked,
                note = note.text.toString().trim().ifEmpty { null },
                detectedKey = existing?.detectedKey ?: detected?.key,
            )
            try {
                store.save(carId, entry)
            } catch (e: IOException) {
                toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            onSaved()
        }
    }

    private fun toast(t: String) = Toast.makeText(activity, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val SHOWN = 5
    }
}
