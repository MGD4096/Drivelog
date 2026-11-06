package dev.idrivetrips

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.LastKnownCar
import dev.idrivetrips.core.TripMeta
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import dev.idrivetrips.core.Lang
import dev.idrivetrips.core.tr
import dev.idrivetrips.core.trn

/**
 * Three tabs, built in code with plain framework views so the project needs no libraries at all:
 *  - Cars: each car with photo, model, VIN, odometer and where it is parked ([CarsScreen]);
 *  - Trips: every recorded trip, newest first;
 *  - Settings: live status, permissions, app settings.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var store: FileTripStore? = null
    private lateinit var ui: Ui
    private lateinit var cars: CarsScreen
    private lateinit var actions: TripActions

    private lateinit var statusText: TextView
    private lateinit var tripText: TextView
    private lateinit var permsBox: LinearLayout
    private var permsShown = ""
    private lateinit var banner: TextView
    private lateinit var tripsSummary: TextView
    private lateinit var tripsBox: LinearLayout
    private lateinit var tripsTiles: LinearLayout

    private lateinit var tabViews: List<View>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var remindersSwitch: Switch
    private lateinit var daysButton: View
    private lateinit var kmButton: View
    private lateinit var languageButton: TextView
    private lateinit var themeButton: TextView
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
        store = try { FileTripStore(File(filesDir, "trips")) } catch (e: IOException) { null }
        actions = TripActions(this, ui, store, io) { loadTrips() }
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
        io.execute { ReminderCheck.run(this) }
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

    /** Opened from the "tap to start" notification: the app is visible now, so Android lets the service start. */
    private fun handleIntent(intent: Intent?) {
        val address = intent?.getStringExtra(Notifications.EXTRA_START_ADDRESS) ?: return
        val name = intent.getStringExtra(Notifications.EXTRA_START_NAME) ?: address
        intent.removeExtra(Notifications.EXTRA_START_ADDRESS)
        startTrip(address, name)
    }

    /** "Start trip now": the trip is recorded for the car picked here, so it counts in that car's trips and odometer. */
    private fun startManualTrip() {
        val details = dev.idrivetrips.core.FileCarDetailsStore(File(filesDir, "cars.json"))
        val cars = Prefs.cars(this).filter { !details.get(it.address).archived }
        if (cars.isEmpty()) {
            startTrip(MANUAL_ID, tr("Manual trip"))
            return
        }
        val names = cars.map { c -> details.get(c.address).model?.let { m -> m + "  (" + c.name + ")" } ?: c.name } + tr("Other car (not on the list)")
        AlertDialog.Builder(this)
            .setTitle(tr("Record a trip in which car?"))
            .setItems(names.toTypedArray()) { _, i ->
                if (i < cars.size) startTrip(cars[i].address, cars[i].name) else startTrip(MANUAL_ID, tr("Manual trip"))
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun startTrip(address: String, name: String) {
        try {
            TripService.start(this, address, name)
        } catch (e: Exception) {
            toast(tr("Could not start: {0}", e.message))
        }
    }

    // ---- layout ------------------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        cars = CarsScreen(this, ui, io)
        val trips = buildTripsTab()
        val more = buildSettingsTab()
        tabViews = listOf(cars.view, trips, more)

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

    private fun scrolling(content: LinearLayout) = ScrollView(this).apply {
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
    }

    private fun buildTripsTab(): View {
        val content = page()
        content.addView(ui.title(tr("Trips")))
        banner = ui.body().apply {
            setBackgroundColor(Colors.soft)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            visibility = View.GONE
        }
        content.addView(banner)
        tripsSummary = ui.body().apply { setTextColor(Colors.muted); setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        content.addView(tripsSummary)
        tripsTiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(tripsTiles)
        tripsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(tripsBox)
        return scrolling(content)
    }

    private fun buildSettingsTab(): View {
        val content = page()
        content.addView(ui.title(tr("Settings")))

        content.addView(ui.heading(tr("Language")))
        val language = ui.card()
        languageButton = ui.pill("") { chooseLanguage() }
        language.addView(languageButton)
        content.addView(language)

        content.addView(ui.heading(tr("Theme")))
        val theme = ui.card()
        themeButton = ui.pill("") { chooseTheme() }
        theme.addView(themeButton)
        content.addView(theme)

        content.addView(ui.heading(tr("Recording")))
        val recording = ui.card()
        statusText = ui.body().also { recording.addView(it) }
        tripText = ui.body().also { recording.addView(it) }
        recording.addView(buttons(
            ui.pill(tr("Start trip now"), filled = true) { startManualTrip() },
            ui.pill(tr("Stop and save")) { TripService.stop(this) },
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) })
        content.addView(recording)

        content.addView(ui.heading(tr("Reminders")))
        val reminders = ui.card()
        remindersSwitch = Switch(this).apply {
            text = tr("Notify me about inspection, insurance and oil")
            textSize = 15f
            setPadding(0, ui.dp(4), 0, ui.dp(8))
            setOnCheckedChangeListener { _, on ->
                AppSettings.setRemindersEnabled(this@MainActivity, on)
                if (on) ReminderScheduler.ensureScheduled(this@MainActivity)
                refreshReminders()
            }
        }
        daysButton = ui.pill("") {
            choose(tr("Warn this many days ahead"), AppSettings.DAY_CHOICES, { trn(it, "{0} day", "{0} days") }) { AppSettings.setWarnDays(this, it); refreshReminders() }
        }
        kmButton = ui.pill("") {
            choose(tr("Warn for oil this many kilometres ahead"), AppSettings.KM_CHOICES, { tr("{0} km", ui.km(it.toDouble())) }) {
                AppSettings.setWarnKm(this, it); refreshReminders()
            }
        }
        reminders.addView(remindersSwitch)
        reminders.addView(ui.body().apply { text = tr("Warn me before the due date:"); textSize = 13f; setTextColor(Colors.subtle) })
        reminders.addView(buttons(daysButton, kmButton), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
        reminders.addView(ui.body().apply {
            text = tr("The app checks once a day, and whenever it is opened, the dates you entered on each car's screen. You get one notification when an entry comes within the warning time, and one more when it has run out. The same warning time colours the reminders on the Cars tab.")
            setTextColor(Colors.muted)
            textSize = 13f
            setPadding(0, ui.dp(8), 0, ui.dp(4))
        })
        reminders.addView(ui.pill(tr("Send a test notification")) {
            if (ReminderCheck.canNotify(this)) {
                Notifications.reminder(this, 999, tr("Test reminder"), tr("This is how a reminder looks."), null)
            } else {
                toast(tr("Notifications are not allowed yet: use \"Allow\" under Permissions."))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(4) })
        content.addView(reminders)
        refreshReminders()

        content.addView(ui.heading(tr("Data")))
        val data = ui.card()
        data.addView(ui.body().apply {
            text = tr("All trips, cars, photos, the service log and the settings go into one file you can keep or move to another phone.")
            textSize = 13f
            setTextColor(Colors.muted)
            setPadding(0, 0, 0, ui.dp(8))
        })
        data.addView(buttons(ui.pill(tr("Export all data")) { startExport() }, ui.pill(tr("Import data")) { startImport() }))
        content.addView(data)

        content.addView(ui.heading(tr("Permissions")))
        val permissions = ui.card()
        permsBox = permissions
        content.addView(permissions)

        content.addView(ui.heading(tr("About")))
        content.addView(ui.body().apply {
            text = tr("Drivelog records your trips and the data the car reports through the iDrive Plus head-unit plugin.\nMap data © OpenStreetMap contributors.")
            setTextColor(Colors.muted)
        })
        return scrolling(content)
    }

    // ---- export and import of all data -----------------------------------------------------------------------------------

    private fun startExport() {
        val name = "idrive-trips-backup-" + java.time.LocalDate.now() + ".zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, name)
        try { startActivityForResult(intent, REQ_EXPORT) } catch (e: ActivityNotFoundException) { toast(tr("Could not export: {0}", e.message)) }
    }

    private fun startImport() {
        if (AppState.status.phase != Phase.IDLE) {
            toast(tr("Stop the trip that is being recorded first."))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        try { startActivityForResult(intent, REQ_IMPORT) } catch (e: ActivityNotFoundException) { toast(tr("Could not import: {0}", e.message)) }
    }

    @Deprecated("The framework Activity has no other way to get a result without libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_EXPORT -> exportTo(uri)
            REQ_IMPORT -> AlertDialog.Builder(this)
                .setTitle(tr("Import data?"))
                .setMessage(tr("Importing replaces everything in the app (trips, cars, photos, the service log and the settings) with the contents of the file. This cannot be undone."))
                .setPositiveButton(tr("Replace")) { _, _ -> importFrom(uri) }
                .setNegativeButton(tr("Cancel"), null)
                .show()
        }
    }

    private fun exportTo(uri: Uri) {
        io.execute {
            val result = try {
                val out = contentResolver.openOutputStream(uri) ?: throw IOException("cannot open the file")
                out.use { dev.idrivetrips.core.Backup.write(filesDir, BackupPrefs.collect(this), it) }.files
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (result is Int) tr("Exported {0} files.", result) else tr("Could not export: {0}", (result as Exception).message))
            }
        }
    }

    private fun importFrom(uri: Uri) {
        io.execute {
            val failure: Exception? = try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("cannot open the file")
                val imported = input.use { dev.idrivetrips.core.Backup.read(it, filesDir) }
                BackupPrefs.restore(this, imported.extras)
                null
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (failure != null) {
                    toast(tr("Could not import: {0}", failure.message))
                } else {
                    AppSettings.applyLanguage(this)
                    Notifications.ensureChannels(this)
                    toast(tr("Data imported."))
                    recreate()
                }
            }
        }
    }

    private fun languageName(choice: String): String =
        if (choice == Lang.SYSTEM) tr("Phone language") else Lang.available.firstOrNull { it.first == choice }?.second ?: choice

    private fun chooseLanguage() {
        val choices = listOf(Lang.SYSTEM) + Lang.available.map { it.first }
        AlertDialog.Builder(this)
            .setTitle(tr("Language"))
            .setItems(choices.map { languageName(it) }.toTypedArray()) { _, i ->
                AppSettings.setLanguage(this, choices[i])
                AppSettings.applyLanguage(this)
                Notifications.ensureChannels(this)               // the names of the notification channels follow the language
                recreate()                                       // build every screen again in the new language
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun themeName(choice: String): String = when (choice) {
        Colors.LIGHT -> tr("Light")
        Colors.DARK -> tr("Dark")
        else -> tr("Follow the phone")
    }

    private fun chooseTheme() {
        val choices = listOf(Colors.SYSTEM, Colors.LIGHT, Colors.DARK)
        AlertDialog.Builder(this)
            .setTitle(tr("Theme"))
            .setItems(choices.map { themeName(it) }.toTypedArray()) { _, i ->
                AppSettings.setTheme(this, choices[i])
                recreate()                                       // every screen is built again with the new colours
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun refreshReminders() {
        themeButton.text = themeName(AppSettings.theme(this)) + "   ▾"
        languageButton.text = languageName(AppSettings.language(this)) + "   ▾"
        val r = AppSettings.reminders(this)
        // Setting the state also fires the listener; it only rewrites the same value
        if (remindersSwitch.isChecked != r.enabled) remindersSwitch.isChecked = r.enabled
        (daysButton as TextView).text = trn(r.warnDays, "{0} day", "{0} days")
        (kmButton as TextView).text = tr("{0} km (oil)", ui.km(r.warnKm))
        for (b in listOf(daysButton, kmButton)) { b.isEnabled = r.enabled; b.alpha = if (r.enabled) 1f else 0.4f }
    }

    private fun choose(title: String, values: List<Int>, label: (Int) -> String, onPicked: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(values.map(label).toTypedArray()) { _, i -> onPicked(values[i]) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- live status -----------------------------------------------------------------------------------------------------

    private fun updateLive() {
        val now = System.currentTimeMillis()
        val s = AppState.status

        if (lastPhase != Phase.IDLE && s.phase == Phase.IDLE) loadTrips()      // a trip just ended
        lastPhase = s.phase

        statusText.text = buildString {
            when (s.phase) {
                Phase.IDLE -> {
                    append(tr("Not recording. A trip starts when your phone connects to a car on the Cars tab."))
                    s.message?.let { append("\n").append(it) }
                }
                Phase.RECORDING -> append(tr("Recording a trip in {0}.", s.carName))
                Phase.WAITING_FOR_RECONNECT ->
                    append(tr("{0} disconnected. The trip is saved in {1} s unless it reconnects.", s.carName, ((s.reconnectDeadlineMs - now) / 1000).coerceAtLeast(0)))
            }
            if (s.phase != Phase.IDLE) {
                append(
                    when {
                        s.serverRunning -> "\n" + tr("Plugin link: listening on port {0}.", TripService.PORT)
                        s.serverError != null -> "\n" + tr("Plugin link failed: {0}", s.serverError)
                        else -> "\n" + tr("Plugin link: starting.")
                    }
                )
            }
        }

        val t = s.trip
        tripText.text = when {
            t == null -> ""
            else -> buildString {
                append("${ui.f1(t.distanceKm)} km · ${ui.duration(t.durationMs)}")
                s.speedKmh?.let { append(" · ${it.toInt()} km/h") }
                if (s.gpsProblem != null) append("\n" + tr("No GPS track: {0}", s.gpsProblem))
            }
        }

        // The Trips tab shows a one-line banner while a trip is being recorded, whichever tab is open
        if (s.phase == Phase.IDLE) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            banner.text = tr("Recording in {0}", s.carName) + (t?.let { " · ${ui.f1(it.distanceKm)} km · ${ui.duration(it.durationMs)}" } ?: "")
        }

        refreshPermissions()
        refreshReminders()
    }

    // ---- permissions -----------------------------------------------------------------------------------------------------

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Two buttons side by side, equally wide, with a gap between them. */
    private fun buttons(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ui.dp(4) })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(4) })
    }

    /** One row per permission: a tick or a cross, what it is for, and an "Allow" button while it is missing. */
    private fun refreshPermissions() {
        val bluetooth = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        val notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val background = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val location = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val items = listOf(
            Triple(tr("Location"), tr("for the GPS track"), location) to { requestBasicPermissions() },
            Triple(tr("Location all the time"), tr("to record when the car starts a trip"), background) to { requestBackgroundLocation() },
            Triple(tr("Bluetooth"), tr("to list your paired cars"), bluetooth) to { requestBasicPermissions() },
            Triple(tr("Notifications"), tr("for reminders and the recording notice"), notifications) to { requestBasicPermissions() },
            Triple(tr("Battery unrestricted"), tr("lets Android start a trip in the background"), battery) to { requestUnrestrictedBattery() },
        )
        val signature = Lang.code + items.joinToString("") { if (it.first.third) "1" else "0" }
        if (signature == permsShown) return              // nothing changed: leave the views alone
        permsShown = signature
        permsBox.removeAllViews()
        for ((info, action) in items) {
            val (name, why, ok) = info
            permsBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(5), 0, ui.dp(5))
                addView(ui.body().apply {
                    text = if (ok) "✓" else "✗"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (ok) Colors.good else Colors.warn)
                }, LinearLayout.LayoutParams(ui.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.body().apply { text = name; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
                    addView(ui.body().apply { text = why; textSize = 12f; setTextColor(Colors.muted) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(6); rightMargin = ui.dp(8) })
                if (!ok) addView(ui.pill(tr("Allow"), filled = true, heightDp = 34) { action() })
            })
        }
    }

    private fun requestBasicPermissions() {
        val list = ArrayList<String>()
        list += Manifest.permission.ACCESS_FINE_LOCATION
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(list.toTypedArray(), REQ_BASIC)
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast(tr("Allow location first, then choose \"all the time\"."))
            requestBasicPermissions()
            return
        }
        // Android 11 and later send the user to the settings page for this one
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
    }

    private fun requestUnrestrictedBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateLive()
        cars.redraw()                                  // the weather tile can now ask for the location
    }

    // ---- trips -----------------------------------------------------------------------------------------------------------

    private fun loadTrips() {
        val s = store ?: return
        io.execute {
            val list = try { s.list() } catch (e: Exception) { emptyList() }      // newest first
            // Fuel and range per car come from the end of its latest trip (reads trip files, so off the main thread)
            val known = HashMap<String, LastKnownCar>()
            for (car in Prefs.cars(this)) {
                try { LastKnownCar.find(car.address, list, s)?.let { known[car.address.lowercase(Locale.ROOT)] = it } } catch (e: Exception) { /* none */ }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showTrips(list)
                cars.refresh(list, known)
            }
        }
    }

    private fun showTrips(list: List<TripMeta>) {
        tripsBox.removeAllViews()
        tripsTiles.removeAllViews()
        if (list.isEmpty()) {
            tripsSummary.text = tr("No trips yet. Drive with the phone connected to a car on the Cars tab, or start one by hand on the Settings tab.")
            tripsSummary.visibility = View.VISIBLE
            return
        }
        tripsSummary.visibility = View.GONE
        tripsTiles.addView(actions.summaryTiles(list))
        actions.fill(tripsBox, list, showCar = Prefs.cars(this).size > 1)
    }

    companion object {
        /** A trip started by hand has no Bluetooth device behind it. */
        const val MANUAL_ID = "manual"
        const val REQ_BASIC = 1
        private const val REQ_BACKGROUND = 2
        private const val REQ_EXPORT = 3
        private const val REQ_IMPORT = 4
        private const val KEY_TAB = "tab"
        private const val TAB_CARS = 0
    }
}
