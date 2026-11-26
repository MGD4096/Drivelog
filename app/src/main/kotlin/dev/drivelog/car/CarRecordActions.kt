package dev.drivelog.car

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import dev.drivelog.AppState
import dev.drivelog.CarRef
import dev.drivelog.MainActivity
import dev.drivelog.Phase
import dev.drivelog.Prefs
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.storage.PerCarData
import dev.drivelog.core.text.tr
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * What the ⋮ menu of the car page does to the car's record as a whole: archive or restore it, point it at another
 * Bluetooth device (everything recorded moves along) and remove it. The page reacts through [onChanged] (redraw),
 * [onMoved] (open the page of the new address) and [onRemoved] (close).
 */
class CarRecordActions(
    private val activity: Activity,
    private val ui: Ui,
    private val stores: Stores,
    private val car: CarRef,
    private val io: ExecutorService,
    private val onChanged: () -> Unit,
    private val onMoved: (CarRef) -> Unit,
    private val onRemoved: () -> Unit,
) {
    /** Everything kept per car besides the trips; moved or removed together. */
    private val perCar: List<PerCarData> get() = listOf(stores.details, stores.events, stores.readings, stores.fuel)

    fun toggleArchive(d: CarDetails) {
        try {
            stores.details.put(car.address, stores.details.get(car.address).copy(archived = !d.archived))
            ui.toast(if (d.archived) tr("Car restored from the archive.") else tr("Car archived."))
        } catch (e: IOException) {
            ui.toast(tr("Could not save: {0}", e.message))
        }
        onChanged()
    }

    /** Points this car at another paired Bluetooth device (a new radio, a new phone pairing). */
    fun changeBluetoothDevice() {
        if (AppState.status.phase != Phase.IDLE && AppState.status.carName == car.name) {
            ui.toast(tr("A trip is being recorded for this car. Change the device after it ends."))
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), MainActivity.REQ_BASIC)
            ui.toast(tr("Allow Bluetooth access, then try again."))
            return
        }
        val taken = Prefs.cars(activity).map { it.address.lowercase() }.toSet()
        val devices = try {
            activity.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices?.toList()
        } catch (e: SecurityException) {
            null
        }?.filter { it.address.lowercase() !in taken }.orEmpty()
        if (devices.isEmpty()) {
            ui.toast(tr("No other paired devices found. Pair the new device in Android's Bluetooth settings first."))
            return
        }
        val labels = devices.map { d -> (try { d.name } catch (e: SecurityException) { null }) ?: d.address }
        AlertDialog.Builder(activity)
            .setTitle(tr("Which device is this car now?"))
            .setItems(labels.toTypedArray()) { _, i -> confirmDeviceChange(CarRef(devices[i].address, labels[i])) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun confirmDeviceChange(target: CarRef) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Use {0} for this car?", target.name))
            .setMessage(tr("The trips, photos and service log stay with the car. From now on a trip starts when the phone connects to {0}, not to {1}.", target.name, car.name))
            .setPositiveButton(tr("Change")) { _, _ -> moveTo(target) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun moveTo(target: CarRef) {
        val progress = AlertDialog.Builder(activity).setMessage(tr("Moving the history…")).setCancelable(false).show()
        io.execute {
            var error: String? = null
            val trips = stores.trips
            try {
                if (trips == null) throw IOException("Cannot open the trips folder")
                trips.reassignCar(car.address, target.address, target.name)
                for (data in perCar) data.moveCar(car.address, target.address)
                Prefs.replaceCar(activity, car.address, target)
            } catch (e: IOException) {
                error = e.message ?: e.toString()
                try { trips?.reassignCar(target.address, car.address, car.name) } catch (x: IOException) { /* best effort */ }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                progress.dismiss()
                if (error != null) {
                    ui.toast(tr("Could not change the device: {0}", error))
                } else {
                    ui.toast(tr("Bluetooth device changed."))
                    onMoved(target)
                }
            }
        }
    }

    fun confirmRemove(d: CarDetails) {
        AlertDialog.Builder(activity)
            .setTitle(tr("Remove {0}?", car.name))
            .setMessage(
                tr("Trips already recorded stay in the Trips tab. The photos, the details and the service log of this car are deleted, and trips will no longer start when the phone connects to it.")
            )
            .setPositiveButton(tr("Remove")) { _, _ ->
                Prefs.removeCar(activity, car.address)
                d.allPhotos.forEach { PhotoStore.delete(activity, it) }
                for (data in perCar) {
                    try { data.removeCar(car.address) } catch (e: IOException) { /* the entry stays unused */ }
                }
                onRemoved()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }
}
