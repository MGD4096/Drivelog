package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.drivelog.core.text.tr

/**
 * Asks which vehicles the several-vehicles widget ([CarsWidget]) shows, up to [CarsWidget.MAX], when it is placed (and
 * again from the launcher's "Widget settings"). Ticked in the order of the list; a transparent window with the list.
 */
class WidgetCarsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // backing out leaves no widget behind
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        Colors.init(this)
        AppSettings.applyLanguage(this)
        val stores = Stores(this)
        val cars = CarWidget.cars(this)
        val current = CarsWidget.carsOf(this, id)
        val checked = BooleanArray(cars.size) { i ->
            if (current == null) i < CarsWidget.MAX else current.any { it.equals(cars[i].address, ignoreCase = true) }
        }
        val theme = if (Colors.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        val dialog = AlertDialog.Builder(this, theme)
            .setTitle(tr("Which vehicles should the widget show? (up to {0})", CarsWidget.MAX))
            .setMultiChoiceItems(cars.map { stores.carName(it.address, it.name) }.toTypedArray(), checked) { d, which, on ->
                if (on && checked.count { it } > CarsWidget.MAX) {
                    checked[which] = false
                    (d as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(this, tr("At most {0} vehicles.", CarsWidget.MAX), Toast.LENGTH_SHORT).show()
                } else {
                    checked[which] = on
                }
            }
            .setPositiveButton(tr("Save")) { _, _ ->
                CarsWidget.setCars(this, id, cars.filterIndexed { i, _ -> checked[i] }.map { it.address })
                CarsWidget.update(this)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            }
            .setNegativeButton(tr("Cancel"), null)
            .setOnDismissListener { finish() }
            .create()
        dialog.show()
    }
}
