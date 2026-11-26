package dev.drivelog.ride

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.drivelog.pluginapi.CarPluginContract
import dev.drivelog.pluginapi.CarValuesBundle

/**
 * Reads the vehicle app's screens ([RideScreen]). Android shows this service only the windows of that one app
 * (`res/xml/screen_reader.xml`).
 *
 * When the screen shows values that are new ([RideReadings.isNew]), it keeps them ([RideStore]), hands them to
 * Drivelog as a reading after the trip ([CarPluginContract.ACTION_READING]) and to a trip that runs right now
 * ([RidePluginService]).
 *
 * After a ride ([rideEnded]; when [RideStore.autoOpen]) it fetches the values itself: once the phone is unlocked it
 * opens the vehicle app, reads the main screen, taps the vehicle's picture for the details, reads them and goes back to
 * the home screen. That tap is the only thing it ever does in the app; it never types.
 */
class ScreenReaderService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val readNow = Runnable { readScreen() }
    private val giveUp = Runnable { finishAutoRead(goHome = false) }

    /** When the latest ride ended and its values are still to be fetched; 0 = nothing to fetch. */
    private var rideEndedMs = 0L
    /** An automatic read runs until then (0 = none); [tapped] once the vehicle's picture was tapped. */
    private var autoUntilMs = 0L
    private var tapped = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { openIfDue() }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // the screen changes many times while it builds up: read once it has been still for a moment
        handler.removeCallbacks(readNow)
        handler.postDelayed(readNow, SETTLE_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(unlocked) } catch (e: IllegalArgumentException) {}
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- fetching the values after a ride ---------------------------------------------------------------------------

    /** Main thread. A ride has just ended: fetch its values as soon as the phone is in use. */
    private fun onRideEnded() {
        if (!RideStore(this).autoOpen) return
        rideEndedMs = System.currentTimeMillis()
        openIfDue()
    }

    /** Opens the vehicle app when a ride's values are due and the phone is unlocked; otherwise waits for the unlock. */
    private fun openIfDue() {
        if (rideEndedMs == 0L) return
        if (System.currentTimeMillis() - rideEndedMs > WAIT_FOR_UNLOCK_MS) {
            rideEndedMs = 0L
            return
        }
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked != false) return
        rideEndedMs = 0L
        openVehicleApp()
    }

    /** Opens the vehicle app and reads it ([readScreen] does the rest). False when the app is not installed. */
    private fun openVehicleApp(): Boolean {
        val app = serviceInfo?.packageNames?.firstOrNull() ?: return false
        val launch = packageManager.getLaunchIntentForPackage(app) ?: return false
        autoUntilMs = System.currentTimeMillis() + AUTO_READ_MS
        tapped = false
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, AUTO_READ_MS)
        return try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) {
            finishAutoRead(goHome = false)
            false
        }
    }

    private fun finishAutoRead(goHome: Boolean) {
        if (autoUntilMs == 0L) return
        autoUntilMs = 0L
        handler.removeCallbacks(giveUp)
        if (goHome) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    private fun readScreen() {
        val root = rootInActiveWindow ?: return
        // only the app named in res/xml/screen_reader.xml (Android filters already; this keeps it so)
        val app = root.packageName?.toString()
        if (serviceInfo?.packageNames?.contains(app) != true) return
        val store = RideStore(this)
        val now = System.currentTimeMillis()
        val screen = toNode(root, 0, IntArray(1))
        val reading = RideScreen.read(screen, now, store.tankLiters)
        if (reading != null) keep(store, reading, now)
        if (autoUntilMs > now) {
            when {
                RideScreen.showsDetails(screen) -> handler.postDelayed({ finishAutoRead(goHome = true) }, SETTLE_MS)
                !tapped && reading != null -> {
                    // the main screen: tap the vehicle for the details
                    val picture = root.findAccessibilityNodeInfosByViewId("$app:id/${RideScreen.VEHICLE_IMAGE_ID}").firstOrNull()
                    tapped = picture?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!tapped) finishAutoRead(goHome = true)                 // no picture to tap: what there was is read
                }
            }
        }
    }

    private fun keep(store: RideStore, reading: RideReading, now: Long) {
        val old = store.latest
        val isNew = RideReadings.isNew(old, reading)
        // the same values again now and then too: Drivelog may have missed them (not installed yet, an older version);
        // it takes a repeat without harm
        if (!isNew && now - store.sentAtMs < RESEND_MS) return
        val merged = RideReadings.merge(old, reading)
        store.latest = merged
        sendBroadcast(
            Intent(CarPluginContract.ACTION_READING)
                .setPackage(CarPluginContract.DRIVELOG_PACKAGE)
                .putExtra(CarPluginContract.EXTRA_PLUGIN_ID, PLUGIN_ID)
                .putExtra(CarPluginContract.EXTRA_VALUES, CarValuesBundle.toBundle(reading.values))
                .putExtra(CarPluginContract.EXTRA_AT_MS, reading.atMs),
            CarPluginContract.PERMISSION,
        )
        store.sentAtMs = now
        if (isNew) RidePluginService.onNewReading(merged)
    }

    /** The window as [ScreenNode]s; at most [MAX_NODES] views and [MAX_DEPTH] levels, whatever the app shows. */
    private fun toNode(n: AccessibilityNodeInfo, depth: Int, count: IntArray): ScreenNode {
        count[0]++
        val kids = ArrayList<ScreenNode>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until n.childCount) {
                if (count[0] >= MAX_NODES) break
                val child = n.getChild(i) ?: continue
                kids += toNode(child, depth + 1, count)
            }
        }
        return ScreenNode(ScreenNode.shortId(n.viewIdResourceName), n.text?.toString(), kids)
    }

    companion object {
        /** The plugin id Drivelog knows this plugin by (the manifest's meta-data says the same). */
        const val PLUGIN_ID = "ride"
        private const val SETTLE_MS = 700L
        private const val RESEND_MS = 10 * 60_000L
        /** How long after a ride the values are still fetched at the next unlock. */
        private const val WAIT_FOR_UNLOCK_MS = 2 * 3_600_000L
        /** An automatic read that has not reached the details by then stops (and leaves the app as it is). */
        private const val AUTO_READ_MS = 20_000L
        private const val MAX_NODES = 1_000
        private const val MAX_DEPTH = 40

        @Volatile private var instance: ScreenReaderService? = null

        /** A ride has ended (Drivelog let go of the plugin): fetch its values. Any thread. */
        fun rideEnded() {
            val s = instance ?: return
            s.handler.post { s.onRideEnded() }
        }

        /** "Read now" on the plugin's screen. False when the screen reader is off or the vehicle app is missing. */
        fun readNow(): Boolean {
            val s = instance ?: return false
            return s.openVehicleApp()
        }

        /** Whether the user has turned the service on in the accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = context.packageName + "/" + ScreenReaderService::class.java.name
            return list.split(':').any { it.equals(me, ignoreCase = true) || it.equals(context.packageName + "/.ScreenReaderService", ignoreCase = true) }
        }
    }
}
