package dev.drivelog

import dev.drivelog.core.tr
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.drivelog.core.LiveSpeed
import dev.drivelog.core.TripStore
import dev.drivelog.core.TripRecorder
import dev.drivelog.core.TripStats
import java.io.IOException
import java.util.Locale

/**
 * Records one trip. It runs from the moment the car connects until it disconnects (plus a short grace period), and
 * while it runs it is bound to the car's plugin app (if the car uses one), which sends the car data.
 *
 * The parts: [TripLocation] (GPS), [CarConnectionWatch] (Bluetooth), [TripPlugin] (the car's plugin app),
 * [CarRecordKeeper] (latest values, parking, battery), [TripDrafts] (crash safety). This class runs the trip's
 * lifecycle: start, tick, disconnect and grace period, finish and save.
 *
 * Everything here runs on the main thread except the plugin's calls (binder threads), which only touch
 * [AppState.car] (thread-safe). The recorder and the store are used from the main thread only.
 */
class TripService : Service() {
    private val stores by lazy { Stores(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = TripRecorder()
    private lateinit var store: TripStore

    /** The tyre pressure warning was notified in this trip. */
    private var tyreWarned = false
    private lateinit var drafts: TripDrafts
    private val plugin by lazy { TripPlugin(this, AppState.car) }
    private val record by lazy { CarRecordKeeper(this, stores.details, stores.readings) }
    private val location by lazy { TripLocation(this, paused = { disconnectedAtMs != 0L }) { recorder.onFix(it) } }
    private val connection by lazy { CarConnectionWatch(this, { carAddress }, ::onCarDisconnected, ::onCarReconnected) }

    private var running = false
    private var carAddress = ""
    private var carName = ""
    private var ticks = 0

    /** Non-zero while the car is disconnected and we wait to see whether it comes back. */
    private var disconnectedAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val finishAfterGrace = Runnable { finishTrip(tr("Car disconnected")) }

    override fun onCreate() {
        super.onCreate()
        try {
            store = stores.requireTrips()
            drafts = TripDrafts(store, recorder)
        } catch (e: IOException) {
            Notifications.problem(this, tr("Cannot store trips"), e.message ?: tr("The trips folder cannot be created."))
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }      // restarted by the system: nothing to resume
        when (intent.action) {
            ACTION_STOP -> {
                if (running) finishTrip(tr("Stopped")) else stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: address

        // startForegroundService() must be answered with startForeground() quickly, whatever happens next
        if (!promoteToForeground(address, name)) { stopSelf(); return START_NOT_STICKY }
        if (address.isEmpty() || !::store.isInitialized) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            // The same car again (reconnect, or a second start): carry on. A different car: keep the current trip.
            if (address.equals(carAddress, ignoreCase = true)) onCarReconnected()
            return START_NOT_STICKY
        }
        beginTrip(address, name)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (running) drafts.save()          // killed while recording: the draft is recovered on the next start
        location.stop()
        connection.stop()
        plugin.stop()
        running = false
        AppState.status = ServiceStatus(message = AppState.status.message)
        super.onDestroy()
    }

    // ---- trip lifecycle ------------------------------------------------------------------------------------------

    private fun promoteToForeground(address: String, name: String): Boolean {
        Notifications.ensureChannels(this)
        val notification = Notifications.tripNotification(this, name, tr("Starting trip recording"))
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_TRIP, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(Notifications.ID_TRIP, notification)
            }
            true
        } catch (e: SecurityException) {
            Notifications.problem(this, tr("Location permission needed"), tr("Open the app and allow location access, then start the trip again."))
            false
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException): started from the background without an exemption
            Notifications.tapToStart(this, CarRef(address, name))
            false
        }
    }

    private fun beginTrip(address: String, name: String) {
        carAddress = address
        carName = name
        drafts.recover()
        AppState.car.reset()        // values from the last drive must not look current
        recorder.begin(address, name, System.currentTimeMillis(), AppState.car.snapshot())
        running = true
        disconnectedAtMs = 0L
        ticks = 0
        tyreWarned = false
        record.newTrip()
        plugin.start(stores.details.get(address))
        location.start()
        connection.start()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        publish(Phase.RECORDING)
    }

    private fun onCarDisconnected() {
        if (!running || disconnectedAtMs != 0L) return
        disconnectedAtMs = System.currentTimeMillis()
        handler.removeCallbacks(finishAfterGrace)
        handler.postDelayed(finishAfterGrace, GRACE_MS)
        publish(Phase.WAITING_FOR_RECONNECT)
    }

    private fun onCarReconnected() {
        if (!running || disconnectedAtMs == 0L) return
        handler.removeCallbacks(finishAfterGrace)
        disconnectedAtMs = 0L
        publish(Phase.RECORDING)
    }

    private fun finishTrip(reason: String) {
        handler.removeCallbacksAndMessages(null)
        val now = System.currentTimeMillis()
        val endMs = if (disconnectedAtMs != 0L) disconnectedAtMs else now
        recorder.onCar(now, AppState.car.snapshot())
        recorder.onTotals(AppState.car.totals())
        val trip = recorder.finish(endMs)
        record.saveReading(carAddress, AppState.car)
        running = false
        location.stop()
        connection.stop()
        plugin.stop()

        var message = "$reason."
        var savedId: String? = null
        if (trip != null) {
            val summary = TripStats.summarize(trip)
            record.rememberParking(carAddress, summary.endLat, summary.endLon, endMs)
            if (!TripStats.isWorthKeeping(summary)) {
                message = tr("{0}. The trip was too short and was not saved.", reason)
                drafts.clear()
            } else {
                try {
                    // the driver from the settings, so the mileage log only needs the kind of trip
                    store.save(AppSettings.driver(this)?.let { trip.copy(log = trip.log.copy(driver = it)) } ?: trip)
                    drafts.clear()
                    message = tr("{0}. Saved {1} km.", reason, km(summary.distanceKm))
                    savedId = trip.id
                    record.checkBattery(carAddress, carName, store)
                } catch (e: IOException) {
                    message = tr("{0}, but the trip could not be saved: {1}", reason, e.message)     // the draft stays for a retry
                }
            }
            Notifications.tripResult(this, message, savedId)
        }
        AppState.status = ServiceStatus(message = message)
        CarWidget.update(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- periodic work ---------------------------------------------------------------------------------------------

    private fun onTick() {
        val now = System.currentTimeMillis()
        val car = AppState.car.snapshot()
        recorder.onCar(now, car)
        recorder.onTotals(AppState.car.totals())
        record.saveReading(carAddress, AppState.car)
        if (car.tyrePressureWarning == true && !tyreWarned) {          // once per trip
            tyreWarned = true
            Notifications.carWarning(
                this, Notifications.ID_TYRES, tr("Tyre pressure warning"),
                tr("{0} reports a tyre pressure warning. Check the tyres.", carName), carAddress,
            )
        }
        if (++ticks % DRAFT_EVERY_TICKS == 0) drafts.save()
        val phase = if (disconnectedAtMs != 0L) Phase.WAITING_FOR_RECONNECT else Phase.RECORDING
        publish(phase)
        val s = AppState.status
        val trip = s.trip
        val text = buildString {
            if (phase == Phase.WAITING_FOR_RECONNECT) append(tr("Car disconnected, saving soon") + " · ")
            if (trip != null) append("${km(trip.distanceKm)} km")
            s.speedKmh?.let { append(" · ${it.toInt()} km/h") }
            append(if (AppState.car.lastUpdateMillis > 0) " · " + tr("car data ✓") else " · " + tr("no car data"))
            if (s.gpsProblem != null) append(" · ${s.gpsProblem}")
        }
        Notifications.updateTrip(this, carName, text)
    }

    private fun publish(phase: Phase) {
        val now = System.currentTimeMillis()
        AppState.status = ServiceStatus(
            phase = phase,
            carName = carName,
            pluginLabel = plugin.label,
            pluginState = plugin.state,
            pluginText = plugin.text,
            pluginMissing = plugin.missing,
            gpsProblem = location.problem,
            lastFixMs = location.lastFixMs,
            // the car's own speed while the plugin sends it; the phone's GPS otherwise
            speedKmh = LiveSpeed.pick(AppState.car.snapshot().speedKmh, AppState.car.lastUpdateMillis, location.lastSpeedKmh, location.lastFixMs, now),
            trip = if (recorder.isRecording) recorder.liveSummary(now) else null,
            reconnectDeadlineMs = if (disconnectedAtMs != 0L) disconnectedAtMs + GRACE_MS else 0L,
        )
    }

    private fun km(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    companion object {
        const val ACTION_START = "dev.drivelog.START"
        const val ACTION_STOP = "dev.drivelog.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        /** How long to wait for the car to reconnect before the trip is closed. */
        const val GRACE_MS = 90_000L
        private const val TICK_MS = 5_000L
        private const val DRAFT_EVERY_TICKS = 12      // about once a minute

        /** Starts recording for a car. May throw when Android forbids a background start; the caller handles that. */
        fun start(context: Context, address: String, name: String) {
            context.startForegroundService(
                Intent(context, TripService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ADDRESS, address)
                    .putExtra(EXTRA_NAME, name)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TripService::class.java).setAction(ACTION_STOP))
        }
    }
}
