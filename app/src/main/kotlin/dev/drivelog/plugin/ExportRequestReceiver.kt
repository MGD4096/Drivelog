package dev.drivelog.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.drivelog.pluginapi.CarPluginContract

/**
 * An export plugin's "Send now" ([CarPluginContract.ACTION_EXPORT_REQUEST]): the state goes out at once ([Exports]).
 * Only apps holding the plugin permission (signed like Drivelog) can send it (the manifest); the state itself goes
 * only to the export plugins Drivelog accepts anyway.
 */
class ExportRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CarPluginContract.ACTION_EXPORT_REQUEST) Exports.publishNow(context)
    }
}
