package dev.idrivetrips

import dev.idrivetrips.core.tr
import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.idrivetrips.core.BridgeHttpServer
import dev.idrivetrips.core.BridgeProtocol
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.TripRecorder
import dev.idrivetrips.core.TripStats
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Records one trip. It runs from the moment the car connects until it disconnects (plus a short grace period), and
 * while it runs it also serves the local HTTP page that the head-unit plugin pushes car data to.
 *
 * Everything here runs on the main thread except the HTTP server's own worker threads, which only touch
 * [AppState.car] (thread-safe). The recorder and the store are used from the main thread only.
 */
class TripService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = TripRecorder()
    private lateinit var store: FileTripStore

    private var server: BridgeHttpServer? = null
    private var serverError: String? = null
    private var locationManager: LocationManager? = null
    private var locationRegistered = false
    private var gpsProblem: String? = null
    private var receiverRegistered = false

    private var running = false
    private var carAddress = ""
    private var carName = ""
    private var lastFixMs = 0L
    private var lastSpeedKmh: Double? = null
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

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // While waiting for a reconnect the car is parked: do not record the walk away from it
            if (disconnectedAtMs != 0L) return
            lastFixMs = System.currentTimeMillis()
            lastSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else null
            recorder.onFix(
                TripRecorder.Fix(
                    timeMs = location.time,
                    lat = location.latitude,
                    lon = location.longitude,
                    altitudeM = if (location.hasAltitude()) location.altitude else null,
                    speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                    accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                )
            )
        }

        // Android 10 and older call these on the interface; they have no default implementation there
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val address = bluetoothDeviceFrom(intent)?.address ?: return
            if (!address.equals(carAddress, ignoreCase = true)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onCarDisconnected()
                BluetoothDevice.ACTION_ACL_CONNECTED -> onCarReconnected()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        try {
            store = FileTripStore(File(filesDir, "trips"))
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
        if (running) saveDraft()          // killed while recording: the draft is recovered on the next start
        stopLocation()
        unregisterBluetooth()
        stopServer()
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
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: started from the background without an exemption
            Notifications.tapToStart(this, CarRef(address, name))
            false
        }
    }

    private fun beginTrip(address: String, name: String) {
        carAddress = address
        carName = name
        recoverDraft()
        AppState.car.reset()        // values from the last drive must not look current
        recorder.begin(address, name, System.currentTimeMillis(), AppState.car.snapshot())
        running = true
        disconnectedAtMs = 0L
        ticks = 0
        startServer()
        startLocation()
        registerBluetooth()
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
        val trip = recorder.finish(endMs)
        running = false
        stopLocation()
        unregisterBluetooth()
        stopServer()

        var message = "$reason."
        if (trip != null) {
            val summary = TripStats.summarize(trip)
            if (!TripStats.isWorthKeeping(summary)) {
                message = tr("{0}. The trip was too short and was not saved.", reason)
                clearDraft()
            } else {
                try {
                    store.save(trip)
                    clearDraft()
                    message = tr("{0}. Saved {1} km.", reason, km(summary.distanceKm))
                } catch (e: IOException) {
                    message = tr("{0}, but the trip could not be saved: {1}", reason, e.message)     // the draft stays for a retry
                }
            }
            Notifications.tripResult(this, message)
        }
        AppState.status = ServiceStatus(message = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- periodic work ---------------------------------------------------------------------------------------------

    private fun onTick() {
        val now = System.currentTimeMillis()
        if (server == null) startServer()                  // for example the port was busy the first time
        recorder.onCar(now, AppState.car.snapshot())
        if (++ticks % DRAFT_EVERY_TICKS == 0) saveDraft()
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
            serverRunning = server != null,
            serverError = serverError,
            gpsProblem = gpsProblem,
            lastFixMs = lastFixMs,
            speedKmh = lastSpeedKmh?.takeIf { now - lastFixMs < 10_000 },
            trip = if (recorder.isRecording) recorder.liveSummary(now) else null,
            reconnectDeadlineMs = if (disconnectedAtMs != 0L) disconnectedAtMs + GRACE_MS else 0L,
        )
    }

    // ---- pieces ------------------------------------------------------------------------------------------------------

    private fun startServer() {
        if (server != null) return
        val s = BridgeHttpServer(
            port = PORT,
            protocol = BridgeProtocol(AppState.car, BuildConfig.VERSION_NAME, System.currentTimeMillis() / 1000),
        )
        try {
            s.start()
            server = s
            serverError = null
        } catch (e: IOException) {
            serverError = tr("Port {0}: {1}", PORT, e.message)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun startLocation() {
        gpsProblem = null
        val lm = locationManager
        val allowed = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            gpsProblem = tr("no GPS")
            return
        }
        if (!allowed) {
            gpsProblem = tr("location not allowed")
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            locationRegistered = true
        } catch (e: SecurityException) {
            gpsProblem = tr("location not allowed")
        }
    }

    private fun stopLocation() {
        if (locationRegistered) {
            locationManager?.removeUpdates(locationListener)
            locationRegistered = false
        }
    }

    private fun registerBluetooth() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterBluetooth() {
        if (!receiverRegistered) return
        try { unregisterReceiver(bluetoothReceiver) } catch (e: IllegalArgumentException) { /* already gone */ }
        receiverRegistered = false
    }

    // ---- crash safety ----------------------------------------------------------------------------------------------------

    private fun saveDraft() {
        if (!recorder.isRecording) return
        try { store.saveDraft(recorder.snapshotTrip(System.currentTimeMillis())) } catch (e: IOException) { /* try again next minute */ }
    }

    private fun clearDraft() {
        try { store.clearDraft() } catch (e: Exception) { /* nothing to do */ }
    }

    /** A draft left by a killed service becomes a normal saved trip. */
    private fun recoverDraft() {
        val draft = try { store.loadDraft() } catch (e: Exception) { null } ?: return
        try {
            if (TripStats.isWorthKeeping(TripStats.summarize(draft))) store.save(draft)
            store.clearDraft()
        } catch (e: IOException) {
            // keep it and try again next time
        }
    }

    private fun km(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    companion object {
        const val ACTION_START = "dev.idrivetrips.START"
        const val ACTION_STOP = "dev.idrivetrips.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        /** The port the head-unit plugin looks for the phone on. Fixed by the plugin. */
        const val PORT = 8765

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
