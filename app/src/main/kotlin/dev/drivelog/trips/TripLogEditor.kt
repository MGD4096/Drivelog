package dev.drivelog.trips

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.drivelog.AppSettings
import dev.drivelog.Colors
import dev.drivelog.Ui
import dev.drivelog.core.storage.TripStore
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripKind
import dev.drivelog.core.trip.TripLog
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.map.Places
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * The mileage-log details of one trip (business or private, purpose, route, driver): the line on its card, the box in
 * its details, and the dialog that changes them. [onChanged] runs after a save.
 */
class TripLogEditor(
    private val activity: Activity,
    private val ui: Ui,
    private val store: TripStore?,
    private val io: ExecutorService,
    private val onChanged: () -> Unit,
) {
    /** "Business · Client visit · Warszawa – Radom" for the card; an unmarked trip shows as private. */
    fun line(m: TripMeta): String {
        val l = m.log
        return listOfNotNull(l.kindOrDefault.label, l.purpose, l.route).joinToString("  ·  ")
    }

    /** The details in the trip's dialog, tapped to change them. */
    fun box(m: TripMeta, onEdit: () -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        background = GradientDrawable().apply { setColor(Colors.soft); cornerRadius = ui.dp(12).toFloat() }
        addView(ui.body().apply { text = tr("Mileage log"); textSize = 12f; setTextColor(Colors.strong); setTypeface(typeface, Typeface.BOLD) })
        val l = m.log
        val lines = listOfNotNull(
            l.kindOrDefault.label + if (l.kind == null) tr(" (default)") else "",
            l.purpose?.let { tr("Purpose: {0}", it) },
            l.route?.let { tr("Route: {0}", it) },
            l.driver?.let { tr("Driver: {0}", it) },
        )
        addView(ui.body().apply {
            text = lines.joinToString("\n") + "\n" + tr("Tap to change.")
            textSize = 14f; setTextColor(Colors.strong)
        })
        isClickable = true
        setOnClickListener { onEdit() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
    }

    fun edit(m: TripMeta) {
        val old = m.log
        var kind: TripKind = old.kindOrDefault
        val strong = Colors.strong
        fun field(hint: String, value: String?) = EditText(activity).apply {
            this.hint = hint
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(200))
            setText(value ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
        for ((i, k) in TripKind.values().withIndex()) {
            val pill = ui.body().apply {
                text = k.label
                gravity = Gravity.CENTER
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
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("Save")) { _, _ ->
                val log = TripLog(kind, purpose.text.toString(), route.text.toString(), driver.text.toString()).cleaned()
                log.driver?.let { if (AppSettings.driver(activity) == null) AppSettings.setDriver(activity, it) }
                save(m.id, log)
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun save(id: String, log: TripLog) {
        val s = store ?: return
        io.execute {
            val ok = try { s.updateLog(id, log) != null } catch (e: IOException) { false }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (!ok) ui.toast(tr("Could not save the trip.")) else onChanged()
            }
        }
    }
}
