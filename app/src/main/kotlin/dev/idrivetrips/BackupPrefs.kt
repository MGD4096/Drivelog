package dev.idrivetrips

import android.content.Context
import dev.idrivetrips.core.Json
import dev.idrivetrips.core.Lang

/** The settings that live outside the data folder (the list of cars, reminders, language), as one small text for a backup. */
object BackupPrefs {
    const val NAME = "settings.json"

    fun collect(c: Context): Map<String, String> {
        val reminders = AppSettings.reminders(c)
        val json = Json.stringify(
            linkedMapOf(
                "cars" to Prefs.cars(c).map { linkedMapOf("address" to it.address, "name" to it.name) },
                "remindersEnabled" to reminders.enabled,
                "warnDays" to reminders.warnDays.toDouble(),
                "warnKm" to reminders.warnKm,
                "language" to AppSettings.language(c),
            )
        )
        return mapOf(NAME to json)
    }

    /** Puts the settings of a backup back; a backup without them leaves the current settings as they are. */
    fun restore(c: Context, extras: Map<String, String>) {
        val m = extras[NAME]?.let { try { Json.parseObject(it) } catch (e: Exception) { null } } ?: return
        val cars = (m["cars"] as? List<*>)?.mapNotNull { item ->
            val car = item as? Map<*, *> ?: return@mapNotNull null
            val address = car["address"] as? String ?: return@mapNotNull null
            CarRef(address, (car["name"] as? String) ?: address)
        } ?: emptyList()
        Prefs.setCars(c, cars)
        (m["remindersEnabled"] as? Boolean)?.let { AppSettings.setRemindersEnabled(c, it) }
        (m["warnDays"] as? Double)?.toInt()?.takeIf { it in 1..365 }?.let { AppSettings.setWarnDays(c, it) }
        (m["warnKm"] as? Double)?.toInt()?.takeIf { it in 1..100_000 }?.let { AppSettings.setWarnKm(c, it) }
        (m["language"] as? String)?.takeIf { it == Lang.SYSTEM || Lang.available.any { l -> l.first == it } }?.let { AppSettings.setLanguage(c, it) }
        AppSettings.setNotified(c, emptySet())
    }
}
