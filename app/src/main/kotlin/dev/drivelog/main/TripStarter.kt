package dev.drivelog.main

import android.app.Activity
import android.app.AlertDialog
import dev.drivelog.MainActivity
import dev.drivelog.Prefs
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.core.text.tr
import dev.drivelog.recording.TripService

/** Starts recording by hand, for a car picked from the list (or "another car"), or for the car a notification names. */
class TripStarter(private val activity: Activity, private val ui: Ui, private val stores: Stores) {

    /** "Start trip now": the trip is recorded for the car picked here, so it counts in that car's trips and odometer. */
    fun startManual() {
        val details = stores.details
        val cars = Prefs.cars(activity).filter { !details.get(it.address).archived }
        if (cars.isEmpty()) {
            start(MainActivity.MANUAL_ID, tr("Manual trip"))
            return
        }
        val names = cars.map { c -> details.get(c.address).model?.let { m -> "$m  (${c.name})" } ?: c.name } + tr("Other car (not on the list)")
        AlertDialog.Builder(activity)
            .setTitle(tr("Record a trip in which car?"))
            .setItems(names.toTypedArray()) { _, i ->
                if (i < cars.size) start(cars[i].address, cars[i].name) else start(MainActivity.MANUAL_ID, tr("Manual trip"))
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    fun start(address: String, name: String) {
        try {
            TripService.start(activity, address, name)
        } catch (e: IllegalStateException) {
            ui.toast(tr("Could not start: {0}", e.message))
        } catch (e: SecurityException) {
            ui.toast(tr("Could not start: {0}", e.message))
        }
    }
}
