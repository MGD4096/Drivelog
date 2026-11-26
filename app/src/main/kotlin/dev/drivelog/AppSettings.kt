package dev.drivelog

import android.content.Context
import dev.drivelog.core.car.DetectedRefuel
import dev.drivelog.core.car.ReminderSettings
import dev.drivelog.core.text.Lang
import dev.drivelog.core.text.Texts

/** The few things the person can set in the app's settings screen. */
object AppSettings {
    private const val FILE = "app_settings"
    private const val KEY_ENABLED = "remind_enabled"
    private const val KEY_DAYS = "warn_days"
    private const val KEY_KM = "warn_km"
    private const val KEY_NOTIFIED = "reminders_notified"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"
    private const val KEY_DRIVER = "driver"
    private const val KEY_DISMISSED_REFUELS = "dismissed_refuels"
    private const val KEY_BACKUP_FOLDER = "auto_backup_folder"
    private const val KEY_BACKUP_LAST = "auto_backup_last"
    private const val KEY_BACKUP_ERROR = "auto_backup_error"

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

    /** The texts of the language last used, kept: the widgets apply the language every time they draw. */
    private var texts: Pair<String, Texts>? = null

    /** English needs none (the code's own texts); any other language is read from the string resources once. */
    @Synchronized
    private fun textsFor(c: Context, code: String): Texts {
        if (code == "en") return Texts.ENGLISH
        texts?.let { if (it.first == code) return it.second }
        return ResourceTexts(c.applicationContext, code).also { texts = code to it }
    }

    /** Makes the app's texts, dates and numbers use the chosen language; call at start and after a change. */
    fun applyLanguage(c: Context) {
        val phone = android.content.res.Resources.getSystem().configuration.locales[0]
        val code = Lang.resolve(language(c), phone.language)
        Lang.texts = textsFor(c, code)
        Lang.code = code
        // Dates and numbers: the phone's own style, unless a language was picked here
        java.util.Locale.setDefault(if (language(c) == Lang.SYSTEM) phone else java.util.Locale(code, if (code == "pl") "PL" else ""))
    }

    /** The driver's name for the mileage log: filled in for new entries; null when never set. */
    fun driver(c: Context): String? = prefs(c).getString(KEY_DRIVER, null)?.takeIf { it.isNotBlank() }

    fun setDriver(c: Context, name: String?) = prefs(c).edit().putString(KEY_DRIVER, name?.trim()).apply()

    /** Detected refuellings the person said were not worth an entry ([DetectedRefuel.key]). */
    fun dismissedRefuels(c: Context): Set<String> = prefs(c).getStringSet(KEY_DISMISSED_REFUELS, emptySet())?.toSet() ?: emptySet()

    fun dismissRefuel(c: Context, key: String) =
        prefs(c).edit().putStringSet(KEY_DISMISSED_REFUELS, HashSet(dismissedRefuels(c).toList().takeLast(200) + key)).apply()

    /** Which reminder stages have been announced already (see ReminderPlanner). */
    /** The folder (a document tree URI) that gets a backup every week, or null when the automatic backup is off. */
    fun autoBackupFolder(c: Context): String? = prefs(c).getString(KEY_BACKUP_FOLDER, null)

    fun setAutoBackupFolder(c: Context, uri: String?) =
        prefs(c).edit().putString(KEY_BACKUP_FOLDER, uri).remove(KEY_BACKUP_LAST).remove(KEY_BACKUP_ERROR).apply()

    /** When the last automatic backup was written (0: never), and why the last try failed (null: it did not). */
    fun autoBackupLast(c: Context): Long = prefs(c).getLong(KEY_BACKUP_LAST, 0L)
    fun autoBackupError(c: Context): String? = prefs(c).getString(KEY_BACKUP_ERROR, null)

    fun setAutoBackupResult(c: Context, atMs: Long?, error: String?) = prefs(c).edit().apply {
        if (atMs != null) putLong(KEY_BACKUP_LAST, atMs)
        putString(KEY_BACKUP_ERROR, error)
    }.apply()

    fun notified(c: Context): Set<String> = prefs(c).getStringSet(KEY_NOTIFIED, emptySet())?.toSet() ?: emptySet()

    fun setNotified(c: Context, keys: Set<String>) = prefs(c).edit().putStringSet(KEY_NOTIFIED, HashSet(keys)).apply()
}
