package dev.drivelog.ha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.drivelog.pluginapi.CarPluginContract
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Takes Drivelog's state ([CarPluginContract.ACTION_EXPORT]; only Drivelog can send it: the manifest's permission) and
 * passes what the person allows ([HaPayload]) on to the Home Assistant webhook, when it differs from what went last.
 */
class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CarPluginContract.ACTION_EXPORT) return
        val state = intent.getStringExtra(CarPluginContract.EXTRA_STATE) ?: return
        val settings = HaSettings(context)
        settings.state = state
        val pending = goAsync()
        Thread {
            try { Sender.send(settings, force = System.currentTimeMillis() < settings.forceUntilMs) } finally { pending.finish() }
        }.start()
    }
}

/** Posts to the webhook. Blocking: call off the main thread. */
object Sender {
    private const val TIMEOUT_MS = 10_000

    /** Sends the latest state; [force] also when it went already (the plugin's "Send now"). False when it failed. */
    @Synchronized
    fun send(settings: HaSettings, force: Boolean): Boolean {
        val url = settings.webhookUrl ?: return false
        val body = settings.state?.let { HaPayload.of(it, settings.choice) } ?: return false
        if (!force && body == settings.sentBody) return true
        val error = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            try {
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code in 200..299) "" else "HTTP $code"
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        } catch (e: IllegalArgumentException) {
            e.message ?: "bad address"
        }
        settings.setResult(System.currentTimeMillis(), error)
        if (error.isEmpty()) settings.sentBody = body
        return error.isEmpty()
    }
}
