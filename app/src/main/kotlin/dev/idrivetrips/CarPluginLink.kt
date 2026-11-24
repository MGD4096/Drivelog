package dev.idrivetrips

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import dev.idrivetrips.core.CarPlugin
import dev.idrivetrips.core.CarState
import dev.idrivetrips.core.tr
import dev.idrivetrips.pluginapi.CarPluginContract
import dev.idrivetrips.pluginapi.CarValuesBundle
import dev.idrivetrips.pluginapi.ICarDataSink
import dev.idrivetrips.pluginapi.ICarPlugin

/** An installed car plugin app that Drivelog may use. */
class InstalledPlugin(val plugin: CarPlugin, val component: ComponentName, val api: Int)

/**
 * The car plugins installed on the phone (plugin-api, [CarPluginContract]).
 *
 * Only plugins that are signed with the same key as Drivelog and whose service is protected by the plugin
 * permission count: any app could declare the action, and a plugin's values go straight into the trip log.
 */
object CarPluginRegistry {

    fun installed(context: Context): List<InstalledPlugin> {
        val pm = context.packageManager
        val services = try {
            pm.queryIntentServices(Intent(CarPluginContract.ACTION), PackageManager.GET_META_DATA)
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return services.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.permission != CarPluginContract.PERMISSION) return@mapNotNull null
            if (pm.checkSignatures(context.packageName, si.packageName) != PackageManager.SIGNATURE_MATCH) return@mapNotNull null
            val meta = si.metaData ?: return@mapNotNull null
            val id = meta.getString(CarPluginContract.META_ID)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = meta.getString(CarPluginContract.META_LABEL)?.takeIf { it.isNotBlank() }
                ?: si.loadLabel(pm).toString()
            InstalledPlugin(CarPlugin(id, label), ComponentName(si.packageName, si.name), meta.getInt(CarPluginContract.META_API, 1))
        }.distinctBy { it.plugin.id }
    }

    fun plugins(context: Context): List<CarPlugin> = installed(context).map { it.plugin }

    /** The plugin's own screen ([CarPluginContract.ACTION_SETTINGS]), or null when it has none. */
    fun settingsIntent(context: Context, plugin: InstalledPlugin): Intent? {
        val intent = Intent(CarPluginContract.ACTION_SETTINGS).setPackage(plugin.component.packageName)
        val found = try {
            context.packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return intent.setClassName(found.packageName, found.name)
    }
}

/**
 * Drivelog's side of one plugin while a trip runs: binds the plugin's service, starts it, and puts what it sends into
 * [car]. If the plugin's process dies Android binds it again by itself, and it is started again.
 * Create, [connect] and [disconnect] on the main thread; [state] and [text] may be read from anywhere.
 */
class CarPluginLink(private val context: Context, private val plugin: InstalledPlugin, private val car: CarState) {

    @Volatile var state: Int = CarPluginContract.STATE_CONNECTING
        private set
    @Volatile var text: String = ""
        private set

    val label: String get() = plugin.plugin.label

    private var service: ICarPlugin? = null
    private var bound = false

    private val sink = object : ICarDataSink.Stub() {
        override fun onValues(values: Bundle?) {
            car.receive(CarValuesBundle.fromBundle(values))
        }
        override fun onStatus(state: Int, text: String?) {
            this@CarPluginLink.state = state
            this@CarPluginLink.text = text.orEmpty().take(200)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = ICarPlugin.Stub.asInterface(binder)
            service = s
            try {
                val api = s.apiVersion()
                if (api != CarPluginContract.API_VERSION) {
                    fail(tr("plugin speaks version {0}, Drivelog {1}", api, CarPluginContract.API_VERSION))
                    return
                }
                s.start(sink)
            } catch (e: RemoteException) {
                fail(e.message ?: e.javaClass.simpleName)
            } catch (e: RuntimeException) {          // e.g. SecurityException
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {   // the plugin's process died; Android rebinds
            service = null
            state = CarPluginContract.STATE_CONNECTING
            text = ""
        }
        override fun onBindingDied(name: ComponentName?) {           // updated or removed: bind afresh
            disconnect()
            connect()
        }
    }

    private fun fail(why: String) {
        state = CarPluginContract.STATE_ERROR
        text = why
    }

    fun connect() {
        if (bound) return
        bound = try {
            context.bindService(Intent().setComponent(plugin.component), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) fail(tr("the plugin cannot be started"))
    }

    fun disconnect() {
        try { service?.stop() } catch (_: RemoteException) {} catch (_: RuntimeException) {}
        service = null
        if (bound) {
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) {}
        }
        bound = false
        state = CarPluginContract.STATE_STOPPED
        text = ""
    }
}
