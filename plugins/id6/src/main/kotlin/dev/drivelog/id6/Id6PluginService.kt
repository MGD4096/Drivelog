package dev.drivelog.id6

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import dev.drivelog.carapi.CarValues
import dev.drivelog.pluginapi.CarPluginContract
import dev.drivelog.pluginapi.CarValuesBundle
import dev.drivelog.pluginapi.ICarDataSink
import dev.drivelog.pluginapi.ICarPlugin
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The plugin as Drivelog sees it ([CarPluginContract]): Drivelog binds this service while a trip runs and calls
 * [ICarPlugin.start]. The service then keeps a connection to the head-unit plugin (DrivelogBridge in the car, over the
 * car's Wi-Fi), turns the car's CDS values into neutral [CarValues] and sends them on, at most once a second and only when
 * something changed. Everything runs on one worker thread, so start, stop and the reports never overlap.
 *
 * Only apps signed with the same key as this one can bind (the manifest's permission). The service needs no
 * foreground notification of its own: while Drivelog's trip service (a foreground service) is bound to it, Android
 * treats it as just as important.
 */
class Id6PluginService : Service() {

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "id6-plugin").apply { isDaemon = true } }
    private val cds = CdsState()
    private var wifi: CarWifi? = null
    private var calls: HeadUnitCalls? = null
    @Volatile private var wifiError: String? = null
    private var callsError: String? = null
    private var client: BridgeClient? = null
    private var sink: ICarDataSink? = null
    private var reports: ScheduledFuture<*>? = null
    private var sentVersion = -1L
    private var sentStatus: Pair<Int, String>? = null

    private val binder = object : ICarPlugin.Stub() {
        override fun apiVersion(): Int = CarPluginContract.API_VERSION
        override fun start(sink: ICarDataSink?) {
            if (sink == null) return
            worker.execute { startReading(sink) }
        }
        override fun stop() {
            worker.execute { stopReading() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        worker.execute { stopReading() }        // the last client has gone: stop reading the car
        return false
    }

    override fun onDestroy() {
        worker.execute { stopReading() }
        worker.shutdown()
        super.onDestroy()
    }

    // ---- on the worker thread --------------------------------------------------------------------------------------

    private fun startReading(newSink: ICarDataSink) {
        sink = newSink
        sentVersion = -1L                      // a new sink gets everything once
        sentStatus = null
        if (client == null) {
            cds.reset()                        // values of an earlier drive must not look current
            val w = CarWifi(this).also { it.start() }
            wifi = w
            val incoming = try {
                HeadUnitCalls()
            } catch (e: IOException) {             // port taken: only the phone-calls-car way is left
                callsError = e.message ?: e.javaClass.simpleName
                null
            }
            calls = incoming
            client = BridgeClient(cds, connect = { connectToHeadUnit(w, incoming) }).also { it.start() }
            reports = worker.scheduleWithFixedDelay({ report() }, 0, REPORT_MS, TimeUnit.MILLISECONDS)
        }
        report()
    }

    private fun stopReading() {
        reports?.cancel(false)
        reports = null
        calls?.close()                         // first: a waiting accept() returns at once
        calls = null
        client?.stop()
        client = null
        wifi?.stop()
        wifi = null
        val s = sink
        sink = null
        try { s?.onStatus(CarPluginContract.STATE_STOPPED, "") } catch (_: RemoteException) {}
        lastStatus = CarPluginContract.STATE_STOPPED to ""
        diagnostics = ""
        callsError = null
        wifiError = null
    }

    private fun report() {
        val s = sink ?: return
        val c = client ?: return
        val status = statusOf(c)
        lastStatus = status
        diagnostics = diagnosticsText()
        try {
            if (status != sentStatus) {
                s.onStatus(status.first, status.second)
                sentStatus = status
            }
            val version = cds.version
            if (version != sentVersion) {
                s.onValues(CarValuesBundle.toBundle(cds.values()))
                sentVersion = version
            }
        } catch (e: RemoteException) {
            stopReading()                      // Drivelog is gone (DeadObjectException)
        }
    }

    /**
     * On the bridge client's thread. First the phone calls the head unit, if Android lets this app see the car's
     * Wi-Fi (an ordinary Wi-Fi connection). With wireless Android Auto it does not: then wait up to [CALL_WAIT_MS] for
     * the head unit to call ([HeadUnitCalls]). Null = nobody this time; the client tries again.
     */
    private fun connectToHeadUnit(w: CarWifi, incoming: HeadUnitCalls?): java.net.Socket? {
        val direct = try {
            w.connect(BridgeClient.DEFAULT_PORT).also { wifiError = null }
        } catch (e: IOException) {
            wifiError = e.message ?: e.javaClass.simpleName
            null
        }
        if (direct != null) return direct
        return incoming?.accept(CALL_WAIT_MS)
    }

    /** One line for the plugin's own screen: what each way of reaching the head unit is doing. */
    private fun diagnosticsText(): String {
        val w = wifi
        val c = calls
        val parts = ArrayList<String>()
        parts += "Wi-Fi networks this app may use: ${w?.visibleCount ?: 0}" + (wifiError?.let { " ($it)" } ?: "")
        parts += if (c != null) {
            "listening for the head unit on port ${c.boundPort}: ${c.calls} call(s)" + (c.lastCaller?.let { ", last from $it" } ?: "")
        } else {
            "not listening for the head unit" + (callsError?.let { " ($it)" } ?: "")
        }
        client?.lastError?.let { parts += "last error: $it" }
        return parts.joinToString("\n")
    }

    private fun statusOf(c: BridgeClient): Pair<Int, String> = when (c.state) {
        BridgeClient.State.CONNECTED -> CarPluginContract.STATE_CONNECTED to (c.headUnit ?: "")
        BridgeClient.State.NO_NETWORK -> CarPluginContract.STATE_NO_CAR to ""
        BridgeClient.State.STOPPED -> CarPluginContract.STATE_STOPPED to ""
        BridgeClient.State.CONNECTING ->
            c.lastError?.let { CarPluginContract.STATE_ERROR to it } ?: (CarPluginContract.STATE_CONNECTING to "")
    }

    companion object {
        private const val REPORT_MS = 1_000L
        private const val CALL_WAIT_MS = 3_000

        /** How the plugin is trying to reach the head unit, for [InfoActivity]. */
        @Volatile var diagnostics: String = ""
            private set

        /** The latest state, for the plugin's own screen ([InfoActivity]). */
        @Volatile var lastStatus: Pair<Int, String> = CarPluginContract.STATE_STOPPED to ""
            private set
    }
}
