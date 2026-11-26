package dev.drivelog.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.drivelog.AppSettings
import dev.drivelog.Prefs
import dev.drivelog.Stores
import dev.drivelog.core.car.Reminders
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.export.ExportState
import dev.drivelog.core.export.ExportVehicle
import dev.drivelog.pluginapi.CarPluginContract
import dev.drivelog.reminders.ReminderCheck
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * Hands the state of the vehicles to the export plugins ([CarPluginContract.ACTION_EXPORT]: a home server and the
 * like): the latest values, where each was parked, the services coming up. Only plugins whose receiver is protected by
 * the plugin permission and that are signed like Drivelog get it (as [CarPluginRegistry] does for car plugins).
 * [publish] is called with every widget update (new values, a trip, a service entry …) and sends at most once every
 * [MIN_INTERVAL_MS]; what changes in between goes out with the next send.
 */
object Exports {
    private const val MIN_INTERVAL_MS = 60_000L
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "exports").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var lastSentMs = 0L
    private var pending = false

    /** The installed export plugins' receivers that may get the data. */
    fun installed(context: Context): List<ComponentName> {
        val pm = context.packageManager
        val found = try {
            pm.queryBroadcastReceivers(Intent(CarPluginContract.ACTION_EXPORT), 0)
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return found.mapNotNull { it.activityInfo }
            .filter { it.permission == CarPluginContract.PERMISSION }
            .filter { pm.checkSignatures(context.packageName, it.packageName) == PackageManager.SIGNATURE_MATCH }
            .map { ComponentName(it.packageName, it.name) }
    }

    /** Sends the state now, or once [MIN_INTERVAL_MS] has passed since the last send. Safe from any thread. */
    @Synchronized
    fun publish(context: Context) {
        val app = context.applicationContext
        if (pending) return
        val wait = lastSentMs + MIN_INTERVAL_MS - System.currentTimeMillis()
        pending = true
        main.postDelayed({ worker.execute { send(app) } }, wait.coerceAtLeast(0L))
    }

    /** Sends the state at once, whatever the last send: an export plugin asked for it ([ExportRequestReceiver]). */
    fun publishNow(context: Context) {
        val app = context.applicationContext
        worker.execute { send(app) }
    }

    private fun send(context: Context) {
        synchronized(this) {
            pending = false
            lastSentMs = System.currentTimeMillis()
        }
        val receivers = installed(context)
        if (receivers.isEmpty()) return
        val json = ExportState.toJson(vehicles(context), System.currentTimeMillis())
        for (receiver in receivers) {
            val intent = Intent(CarPluginContract.ACTION_EXPORT).setComponent(receiver).putExtra(CarPluginContract.EXTRA_STATE, json)
            // the receiver's app must hold the plugin permission too: only apps signed like Drivelog do
            context.sendBroadcast(intent, CarPluginContract.PERMISSION)
        }
    }

    private fun vehicles(context: Context): List<ExportVehicle> {
        val stores = Stores(context)
        val trips = stores.trips?.list().orEmpty()
        val today = LocalDate.now()
        val settings = AppSettings.reminders(context)
        val logs = ReminderCheck.cars(context).associateBy { it.address.lowercase() }
        return Prefs.cars(context).filter { !stores.details.get(it.address).archived }.map { car ->
            val d = stores.details.get(car.address)
            val reading = stores.readings.get(car.address)
            val summary = VehicleSummary.of(car.address, trips, details = d, reading = reading)
            val log = logs[car.address.lowercase()]
            ExportVehicle(
                address = car.address,
                name = stores.carName(car.address, car.name),
                kind = d.kind,
                values = reading?.values,
                readAtMs = reading?.atMs,
                parkedLat = summary.parkedLat,
                parkedLon = summary.parkedLon,
                parkedAtMs = summary.parkedAtMs,
                services = log?.let { Reminders.upcoming(it.events, today, it.odometerKm, settings.warnDays, settings.warnKm) }.orEmpty(),
            )
        }
    }
}
