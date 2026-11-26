package dev.drivelog

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import dev.drivelog.core.text.Texts
import java.util.Locale

/**
 * The app's texts in [language], from its string resources: every `t_…` string and `p_…` plural of
 * `res/values/strings.xml` (English, what the code writes) paired with the same one in the language's folder
 * (`values-pl/…`). The code keeps the English text ([dev.drivelog.core.text.tr]); this turns it into the translation.
 * Plurals follow Android's rules for the language (Polish: one, few, many). Built once per language ([AppSettings]).
 */
class ResourceTexts(context: Context, language: String) : Texts {
    private val local: Resources = resources(context, Locale(language))
    private val words = HashMap<String, String>()
    private val plurals = HashMap<String, Int>()

    init {
        val english = resources(context, Locale.ENGLISH)
        for (field in R.string::class.java.fields) {
            if (!field.name.startsWith("t_")) continue
            val id = field.getInt(null)
            words[english.getString(id)] = local.getString(id)
        }
        for (field in R.plurals::class.java.fields) {
            if (!field.name.startsWith("p_")) continue
            val id = field.getInt(null)
            plurals[english.getQuantityString(id, OTHER)] = id         // keyed by the English "other" form
        }
    }

    override fun word(en: String): String? = words[en]

    override fun plural(n: Long, one: String, other: String): String? =
        plurals[other]?.let { local.getQuantityString(it, n.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()) }

    private companion object {
        /** A count that is "other" in English. */
        const val OTHER = 5

        fun resources(context: Context, locale: Locale): Resources {
            val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
            return context.createConfigurationContext(config).resources
        }
    }
}
