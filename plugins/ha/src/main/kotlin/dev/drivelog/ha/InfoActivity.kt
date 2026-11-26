package dev.drivelog.ha

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.drivelog.pluginapi.CarPluginContract
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The plugin's only screen, opened from Drivelog (Settings → Plugins): Home Assistant's address and the webhook, what
 * may be sent, the last send, and the configuration to paste into Home Assistant ([HaYaml]).
 */
class InfoActivity : Activity() {
    private lateinit var settings: HaSettings
    private lateinit var url: EditText
    private lateinit var webhook: EditText
    private lateinit var status: TextView
    private lateinit var yaml: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = HaSettings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(id: Int) = TextView(this).apply { setText(id); textSize = 15f; setPadding(0, pad, 0, 0) }
        column.addView(TextView(this).apply { setText(R.string.info_text); textSize = 15f })
        column.addView(label(R.string.url_label))
        url = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://homeassistant.local:8123"
            setText(settings.baseUrl)
        }
        column.addView(url)
        column.addView(label(R.string.webhook_label))
        webhook = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; setText(settings.webhookId) }
        column.addView(webhook)
        column.addView(label(R.string.send_label))
        val choice = settings.choice
        val values = Switch(this).apply { setText(R.string.send_values); isChecked = choice.values }
        val parking = Switch(this).apply { setText(R.string.send_parking); isChecked = choice.parking }
        val services = Switch(this).apply { setText(R.string.send_services); isChecked = choice.services }
        for (s in listOf(values, parking, services)) column.addView(s)
        fun save() {
            settings.baseUrl = url.text.toString()
            settings.webhookId = webhook.text.toString().ifBlank { settings.webhookId }
            settings.choice = HaChoice(values.isChecked, parking.isChecked, services.isChecked)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            setText(R.string.save)
            setOnClickListener {
                save()
                show()
                Toast.makeText(this@InfoActivity, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            setText(R.string.send_now)
            setOnClickListener { save(); sendNow() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad) }
        column.addView(status)
        column.addView(label(R.string.yaml_label))
        column.addView(Button(this).apply {
            setText(R.string.copy_yaml)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Drivelog", yaml.text))
                Toast.makeText(this@InfoActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        })
        yaml = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        column.addView(yaml)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /**
     * "Send now": the state held here goes at once, and Drivelog is asked for a fresh one
     * ([CarPluginContract.ACTION_EXPORT_REQUEST]), which goes too when it comes, even unchanged.
     */
    private fun sendNow() {
        if (settings.webhookUrl == null) {
            Toast.makeText(this, R.string.no_url, Toast.LENGTH_LONG).show()
            return
        }
        settings.forceUntilMs = System.currentTimeMillis() + FRESH_WAIT_MS
        sendBroadcast(Intent(CarPluginContract.ACTION_EXPORT_REQUEST).setPackage(CarPluginContract.DRIVELOG_PACKAGE), CarPluginContract.PERMISSION)
        status.text = getString(R.string.sending)
        io.execute {
            if (settings.state != null) Sender.send(settings, force = true)
            runOnUiThread { if (!isDestroyed) show() }
        }
        // the fresh state from Drivelog is sent by ExportReceiver a moment later: show how that went
        status.postDelayed({ if (!isDestroyed) show() }, REFRESH_MS)
    }

    private fun show() {
        val at = settings.lastAtMs
        status.text = when {
            settings.state == null -> getString(R.string.no_state)
            at == 0L -> getString(R.string.not_sent)
            settings.lastError.isEmpty() -> getString(R.string.sent_ok, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)))
            else -> getString(R.string.sent_error, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)), settings.lastError)
        }
        yaml.text = HaYaml.of(settings.webhookId, HaPayload.vehicles(settings.state), settings.choice)
    }

    private companion object {
        /** How long a fresh state from Drivelog is sent even unchanged, after "Send now". */
        const val FRESH_WAIT_MS = 30_000L
        const val REFRESH_MS = 4_000L
    }
}
