package dev.idrivetrips

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts a trip when the phone connects to one of the chosen cars over Bluetooth. The disconnect (and a quick
 * reconnect) is handled inside the service while it runs.
 *
 * Android does not list Bluetooth among the exemptions that allow starting a foreground service from the
 * background. It works when the user has set battery use to "Unrestricted" for this app; if Android refuses anyway,
 * a notification is shown and one tap starts the trip.
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val address = bluetoothDeviceFrom(intent)?.address ?: return
        val car = Prefs.cars(context).firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return
        // An archived car is out of use: no trip is started for it
        if (try { dev.idrivetrips.core.FileCarDetailsStore(java.io.File(context.filesDir, "cars.json")).get(car.address).archived } catch (e: Exception) { false }) return
        // Already running (for example a reconnect during the grace period): the service sees that itself.
        if (AppState.status.phase != Phase.IDLE) return
        try {
            TripService.start(context, car.address, car.name)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+, or the app is not allowed to run
            Notifications.tapToStart(context, car)
        }
    }
}
