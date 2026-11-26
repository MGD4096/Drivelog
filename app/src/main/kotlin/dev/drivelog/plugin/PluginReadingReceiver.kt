package dev.drivelog.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.drivelog.AppState
import dev.drivelog.CarWidget
import dev.drivelog.Phase
import dev.drivelog.Stores
import dev.drivelog.carapi.CarValues
import dev.drivelog.core.trip.LateReading
import dev.drivelog.pluginapi.CarPluginContract
import dev.drivelog.pluginapi.CarValuesBundle
import java.io.IOException

/**
 * Values a plugin sends after the trip ([CarPluginContract.ACTION_READING]). Only apps holding the plugin permission
 * (signed like Drivelog) can send it (the manifest), and the plugin must be one [CarPluginRegistry] accepts. The values
 * become the vehicle's latest ones and, when they fit ([LateReading.endOf]), the end values of its newest trip.
 */
class PluginReadingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CarPluginContract.ACTION_READING) return
        val pluginId = intent.getStringExtra(CarPluginContract.EXTRA_PLUGIN_ID) ?: return
        val bundle = intent.getBundleExtra(CarPluginContract.EXTRA_VALUES) ?: return
        val now = System.currentTimeMillis()
        val atMs = intent.getLongExtra(CarPluginContract.EXTRA_AT_MS, now).coerceAtMost(now)
        val pending = goAsync()
        Thread {
            try {
                apply(context.applicationContext, pluginId, CarValuesBundle.fromBundle(bundle).checked(), atMs)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun apply(context: Context, pluginId: String, values: CarValues, atMs: Long) {
        if (CarPluginRegistry.plugins(context).none { it.id == pluginId }) return
        val stores = Stores(context)
        val trips = stores.trips ?: return
        val metas = trips.list()
        val address = LateReading.vehicleFor(pluginId, stores.details.all(), metas) ?: return
        try {
            stores.readings.put(address, values, atMs)
            // the newest trip of this vehicle, unless one is being recorded right now (it gets its values live)
            if (AppState.status.phase == Phase.IDLE) {
                val last = metas.filter { it.carId.equals(address, ignoreCase = true) }.maxByOrNull { it.endMs }
                val trip = last?.let { trips.load(it.id) }
                val updated = trip?.let { LateReading.endOf(it, values, atMs) }
                if (updated != null) trips.save(updated)
            }
        } catch (e: IOException) {
            // nothing else to do: the plugin sends the next reading when the vehicle's values change again
        }
        CarWidget.update(context)
    }
}
