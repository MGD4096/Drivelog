package dev.drivelog

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/** Tells when the car's Bluetooth connection drops and comes back while a trip runs. Main thread only. */
class CarConnectionWatch(
    private val context: Context,
    private val address: () -> String,
    private val onDisconnected: () -> Unit,
    private val onReconnected: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = bluetoothDeviceFrom(intent)?.address ?: return
            if (!device.equals(address(), ignoreCase = true)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected()
                BluetoothDevice.ACTION_ACL_CONNECTED -> onReconnected()
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { /* already gone */ }
        registered = false
    }
}
