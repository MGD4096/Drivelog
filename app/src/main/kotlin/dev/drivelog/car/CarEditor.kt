package dev.drivelog.car

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.drivelog.Colors
import dev.drivelog.Ui
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarInput
import dev.drivelog.core.car.CarPlugins
import dev.drivelog.core.car.ManualOdometer
import dev.drivelog.core.car.Parsed
import dev.drivelog.core.car.VehicleKind
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.car.Vin
import dev.drivelog.core.storage.CarDetailsStore
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TripMeta
import dev.drivelog.plugin.CarPluginRegistry
import java.io.IOException
import java.util.Calendar

/**
 * The dialogs that change what the person told about a car: "Edit car" (model, year, engine, VIN, plate, where its
 * data comes from) and typing in the odometer of a car without a plugin. [onChanged] redraws the page after a save.
 */
class CarEditor(
    private val activity: Activity,
    private val ui: Ui,
    private val views: CarPageViews,
    private val details: CarDetailsStore,
    private val address: String,
    private val trips: () -> List<TripMeta>,
    private val onChanged: () -> Unit,
) {

    fun editCar(old: CarDetails) {
        val strong = Colors.strong
        val grey = Colors.card

        val modelField = views.field("", old.model, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val yearField = views.field("", old.year?.toString(), InputType.TYPE_CLASS_NUMBER, 4)
        val engineField = views.field("", old.engineCc?.let { "$it cc" }, InputType.TYPE_CLASS_TEXT, 12)
        val plateField = views.field("", old.plate, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 12)
        val vinField = views.field(
            "", old.vin,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 24,
        )

        var pluginOn = old.usesPlugin
        // The plugin apps on the phone, plus the car's current choice if that one is not installed (kept, marked)
        val installedPlugins = CarPluginRegistry.plugins(activity)
        val plugins = CarPlugins.choices(installedPlugins, old.plugin) { tr("{0} (not installed)", it) }
        var pluginIndex = plugins.indexOfFirst { it.id == old.plugin }.coerceAtLeast(0)
        val pluginPicker = ui.body().apply {
            textSize = 16f
            setTextColor(Colors.text)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = views.rounded(grey, 12)
            isClickable = true
        }
        fun showPlugin() { pluginPicker.text = "${plugins[pluginIndex].label}   ▾" }
        showPlugin()
        pluginPicker.setOnClickListener {
            val dialog = AlertDialog.Builder(activity).setTitle(tr("Plugin"))
            if (installedPlugins.isEmpty()) dialog.setMessage(tr("No plugin app is installed. Install Drivelog ID6 (or another plugin) and come back."))
            else dialog.setItems(plugins.map { it.label }.toTypedArray()) { _, which -> pluginIndex = which; showPlugin() }
            dialog.show()
        }
        val odometerField = views.field("", ManualOdometer.shownText(old, VehicleSummary.of(address, trips())), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val odometerAtStart = odometerField.text.toString()
        val pluginBox = views.labelled(tr("Plugin"), EditText(activity).also { it.visibility = View.GONE }, "").apply { addView(pluginPicker) }
        val odometerBox = views.labelled(tr("Odometer (km); trips are added to it automatically"), odometerField, tr("e.g. 154 000"))
        val hint = ui.body().apply { textSize = 12f; setTextColor(Colors.muted); setPadding(0, ui.dp(6), 0, 0) }

        // car or motorcycle: what the car page and the widget draw
        var kind = old.kind
        fun showHint() {
            hint.text = when {
                !pluginOn -> tr("No data from the car: type in the odometer once; every trip you record is added to it.")
                kind == VehicleKind.MOTORCYCLE -> tr("Odometer, fuel and range come from the plugin.")
                else -> tr("Odometer, fuel, doors and windows come from the head-unit plugin.")
            }
        }

        // where the car's data comes from: two big choices instead of a checkbox
        val sourceRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val sourceTiles = ArrayList<Triple<LinearLayout, TextView, TextView>>()
        fun updatePluginFields() {
            for ((i, t) in sourceTiles.withIndex()) {
                val chosen = (i == 0) == pluginOn
                t.first.background = views.rounded(if (chosen) strong else grey, 12)
                t.second.setTextColor(if (chosen) Color.WHITE else Colors.text)
                t.third.setTextColor(if (chosen) Color.argb(220, 255, 255, 255) else Colors.muted)
            }
            pluginBox.visibility = if (pluginOn) View.VISIBLE else View.GONE
            odometerBox.visibility = if (pluginOn) View.GONE else View.VISIBLE
            showHint()
        }
        for ((i, pair) in listOf(tr("Plugin") to tr("Reports odometer, fuel, doors"), tr("No plugin") to tr("Odometer typed in by hand")).withIndex()) {
            val title = ui.body().apply { text = pair.first; textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
            val sub = ui.body().apply { text = pair.second; textSize = 11f }
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                isClickable = true
                addView(title)
                addView(sub)
                setOnClickListener { pluginOn = i == 0; updatePluginFields() }
            }
            sourceTiles += Triple(box, title, sub)
            sourceRow.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) rightMargin = ui.dp(8) })
        }
        val kindRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val kindTiles = ArrayList<Pair<VehicleKind, TextView>>()
        fun updateKind() {
            for ((k, tile) in kindTiles) {
                tile.background = views.rounded(if (k == kind) strong else grey, 12)
                tile.setTextColor(if (k == kind) Color.WHITE else Colors.text)
            }
            showHint()
        }
        for ((i, k) in VehicleKind.values().withIndex()) {
            val tile = ui.body().apply {
                text = if (k == VehicleKind.MOTORCYCLE) tr("Motorcycle") else tr("Car")
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                isClickable = true
                setOnClickListener { kind = k; updateKind() }
            }
            kindTiles += k to tile
            kindRow.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i == 0) rightMargin = ui.dp(8) })
        }
        updatePluginFields()
        updateKind()

        fun below() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(0), ui.dp(20), ui.dp(8))
            // The Bluetooth device is changed from the ⋮ menu, not here
            addView(views.caption(tr("Car")))
            addView(kindRow, below().apply { bottomMargin = ui.dp(6) })
            addView(views.labelled(tr("Make and model"), modelField, tr("e.g. Mazda MX-5 2.0")))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(views.labelled(tr("Year"), yearField, tr("e.g. 2019")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(10) })
                addView(views.labelled(tr("Engine"), engineField, tr("3.0 or 2998 cc")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, below().apply { topMargin = ui.dp(6) })
            addView(views.labelled(tr("VIN (17 characters)"), vinField, tr("optional")), below().apply { topMargin = ui.dp(6) })
            addView(views.labelled(tr("Registration number (for the mileage log)"), plateField, tr("optional")), below().apply { topMargin = ui.dp(6) })
            addView(views.caption(tr("Data source")))
            addView(sourceRow)
            addView(hint)
            addView(pluginBox, below().apply { topMargin = ui.dp(8) })
            addView(odometerBox, below().apply { topMargin = ui.dp(8) })
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Edit car"))
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(tr("Save"), null)          // set below, so that a mistake keeps the dialog open
            .setNegativeButton(tr("Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val year = CarInput.parseYear(yearField.text.toString(), Calendar.getInstance().get(Calendar.YEAR))
            val engine = CarInput.parseEngineCc(engineField.text.toString())
            val vin = Vin.parse(vinField.text.toString())
            yearField.error = year.error
            engineField.error = engine.error
            vinField.error = vin.error
            val odometer = if (pluginOn) Parsed<Double>(null) else CarInput.parseOdometerKm(odometerField.text.toString())
            odometerField.error = odometer.error
            if (!year.ok || !engine.ok || !vin.ok || !odometer.ok) return@setOnClickListener

            try {
                // The photos are managed in the gallery: keep them as they are now (they may have changed while this was open)
                var updated = details.get(address).copy(
                    vin = vin.value, model = modelField.text.toString().trim().ifEmpty { null }, year = year.value, engineCc = engine.value,
                    usesPlugin = pluginOn,
                    kind = kind,
                    plate = plateField.text.toString().trim().uppercase().ifEmpty { null },
                    plugin = if (pluginOn) plugins[pluginIndex.coerceIn(0, plugins.size - 1)].id else old.plugin,
                )
                // The typed reading counts as entered now when it was changed here; with a plugin it is left alone
                if (!pluginOn) updated = ManualOdometer.afterEdit(updated, odometerAtStart, odometerField.text.toString(), odometer.value, System.currentTimeMillis())
                details.put(address, updated)
            } catch (e: IOException) {
                ui.toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            onChanged()
        }
    }

    /** For a car without a plugin: type in the odometer reading now. */
    fun editOdometer(d: CarDetails) {
        val input = views.field(tr("Odometer in km"), ManualOdometer.shownText(d, VehicleSummary.of(address, trips())), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 9)
        val shownAtStart = input.text.toString()
        val box = LinearLayout(activity).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(tr("Odometer"))
            .setView(box)
            .setPositiveButton(tr("Save"), null)
            .setNegativeButton(tr("Cancel"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val km = CarInput.parseOdometerKm(input.text.toString())
            input.error = km.error
            if (!km.ok) return@setOnClickListener
            try {
                details.put(address, ManualOdometer.afterEdit(details.get(address), shownAtStart, input.text.toString(), km.value, System.currentTimeMillis()))
            } catch (e: IOException) {
                ui.toast(tr("Could not save: {0}", e.message))
                return@setOnClickListener
            }
            dialog.dismiss()
            onChanged()
        }
    }
}
