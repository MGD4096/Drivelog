package dev.drivelog

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.drivelog.core.LastKnownCar
import dev.drivelog.core.tr
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Three tabs, built in code with plain framework views so the project needs no libraries at all:
 *  - Cars: each car with photo, model, odometer and where it is parked ([CarsScreen]);
 *  - Trips: every recorded trip, newest first ([TripsTab]);
 *  - Settings: live status, permissions, app settings ([SettingsTab]).
 *
 * The activity owns the tab bar, the lifecycle and the loading of the trips; the tabs draw themselves.
 */
class MainActivity : Activity() {
    private val stores by lazy { Stores(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var cars: CarsScreen
    private lateinit var tripsTab: TripsTab
    private lateinit var settingsTab: SettingsTab
    private lateinit var starter: TripStarter
    private lateinit var backup: BackupFlow

    private lateinit var tabViews: List<View>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabIcons: List<ImageView>
    private var tab = TAB_CARS
    private var lastPhase = Phase.IDLE

    private val refresh = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        starter = TripStarter(this, ui, stores)
        backup = BackupFlow(this, ui, io)
        cars = CarsScreen(this, ui, io)
        tripsTab = TripsTab(this, ui, TripActions(this, ui, stores.trips, io) { loadTrips() })
        settingsTab = SettingsTab(this, ui, starter, backup, PermissionsPanel(this, ui))
        tab = savedInstanceState?.getInt(KEY_TAB, TAB_CARS) ?: TAB_CARS
        setContentView(buildUi())
        selectTab(tab)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
        handler.post(refresh)
        ReminderScheduler.ensureScheduled(this)
        io.execute { ReminderCheck.run(this); AutoBackup.runIfDue(this) }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        cars.release()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("The framework Activity has no other way to get a result without libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        backup.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateLive()
        cars.redraw()                                  // the weather tile can now ask for the location
    }

    /** Opened from the "tap to start" notification: the app is visible now, so Android lets the service start. */
    private fun handleIntent(intent: Intent?) {
        val address = intent?.getStringExtra(Notifications.EXTRA_START_ADDRESS) ?: return
        val name = intent.getStringExtra(Notifications.EXTRA_START_NAME) ?: address
        intent.removeExtra(Notifications.EXTRA_START_ADDRESS)
        starter.start(address, name)
    }

    // ---- tabs ----------------------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        tabViews = listOf(cars.view, tripsTab.view, settingsTab.view)

        val frame = FrameLayout(this)
        for (v in tabViews) frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Colors.bar) }
        val tabs = listOf(tr("Cars") to R.drawable.ic_tab_cars, tr("Trips") to R.drawable.ic_tab_trips, tr("Settings") to R.drawable.ic_tab_settings)
        val icons = ArrayList<ImageView>()
        tabLabels = tabs.mapIndexed { index, (label, iconRes) ->
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            icons += icon
            val text = TextView(this).apply { this.text = label; textSize = 12f; gravity = Gravity.CENTER; includeFontPadding = false }
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                contentDescription = label
                setOnClickListener { selectTab(index) }
                addView(icon, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
                addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(2) })
            }.also { bar.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            text
        }
        tabIcons = icons
        val divider = View(this).apply { setBackgroundColor(Colors.line) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Android 15 draws apps edge to edge: keep the content clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
    }

    private fun selectTab(index: Int) {
        tab = index
        tabViews.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabLabels.forEachIndexed { i, label ->
            val selected = i == index
            val colour = if (selected) Colors.primary else Colors.muted
            label.setTextColor(colour)
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tabIcons[i].setColorFilter(colour)
        }
    }

    // ---- live status and data -------------------------------------------------------------------------------------------

    /** Every second while the app is open: the recording status on the tabs; the trips again when one has just ended. */
    private fun updateLive() {
        val s = AppState.status
        if (lastPhase != Phase.IDLE && s.phase == Phase.IDLE) loadTrips()      // a trip just ended
        lastPhase = s.phase
        settingsTab.show(s, System.currentTimeMillis())
        tripsTab.showStatus(s)
    }

    private fun loadTrips() {
        val s = stores.trips ?: return
        io.execute {
            val list = s.list()      // newest first
            // Fuel and range per car come from the end of its latest trip (reads trip files, so off the main thread)
            // and from the latest values each car sent, saved apart from the trips
            val readings = stores.readings.all()
            val known = HashMap<String, LastKnownCar>()
            for (car in Prefs.cars(this)) {
                val key = car.address.lowercase(Locale.ROOT)
                LastKnownCar.withReading(LastKnownCar.find(car.address, list, s), readings[key])?.let { known[key] = it }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                tripsTab.show(list)
                cars.refresh(list, known)
            }
        }
    }

    companion object {
        /** A trip started by hand has no Bluetooth device behind it. */
        const val MANUAL_ID = "manual"
        const val REQ_BASIC = 1
        private const val KEY_TAB = "tab"
        private const val TAB_CARS = 0
    }
}
