package dev.drivelog.main

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.drivelog.Colors
import dev.drivelog.MainActivity
import dev.drivelog.Ui
import dev.drivelog.core.text.Lang
import dev.drivelog.core.text.tr

/**
 * The permissions card of the Settings tab: one row per permission with a tick or a cross, what it is for, and an
 * "Allow" button while it is missing. [refresh] is cheap to call every second: it redraws only when something changed.
 */
class PermissionsPanel(private val activity: Activity, private val ui: Ui) {
    /** One permission the app asks for; [granted] is read again on every refresh. */
    private class Item(val name: String, val why: String, val granted: () -> Boolean, val request: () -> Unit)

    val view: LinearLayout = ui.card()
    private var shown = ""

    private val items: List<Item> get() = listOf(
        Item(tr("Location"), tr("for the GPS track"), { granted(Manifest.permission.ACCESS_FINE_LOCATION) }, ::requestBasic),
        Item(tr("Location all the time"), tr("to record when the car starts a trip"),
            { Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, ::requestBackgroundLocation),
        Item(tr("Bluetooth"), tr("to list your paired cars"), { Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT) }, ::requestBasic),
        Item(tr("Notifications"), tr("for reminders and the recording notice"),
            { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }, ::requestBasic),
        Item(tr("Battery unrestricted"), tr("lets Android start a trip in the background"),
            { activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName) }, ::requestUnrestrictedBattery),
    )

    fun refresh() {
        val list = items.map { it to it.granted() }
        val signature = Lang.code + list.joinToString("") { if (it.second) "1" else "0" }
        if (signature == shown) return              // nothing changed: leave the views alone
        shown = signature
        view.removeAllViews()
        for ((item, ok) in list) {
            view.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(5), 0, ui.dp(5))
                addView(ui.body().apply {
                    text = if (ok) "✓" else "✗"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (ok) Colors.good else Colors.warn)
                    contentDescription = if (ok) tr("Allowed") else tr("Not allowed")
                }, LinearLayout.LayoutParams(ui.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.body().apply { text = item.name; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
                    addView(ui.body().apply { text = item.why; textSize = 12f; setTextColor(Colors.muted) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(6); rightMargin = ui.dp(8) })
                if (!ok) addView(ui.pill(tr("Allow"), filled = true, heightDp = 34) { item.request() })
            })
        }
    }

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestBasic() {
        val list = ArrayList<String>()
        list += Manifest.permission.ACCESS_FINE_LOCATION
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        activity.requestPermissions(list.toTypedArray(), MainActivity.REQ_BASIC)
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            ui.toast(tr("Allow location first, then choose \"all the time\"."))
            requestBasic()
            return
        }
        // Android 11 and later send the user to the settings page for this one
        activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
    }

    private fun requestUnrestrictedBattery() {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private companion object {
        const val REQ_BACKGROUND = 2
    }
}
