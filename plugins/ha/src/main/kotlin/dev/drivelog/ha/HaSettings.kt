package dev.drivelog.ha

import android.content.Context
import java.security.SecureRandom

/** What the plugin keeps: where Home Assistant is, what may go there, the latest state from Drivelog, the last send. */
class HaSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ha", Context.MODE_PRIVATE)

    /** Home Assistant's address, e.g. http://homeassistant.local:8123; empty until set. */
    var baseUrl: String
        get() = prefs.getString(URL, "").orEmpty()
        set(value) { prefs.edit().putString(URL, value.trim().trimEnd('/')).apply() }

    /** The webhook's id: made up once (hard to guess, since a webhook needs no password), changeable. */
    var webhookId: String
        get() = prefs.getString(WEBHOOK, null) ?: newId().also { webhookId = it }
        set(value) { prefs.edit().putString(WEBHOOK, value.trim()).apply() }

    var choice: HaChoice
        get() = HaChoice(prefs.getBoolean(VALUES, true), prefs.getBoolean(PARKING, true), prefs.getBoolean(SERVICES, true))
        set(value) { prefs.edit().putBoolean(VALUES, value.values).putBoolean(PARKING, value.parking).putBoolean(SERVICES, value.services).apply() }

    /** The latest state Drivelog sent (its JSON), to send again or to make the YAML from. */
    var state: String?
        get() = prefs.getString(STATE, null)
        set(value) { prefs.edit().putString(STATE, value).apply() }

    /** The body sent last with success: the same one is not sent again. */
    var sentBody: String?
        get() = prefs.getString(SENT, null)
        set(value) { prefs.edit().putString(SENT, value).apply() }

    /** Until when a state from Drivelog is sent even when it did not change: just after "Send now" asked for it. */
    var forceUntilMs: Long
        get() = prefs.getLong(FORCE_UNTIL, 0L)
        set(value) { prefs.edit().putLong(FORCE_UNTIL, value).apply() }

    /** The last send: when, and the error ("" when it went through). */
    val lastAtMs: Long get() = prefs.getLong(LAST_AT, 0L)
    val lastError: String get() = prefs.getString(LAST_ERROR, "").orEmpty()

    fun setResult(atMs: Long, error: String) {
        prefs.edit().putLong(LAST_AT, atMs).putString(LAST_ERROR, error).apply()
    }

    /** The webhook's full address, or null without a Home Assistant address. */
    val webhookUrl: String? get() = baseUrl.takeIf { it.isNotEmpty() }?.let { "$it/api/webhook/$webhookId" }

    private companion object {
        const val URL = "url"
        const val WEBHOOK = "webhook"
        const val VALUES = "values"
        const val PARKING = "parking"
        const val SERVICES = "services"
        const val STATE = "state"
        const val SENT = "sent"
        const val LAST_AT = "lastAt"
        const val LAST_ERROR = "lastError"
        const val FORCE_UNTIL = "forceUntil"

        fun newId(): String {
            val bytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
            return "drivelog_" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
