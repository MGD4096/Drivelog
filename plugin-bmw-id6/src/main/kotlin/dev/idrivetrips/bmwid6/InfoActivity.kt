package dev.idrivetrips.bmwid6

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import dev.idrivetrips.pluginapi.CarPluginContract

/**
 * The plugin's only screen: what it is, whether Drivelog is installed, and the state of the car connection. It has no
 * launcher icon: Drivelog opens it (car page → "Open Drivelog BMW ID6", [dev.idrivetrips.pluginapi.CarPluginContract.ACTION_SETTINGS]).
 * There is nothing to set up; the plugin is picked in Drivelog (car → Edit → plugin).
 */
class InfoActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val refresh = object : Runnable {
        override fun run() {
            show()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        text = TextView(this).apply { setPadding(pad, pad, pad, pad); textSize = 16f }
        setContentView(ScrollView(this).apply { addView(text) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun show() {
        val drivelog = try {
            packageManager.getPackageInfo(DRIVELOG_PACKAGE, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }
        val (state, detail) = BmwId6PluginService.lastStatus
        val stateText = getString(
            when (state) {
                CarPluginContract.STATE_CONNECTED -> R.string.state_connected
                CarPluginContract.STATE_NO_CAR -> R.string.state_no_car
                CarPluginContract.STATE_CONNECTING -> R.string.state_connecting
                CarPluginContract.STATE_ERROR -> R.string.state_error
                else -> R.string.state_stopped
            }
        ) + if (detail.isNotEmpty()) " ($detail)" else ""
        text.text = getString(R.string.info_text) + "\n\n" +
            getString(if (drivelog) R.string.drivelog_installed else R.string.drivelog_missing) + "\n\n" +
            getString(R.string.state_label, stateText) + "\n\n" +
            getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)
    }

    companion object {
        const val DRIVELOG_PACKAGE = "dev.idrivetrips"
    }
}
