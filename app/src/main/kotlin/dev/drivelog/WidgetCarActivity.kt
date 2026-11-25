package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import dev.drivelog.core.FileCarDetailsStore
import dev.drivelog.core.tr
import java.io.File

/**
 * Asks which car a home-screen widget shows, when the widget is placed (and again from the launcher's "Widget
 * settings", where it offers that). The choice is kept per widget ([CarWidget.setCar]); "the car that reported last"
 * keeps the old behaviour. A transparent window with just the list on it.
 */
class WidgetCarActivity : Activity() {

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
        val details = FileCarDetailsStore(File(filesDir, "cars.json"))
        val cars = CarWidget.cars(this)
        val names = listOf(tr("The car that reported last")) + cars.map { details.get(it.address).model?.takeIf { m -> m.isNotBlank() } ?: it.name }
        val current = CarWidget.carOf(this, id)
        val checked = cars.indexOfFirst { it.address.equals(current, ignoreCase = true) } + 1
        val theme = if (Colors.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(this, theme)
            .setTitle(tr("Which car should the widget show?"))
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, i ->
                CarWidget.setCar(this, id, if (i == 0) null else cars[i - 1].address)
                CarWidget.update(this)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                dialog.dismiss()
            }
            .setNegativeButton(tr("Cancel"), null)
            .setOnDismissListener { finish() }
            .show()
    }
}
