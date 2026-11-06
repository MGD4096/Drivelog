package dev.idrivetrips

import android.content.Context
import dev.idrivetrips.core.Lang
import dev.idrivetrips.core.ReminderSettings

/** The few things the person can set in the app's settings screen. */
object AppSettings {
    private const val FILE = "app_settings"
    private const val KEY_ENABLED = "remind_enabled"
    private const val KEY_DAYS = "warn_days"
    private const val KEY_KM = "warn_km"
    private const val KEY_NOTIFIED = "reminders_notified"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"

    val DAY_CHOICES = listOf(3, 7, 14, 21, 30, 45, 60)
    val KM_CHOICES = listOf(500, 1000, 1500, 2000, 3000, 5000)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun reminders(c: Context): ReminderSettings {
        val p = prefs(c)
        return ReminderSettings(
            enabled = p.getBoolean(KEY_ENABLED, true),
            warnDays = p.getInt(KEY_DAYS, 14).toLong(),
            warnKm = p.getInt(KEY_KM, 1500).toDouble(),
        )
    }

    fun setRemindersEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    fun setWarnDays(c: Context, days: Int) = prefs(c).edit().putInt(KEY_DAYS, days).apply()
    fun setWarnKm(c: Context, km: Int) = prefs(c).edit().putInt(KEY_KM, km).apply()

    /** [Lang.SYSTEM] or a language code from [Lang.available]. */
    fun language(c: Context): String = prefs(c).getString(KEY_LANGUAGE, Lang.SYSTEM) ?: Lang.SYSTEM

    fun setLanguage(c: Context, choice: String) = prefs(c).edit().putString(KEY_LANGUAGE, choice).apply()

    /** [Colors.SYSTEM], [Colors.LIGHT] or [Colors.DARK]. */
    fun theme(c: Context): String = prefs(c).getString(KEY_THEME, Colors.SYSTEM) ?: Colors.SYSTEM

    fun setTheme(c: Context, choice: String) = prefs(c).edit().putString(KEY_THEME, choice).apply()

    /** Makes the app's texts, dates and numbers use the chosen language; call at start and after a change. */
    fun applyLanguage(c: Context) {
        val phone = android.content.res.Resources.getSystem().configuration.locales[0]
        val code = Lang.resolve(language(c), phone.language)
        Lang.code = code
        // Dates and numbers: the phone's own style, unless a language was picked here
        java.util.Locale.setDefault(if (language(c) == Lang.SYSTEM) phone else java.util.Locale(code, if (code == "pl") "PL" else ""))
    }

    /** Which reminder stages have been announced already (see ReminderPlanner). */
    fun notified(c: Context): Set<String> = prefs(c).getStringSet(KEY_NOTIFIED, emptySet())?.toSet() ?: emptySet()

    fun setNotified(c: Context, keys: Set<String>) = prefs(c).edit().putStringSet(KEY_NOTIFIED, HashSet(keys)).apply()
}
