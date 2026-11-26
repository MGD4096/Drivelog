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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The plugin as Drivelog sees it ([CarPluginContract]): Drivelog binds this service while a trip runs and calls
 * [ICarPlugin.start]. The service then keeps a connection to the head-unit plugin (DrivelogBridge in the car), turns
 * the car's CDS values into neutral [CarValues] and sends them on, at most once a second and only when something
 * changed. Everything runs on one worker thread, so start, stop and the reports never overlap.
 *
 * The parts: [HeadUnitRoutes] (the ways to reach the head unit, tried in turn), [BridgeClient] (keeps the connection
 * and reads the values into [CdsState]), [PluginStatus] (what state to report). This class only starts, stops and
 * reports.
 *
 * Only apps signed with the same key as this one can bind (the manifest's permission). The service needs no
 * foreground notification of its own: while Drivelog's trip service (a foreground service) is bound to it, Android
 * treats it as just as important.
 */
class Id6PluginService : Service() {

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "id6-plugin").apply { isDaemon = true } }
    private val cds = CdsState()
    private var routes: HeadUnitRoutes? = null
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
            // First the phone calls the head unit, if Android lets this app see the car's Wi-Fi. With wireless Android
            // Auto it does not: then the head unit calls the phone (HeadUnitCalls).
            val r = HeadUnitRoutes(listOf(
                WifiRoute(CarWifi(this).also { it.start() }, BridgeClient.DEFAULT_PORT),
                CallsRoute.open(CALL_WAIT_MS),
            ))
            routes = r
            client = BridgeClient(cds, connect = { r.connect() }).also { it.start() }
            reports = worker.scheduleWithFixedDelay({ report() }, 0, REPORT_MS, TimeUnit.MILLISECONDS)
        }
        report()
    }

    private fun stopReading() {
        reports?.cancel(false)
        reports = null
        routes?.close()                        // first: a waiting accept() returns at once
        routes = null
        client?.stop()
        client = null
        val s = sink
        sink = null
        try { s?.onStatus(CarPluginContract.STATE_STOPPED, "") } catch (_: RemoteException) {}
        lastStatus = CarPluginContract.STATE_STOPPED to ""
        diagnostics = ""
    }

    private fun report() {
        val s = sink ?: return
        val c = client ?: return
        val status = PluginStatus.of(c.state, c.headUnit, c.lastError)
        lastStatus = status
        diagnostics = listOfNotNull(routes?.describe(), c.lastError?.let { "last error: $it" }).joinToString("\n")
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
