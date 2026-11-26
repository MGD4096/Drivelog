package dev.drivelog.core.text

/**
 * Where the translations come from. The texts themselves live in the app's string resources
 * (`app/src/main/res/values/strings.xml` in English, `values-pl/strings.xml` in Polish); this module knows nothing of
 * Android, so the app reads them and hands them over ([Lang.texts]); the tests read the same files.
 */
interface Texts {
    /** The translation of the English text [en], or null when there is none. */
    fun word(en: String): String?

    /** The form for [n] of the plural whose English forms are [one] and [other], or null when there is none. */
    fun plural(n: Long, one: String, other: String): String?

    companion object {
        /** No translations: every text stays as it is written in the code. */
        val ENGLISH: Texts = object : Texts {
            override fun word(en: String): String? = null
            override fun plural(n: Long, one: String, other: String): String? = null
        }
    }
}

/**
 * The language of the app's texts. The English text is the key: [tr] returns it as it is, or its translation from
 * [texts]. Placeholders are written {0}, {1}, … and filled from the arguments, so a translation may put them wherever
 * its grammar wants. Dates, numbers and money are formatted by the caller, in the phone's locale.
 */
object Lang {
    /** The language codes the app has texts for, with the name each is written in. */
    val available: List<Pair<String, String>> = listOf("en" to "English", "pl" to "Polski")

    /** What the person picked: [SYSTEM] follows the phone, otherwise a code from [available]. */
    const val SYSTEM = "system"

    /** The language in use (a code from [available]); the CSV export and a few screens look at it. */
    @Volatile var code: String = "en"

    /** The translations of [code]; set together with it ([Texts.ENGLISH] for English). */
    @Volatile var texts: Texts = Texts.ENGLISH

    /** The code to use for the setting [choice] on a phone whose language is [systemLanguage] ("pl", "de", …). */
    fun resolve(choice: String?, systemLanguage: String): String {
        val wanted = if (choice == null || choice == SYSTEM) systemLanguage.lowercase() else choice
        return available.firstOrNull { it.first == wanted }?.first ?: "en"
    }

    fun tr(en: String, vararg args: Any?): String = fill(texts.word(en) ?: en, args)

    /** "1 trip" / "5 trips": the language's own form for [n] (Polish has three); both English forms use {0}. */
    fun trn(n: Long, one: String, other: String): String =
        fill(texts.plural(n, one, other) ?: (if (n == 1L) one else other), arrayOf(n))

    private fun fill(text: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return text
        var out = text
        for ((i, a) in args.withIndex()) out = out.replace("{$i}", a?.toString() ?: "")
        return out
    }
}

fun tr(en: String, vararg args: Any?): String = Lang.tr(en, *args)

fun trn(n: Int, one: String, other: String): String = Lang.trn(n.toLong(), one, other)

fun trn(n: Long, one: String, other: String): String = Lang.trn(n, one, other)
