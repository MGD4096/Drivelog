package dev.drivelog

import android.content.Context
import dev.drivelog.core.CarDetails
import dev.drivelog.core.CarState
import dev.drivelog.pluginapi.CarPluginContract

/**
 * The car's plugin app during a trip: bound for as long as the trip runs, so its values arrive in [car]. Nothing for a
 * car without a plugin; [missing] names a plugin the car uses that is not installed (or not signed like Drivelog).
 */
class TripPlugin(private val context: Context, private val car: CarState) {
    private var link: CarPluginLink? = null

    var missing: String? = null
        private set

    val label: String? get() = link?.label
    val state: Int get() = link?.state ?: CarPluginContract.STATE_STOPPED
    val text: String get() = link?.text.orEmpty()

    fun start(details: CarDetails) {
        if (link != null) return
        missing = null
        if (!details.usesPlugin) return
        val id = details.plugin
        val installed = CarPluginRegistry.installed(context).firstOrNull { it.plugin.id == id }
        if (installed == null) {
            missing = id
            return
        }
        link = CarPluginLink(context, installed, car).also { it.connect() }
    }

    fun stop() {
        link?.disconnect()
        link = null
        missing = null
    }
}
