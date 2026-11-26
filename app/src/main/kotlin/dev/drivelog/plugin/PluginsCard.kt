package dev.drivelog.plugin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import dev.drivelog.Colors
import dev.drivelog.Ui
import dev.drivelog.core.text.tr
import dev.drivelog.pluginapi.CarPluginContract

/**
 * The Plugins card in the settings: the plugin apps Drivelog knows of ([CATALOG]), each with what it does and whether
 * it is installed. An installed one opens its own screen; a missing one leads to its APK, which Android's installer
 * installs (Drivelog installs nothing itself). A plugin signed with another key is shown but never used.
 * [refresh] rebuilds it only when what is installed changed.
 */
class PluginsCard(private val activity: Activity, private val ui: Ui) {

    /** A plugin app Drivelog knows of. [apk] is its file in the project's latest release. */
    class Known(val packageName: String, val label: String, val about: () -> String, val apk: String)

    val view: LinearLayout = ui.card()
    private var shownState = ""

    fun refresh() {
        val states = CATALOG.map { it to state(it) }
        val signature = states.joinToString { "${it.first.packageName}=${it.second}" }
        if (signature == shownState) return
        shownState = signature
        view.removeAllViews()
        view.addView(ui.body().apply {
            text = tr("Separate apps that add to Drivelog: they read a vehicle's values, or pass Drivelog's data on to your own server. Only plugins signed like Drivelog are used.")
            textSize = 13f
            setTextColor(Colors.muted)
            setPadding(0, 0, 0, ui.dp(6))
        })
        for ((known, state) in states) view.addView(row(known, state))
    }

    private enum class State { MISSING, INSTALLED, FOREIGN }

    private fun state(k: Known): State {
        val pm = activity.packageManager
        return try {
            pm.getPackageInfo(k.packageName, 0)
            if (pm.checkSignatures(activity.packageName, k.packageName) == PackageManager.SIGNATURE_MATCH) State.INSTALLED else State.FOREIGN
        } catch (e: PackageManager.NameNotFoundException) {
            State.MISSING
        }
    }

    private fun row(k: Known, state: State): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ui.dp(10), 0, ui.dp(4))
        val version = if (state == State.MISSING) null else try {
            activity.packageManager.getPackageInfo(k.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) { null }
        addView(ui.body().apply {
            text = k.label + (version?.let { "  $it" } ?: "")
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(ui.body().apply { text = k.about(); textSize = 13f; setTextColor(Colors.muted) })
        addView(ui.body().apply {
            text = when (state) {
                State.INSTALLED -> tr("Installed")
                State.FOREIGN -> tr("Installed, but signed with another key: Drivelog does not use it.")
                State.MISSING -> tr("Not installed")
            }
            textSize = 13f
            setTextColor(when (state) { State.INSTALLED -> Colors.good; State.FOREIGN -> Colors.warn; State.MISSING -> Colors.subtle })
        })
        val action = when (state) {
            State.MISSING -> ui.pill(tr("Download")) { download(k) }
            else -> ui.pill(tr("Open")) { open(k) }
        }
        addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
    }

    /** The plugin's own screen ([CarPluginContract.ACTION_SETTINGS]), or else its app info. */
    private fun open(k: Known) {
        val settings = Intent(CarPluginContract.ACTION_SETTINGS).setPackage(k.packageName)
        val found = activity.packageManager.queryIntentActivities(settings, 0).firstOrNull()?.activityInfo
        val intent = if (found != null) settings.setClassName(found.packageName, found.name)
            else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + k.packageName))
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("Could not open it."))
        } catch (e: SecurityException) {
            ui.toast(tr("Could not open it."))
        }
    }

    /** The APK in the browser; Android's installer takes it from there (with the person's consent). */
    private fun download(k: Known) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES + k.apk)))
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("No browser found."))
        }
    }

    companion object {
        /** The project's latest release; each plugin's APK is a file of it. */
        const val RELEASES = "https://github.com/MGD4096/Drivelog/releases/latest/download/"

        val CATALOG: List<Known> = listOf(
            Known("dev.drivelog.id6", "Drivelog ID6", { tr("Car values from an NBT Evo or EntryNav2 head unit, through its plugin in the head unit.") }, "drivelog-id6.apk"),
            Known("dev.drivelog.ride", "Drivelog Ride", { tr("A motorcycle's values from the maker's app on the phone, read after each ride.") }, "drivelog-ride.apk"),
            Known("dev.drivelog.ha", "Drivelog Home Assistant", { tr("Sends your vehicles' values, where they are parked and the services coming up to your Home Assistant.") }, "drivelog-ha.apk"),
        )
    }
}
