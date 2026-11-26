package dev.drivelog.car

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.drivelog.Colors
import dev.drivelog.R
import dev.drivelog.Ui
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.CarInput
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.ManualOdometer
import dev.drivelog.core.car.Parsed
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.storage.CarDetailsStore
import dev.drivelog.core.storage.CarEventsStore
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripMeta
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/**
 * The service log of one car: its entries as cards, and the dialogs to add, change and delete one. Saving an entry
 * with a higher odometer also raises the typed odometer of a car without a plugin. [onChanged] redraws the page.
 */
class ServiceLogPanel(
    private val activity: Activity,
    private val ui: Ui,
    private val views: CarPageViews,
    private val details: CarDetailsStore,
    private val events: CarEventsStore,
    private val address: String,
    private val trips: () -> List<TripMeta>,
    private val onChanged: () -> Unit,
) {

    /** One entry as a card: the icon of its kind, what and when, and the cost on the right; tapping it edits it. */
    fun row(e: CarEvent, odometer: Double?): View {
        val strong = Colors.strong
        val icon = ImageView(activity).apply {
            setImageResource(icon(e.type))
            setColorFilter(strong)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Colors.soft) }
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.body().apply { text = e.displayTitle; setTypeface(typeface, Typeface.BOLD) })
            val whenParts = ArrayList<String>()
            whenParts += ui.date(e.date)
            e.odometerKm?.let { whenParts += "${ui.km(it)} km" }
            addView(ui.body().apply { text = whenParts.joinToString("  ·  "); setTextColor(Colors.subtle); textSize = 13f })
            val next = ArrayList<String>()
            e.dueDate?.let { next += (if (e.type == EventType.OIL_CHANGE) tr("next by ") else tr("valid until ")) + ui.date(it) }
            e.nextAtKm?.let { next += tr("next at {0} km", ui.km(it)) }
            if (next.isNotEmpty()) addView(ui.body().apply { text = next.joinToString("  ·  "); setTextColor(strong); textSize = 13f })
            e.note?.let { addView(ui.body().apply { text = it; setTextColor(Colors.muted); textSize = 13f }) }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = views.rounded(Colors.card, 14)
            addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(12) })
            e.cost?.let { cost ->
                addView(ui.body().apply { text = ui.money(cost); setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
            }
            isClickable = true
            setOnClickListener { edit(e, odometer) }
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
        return row
    }

    /** The dialog for a new entry ([existing] null) or a change to one. */
    fun edit(existing: CarEvent?, odometer: Double?) {
        var type = existing?.type ?: EventType.OIL_CHANGE
        var date = existing?.date ?: LocalDate.now()
        var due: LocalDate? = existing?.dueDate ?: if (existing == null && type.hasDue) date.plusYears(1) else null
        var dueTouched = existing != null

        val strong = Colors.strong
        val grey = Colors.card
        /** A grey tile showing a small caption and a value; tapping it runs [onTap]. */
        fun tile(onTap: () -> Unit): Triple<LinearLayout, TextView, TextView> {
            val cap = ui.body().apply { textSize = 12f; setTextColor(Colors.muted) }
            val value = ui.body().apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                background = views.rounded(grey, 12)
                isClickable = true
                setOnClickListener { onTap() }
                addView(cap)
                addView(value)
            }
            return Triple(box, cap, value)
        }

        // what kind of entry: one tile per type, the chosen one filled in blue
        val typeIcons = HashMap<EventType, Pair<LinearLayout, Pair<ImageView, TextView>>>()
        val shortNames = mapOf(
            EventType.OIL_CHANGE to tr("Oil"), EventType.INSPECTION to tr("Inspection"), EventType.INSURANCE to tr("Insurance"),
            EventType.REPAIR to tr("Repair"), EventType.OTHER to tr("Other"),
        )
        val typeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val titleField = views.field("", existing?.title, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 80)
        val nextKmField = views.field("", existing?.nextAtKm?.let { String.format(Locale.ROOT, "%.0f", it) }, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val odoField = views.field(
            "", (existing?.odometerKm ?: if (existing == null) odometer else null)?.let { String.format(Locale.ROOT, "%.0f", it) },
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9,
        )
        val costField = views.field("", existing?.cost?.let { String.format(Locale.ROOT, "%.2f", it) }, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 12)
        val noteField = views.field("", existing?.note, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 200)

        var changed: () -> Unit = {}   // set to the redraw once everything it touches exists
        val (dateBox, _, dateValue) = tile { pickDate(date) { date = it; if (!dueTouched && type.hasDue) due = date.plusYears(1); changed() } }
        val (dueBox, dueCaption, dueValue) = tile { pickDate(due ?: date.plusYears(1)) { due = it; dueTouched = true; changed() } }
        val dueClear = ui.body().apply {
            text = "✕"
            textSize = 18f
            setTextColor(Colors.muted)
            gravity = Gravity.CENTER
            contentDescription = tr("Clear the date")
            isClickable = true
            setOnClickListener { due = null; dueTouched = true; changed() }
        }
        val dueRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dueBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dueClear, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        val titleBox = views.labelled("", titleField, "")
        val nextKmBox = views.labelled(tr("Next change at (km)"), nextKmField, tr("e.g. 215 000"))

        fun dueLabel() = if (type == EventType.OIL_CHANGE) tr("Next change by") else tr("Valid until")

        fun refresh() {
            for ((t, parts) in typeIcons) {
                val (box, iconAndText) = parts
                val chosen = t == type
                box.background = views.rounded(if (chosen) strong else grey, 12)
                iconAndText.first.setColorFilter(if (chosen) Color.WHITE else strong)
                iconAndText.second.setTextColor(if (chosen) Color.WHITE else Colors.subtle)
            }
            (titleBox.getChildAt(0) as TextView).text = if (type.hasDue) tr("Details (optional)") else tr("What was done")
            titleField.hint = if (type.hasDue) tr("e.g. oil type, insurer") else tr("e.g. new brake pads (required)")
            dateValue.text = ui.date(date)
            (dateBox.getChildAt(0) as TextView).text = if (type.hasDue) tr("Date done or bought") else tr("Date")
            dueRow.visibility = if (type.hasDue) View.VISIBLE else View.GONE
            dueCaption.text = dueLabel()
            dueValue.text = due?.let { ui.date(it) } ?: tr("Not set")
            nextKmBox.visibility = if (type == EventType.OIL_CHANGE) View.VISIBLE else View.GONE
        }
        fun defaultDue() { if (!dueTouched && type.hasDue) due = date.plusYears(1) }

        for (t in EventType.values()) {
            val icon = ImageView(activity).apply { setImageResource(icon(t)); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val name = ui.body().apply { text = shortNames[t]; textSize = 10f; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(6))
                isClickable = true
                contentDescription = t.label
                addView(icon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { bottomMargin = ui.dp(3) })
                addView(name)
                setOnClickListener { type = t; defaultDue(); refresh() }
            }
            typeIcons[t] = box to (icon to name)
            typeRow.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (t != EventType.OTHER) rightMargin = ui.dp(4) })
        }
        changed = { refresh() }
        refresh()

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val odoBox = views.labelled(if (details.get(address).usesPlugin) tr("Odometer (km)") else tr("Odometer (km) – updates the car"), odoField, tr("km"))
            val costBox = views.labelled(tr("Cost ({0})", ui.currencySymbol()), costField, "0.00")
            addView(odoBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(10) })
            addView(costBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(0), ui.dp(20), ui.dp(8))
            addView(views.caption(tr("Type")))
            addView(typeRow)
            addView(views.caption(tr("When")))
            addView(dateBox)
            addView(dueRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(views.caption(tr("Details")))
            addView(titleBox)
            addView(nextKmBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(views.caption(tr("Odometer and cost")))
            addView(numbers)
            addView(views.caption(tr("Note")))
            addView(noteField.also { it.hint = tr("Garage, parts, … (optional)") })
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) tr("New service entry") else tr("Edit entry"))
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("Save"), null)
            .setNegativeButton(tr("Cancel"), null)
        if (existing != null) builder.setNeutralButton(tr("Delete")) { _, _ -> confirmDelete(existing) }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = titleField.text.toString().trim().ifEmpty { null }
            val odo: Parsed<Double> = CarInput.parseDecimal(odoField.text.toString(), tr("Odometer"), 0.0, 2_000_000.0)
            val next: Parsed<Double> = if (type == EventType.OIL_CHANGE) CarInput.parseDecimal(nextKmField.text.toString(), tr("Next change"), 0.0, 2_000_000.0) else Parsed(null)
            val cost: Parsed<Double> = CarInput.parseDecimal(costField.text.toString(), tr("Cost"), 0.0, 10_000_000.0)
            titleField.error = if (!type.hasDue && title == null) tr("Describe what was done") else null
            odoField.error = odo.error
            nextKmField.error = next.error
            costField.error = cost.error
            if (titleField.error != null || !odo.ok || !next.ok || !cost.ok) return@setOnClickListener
            val dueSaved = if (type.hasDue) due else null
            if (dueSaved != null && dueSaved.isBefore(date)) {
                ui.toast(tr("The \"{0}\" date is before the date of the entry.", dueLabel()))
                return@setOnClickListener
            }
            val note = noteField.text.toString().trim().ifEmpty { null }
            try {
                events.save(address, CarEvent(existing?.id ?: CarEvent.newId(), type, date, title, dueSaved, odo.value, next.value, cost.value, note))
                val car = details.get(address)
                val raised = ManualOdometer.afterServiceEntry(car, VehicleSummary.of(address, trips()), odo.value, System.currentTimeMillis())
                if (raised != car) details.put(address, raised)
            } catch (e: IOException) {
                ui.toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            onChanged()
        }
    }

    private fun confirmDelete(e: CarEvent) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Delete this entry?"))
            .setMessage("${ui.date(e.date)}  ·  ${e.displayTitle}")
            .setPositiveButton(tr("Delete")) { _, _ ->
                try { events.delete(address, e.id) } catch (x: IOException) { ui.toast(tr("Could not delete: {0}", x.message)) }
                onChanged()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun pickDate(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(activity, { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }

    companion object {
        /** The icon of each kind of entry. A new [EventType] fails to compile here until it has one. */
        fun icon(type: EventType): Int = when (type) {
            EventType.OIL_CHANGE -> R.drawable.ic_service_oil
            EventType.INSPECTION -> R.drawable.ic_service_inspection
            EventType.INSURANCE -> R.drawable.ic_service_insurance
            EventType.REPAIR -> R.drawable.ic_service_repair
            EventType.OTHER -> R.drawable.ic_service_other
        }
    }
}
