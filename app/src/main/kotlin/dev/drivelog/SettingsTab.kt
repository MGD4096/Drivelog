package dev.drivelog

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.drivelog.core.Lang
import dev.drivelog.core.tr
import dev.drivelog.core.trn
import dev.drivelog.pluginapi.CarPluginContract

/**
 * The Settings tab: language and theme, the recording status with start and stop, the driver for the mileage log,
 * reminders, export and import ([BackupFlow]), permissions ([PermissionsPanel]) and about. [show] updates what changes
 * while it is open (called every second).
 */
class SettingsTab(
    private val activity: Activity,
    private val ui: Ui,
    private val starter: TripStarter,
    private val backup: BackupFlow,
    private val permissions: PermissionsPanel,
) {
    private val statusText: TextView = ui.body()
    private val tripText: TextView = ui.body()
    private val languageButton: TextView = ui.pill("") { chooseLanguage() }
    private val themeButton: TextView = ui.pill("") { chooseTheme() }
    private lateinit var remindersSwitch: Switch
    private lateinit var daysButton: TextView
    private lateinit var kmButton: TextView

    val view: View = ui.scrolling(ui.page().apply {
        addView(ui.title(tr("Settings")))
        addView(ui.heading(tr("Language")))
        addView(ui.card().apply { addView(languageButton) })
        addView(ui.heading(tr("Theme")))
        addView(ui.card().apply { addView(themeButton) })
        addView(ui.heading(tr("Recording")))
        addView(recordingCard())
        addView(ui.heading(tr("Mileage log")))
        addView(mileageCard())
        addView(ui.heading(tr("Reminders")))
        addView(remindersCard())
        addView(ui.heading(tr("Data")))
        addView(dataCard())
        addView(ui.heading(tr("Permissions")))
        addView(permissions.view)
        addView(ui.heading(tr("About")))
        addView(ui.body().apply {
            text = tr("Drivelog records your trips and the data the car reports through the iDrive Plus head-unit plugin.\nMap data © OpenStreetMap contributors.")
            setTextColor(Colors.muted)
        })
    })

    init {
        refreshChoices()
    }

    /** What changes while the tab is open: the recording status, permissions and the chosen settings. */
    fun show(s: ServiceStatus, nowMs: Long) {
        statusText.text = statusLine(s, nowMs)
        val t = s.trip
        tripText.text = if (t == null) "" else buildString {
            append("${ui.f1(t.distanceKm)} km · ${ui.duration(t.durationMs)}")
            s.speedKmh?.let { append(" · ${it.toInt()} km/h") }
            if (s.gpsProblem != null) append("\n" + tr("No GPS track: {0}", s.gpsProblem))
        }
        permissions.refresh()
        refreshChoices()
    }

    private fun statusLine(s: ServiceStatus, nowMs: Long): String = buildString {
        when (s.phase) {
            Phase.IDLE -> {
                append(tr("Not recording. A trip starts when your phone connects to a car on the Cars tab."))
                s.message?.let { append("\n").append(it) }
            }
            Phase.RECORDING -> append(tr("Recording a trip in {0}.", s.carName))
            Phase.WAITING_FOR_RECONNECT ->
                append(tr("{0} disconnected. The trip is saved in {1} s unless it reconnects.", s.carName, ((s.reconnectDeadlineMs - nowMs) / 1000).coerceAtLeast(0)))
        }
        if (s.phase == Phase.IDLE) return@buildString
        val label = s.pluginLabel
        when {
            s.pluginMissing != null -> append("\n" + tr("Plugin {0} is not installed: no car data.", s.pluginMissing))
            label != null -> append(
                "\n" + when (s.pluginState) {
                    CarPluginContract.STATE_CONNECTED -> tr("{0}: connected to {1}.", label, s.pluginText.ifEmpty { "?" })
                    CarPluginContract.STATE_ERROR -> tr("{0}: the car does not answer ({1}).", label, s.pluginText)
                    CarPluginContract.STATE_NO_CAR -> tr("{0}: waiting for the car.", label)
                    else -> tr("{0}: connecting.", label)
                }
            )
        }
    }

    // ---- cards --------------------------------------------------------------------------------------------------------

    private fun below(top: Int = 10) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(top) }

    private fun recordingCard() = ui.card().apply {
        addView(statusText)
        addView(tripText)
        addView(ui.twoButtons(
            ui.pill(tr("Start trip now"), filled = true) { starter.startManual() },
            ui.pill(tr("Stop and save")) { TripService.stop(activity) },
        ), below())
    }

    private fun mileageCard() = ui.card().apply {
        val driverButton = ui.pill("") {}
        fun showDriver() { driverButton.text = tr("Driver: {0}", AppSettings.driver(activity) ?: tr("not set")) }
        showDriver()
        driverButton.setOnClickListener {
            val input = EditText(activity).apply { setText(AppSettings.driver(activity) ?: ""); hint = tr("Name and surname"); setSingleLine() }
            AlertDialog.Builder(activity)
                .setTitle(tr("Driver"))
                .setView(LinearLayout(activity).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
                .setPositiveButton(tr("Save")) { _, _ -> AppSettings.setDriver(activity, input.text.toString().trim().ifEmpty { null }); showDriver() }
                .setNegativeButton(tr("Cancel"), null)
                .show()
        }
        addView(driverButton)
        addView(ui.body().apply {
            text = tr("Filled in as the driver of every new trip. Trips count as private unless marked as business: from the \"Trip finished\" notification or in the trip. The log of a car is exported from its page.")
            textSize = 13f; setTextColor(Colors.muted); setPadding(0, ui.dp(8), 0, ui.dp(4))
        })
    }

    private fun remindersCard() = ui.card().apply {
        remindersSwitch = Switch(activity).apply {
            text = tr("Notify me about inspection, insurance and oil")
            textSize = 15f
            setPadding(0, ui.dp(4), 0, ui.dp(8))
            setOnCheckedChangeListener { _, on ->
                AppSettings.setRemindersEnabled(activity, on)
                if (on) ReminderScheduler.ensureScheduled(activity)
                refreshChoices()
            }
        }
        daysButton = ui.pill("") {
            choose(tr("Warn this many days ahead"), AppSettings.DAY_CHOICES, { trn(it, "{0} day", "{0} days") }) { AppSettings.setWarnDays(activity, it); refreshChoices() }
        }
        kmButton = ui.pill("") {
            choose(tr("Warn for oil this many kilometres ahead"), AppSettings.KM_CHOICES, { tr("{0} km", ui.km(it.toDouble())) }) {
                AppSettings.setWarnKm(activity, it); refreshChoices()
            }
        }
        addView(remindersSwitch)
        addView(ui.body().apply { text = tr("Warn me before the due date:"); textSize = 13f; setTextColor(Colors.subtle) })
        addView(ui.twoButtons(daysButton, kmButton), below(6))
        addView(ui.body().apply {
            text = tr("The app checks once a day, and whenever it is opened, the dates you entered on each car's screen. You get one notification when an entry comes within the warning time, and one more when it has run out. The same warning time colours the reminders on the Cars tab.")
            setTextColor(Colors.muted)
            textSize = 13f
            setPadding(0, ui.dp(8), 0, ui.dp(4))
        })
        addView(ui.pill(tr("Send a test notification")) {
            if (ReminderCheck.canNotify(activity)) {
                Notifications.reminder(activity, 999, tr("Test reminder"), tr("This is how a reminder looks."), null)
            } else {
                ui.toast(tr("Notifications are not allowed yet: use \"Allow\" under Permissions."))
            }
        }, below(4).apply { bottomMargin = ui.dp(4) })
    }

    private fun dataCard() = ui.card().apply {
        addView(ui.body().apply {
            text = tr("All trips, cars, photos, the service log and the settings go into one file you can keep or move to another phone.")
            textSize = 13f
            setTextColor(Colors.muted)
            setPadding(0, 0, 0, ui.dp(8))
        })
        addView(ui.twoButtons(ui.pill(tr("Export all data")) { backup.startExport() }, ui.pill(tr("Import data")) { backup.startImport() }))
    }

    // ---- choices ------------------------------------------------------------------------------------------------------

    private fun refreshChoices() {
        themeButton.text = themeName(AppSettings.theme(activity)) + "   ▾"
        languageButton.text = languageName(AppSettings.language(activity)) + "   ▾"
        val r = AppSettings.reminders(activity)
        // Setting the state also fires the listener; it only rewrites the same value
        if (remindersSwitch.isChecked != r.enabled) remindersSwitch.isChecked = r.enabled
        daysButton.text = trn(r.warnDays, "{0} day", "{0} days")
        kmButton.text = tr("{0} km (oil)", ui.km(r.warnKm))
        for (b in listOf(daysButton, kmButton)) { b.isEnabled = r.enabled; b.alpha = if (r.enabled) 1f else 0.4f }
    }

    private fun languageName(choice: String): String =
        if (choice == Lang.SYSTEM) tr("Phone language") else Lang.available.firstOrNull { it.first == choice }?.second ?: choice

    private fun chooseLanguage() {
        val choices = listOf(Lang.SYSTEM) + Lang.available.map { it.first }
        AlertDialog.Builder(activity)
            .setTitle(tr("Language"))
            .setItems(choices.map { languageName(it) }.toTypedArray()) { _, i ->
                AppSettings.setLanguage(activity, choices[i])
                AppSettings.applyLanguage(activity)
                Notifications.ensureChannels(activity)          // the names of the notification channels follow the language
                activity.recreate()                             // build every screen again in the new language
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
        AlertDialog.Builder(activity)
            .setTitle(tr("Theme"))
            .setItems(choices.map { themeName(it) }.toTypedArray()) { _, i ->
                AppSettings.setTheme(activity, choices[i])
                activity.recreate()                             // every screen is built again with the new colours
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun choose(title: String, values: List<Int>, label: (Int) -> String, onPicked: (Int) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(values.map(label).toTypedArray()) { _, i -> onPicked(values[i]) }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }
}
