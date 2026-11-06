package dev.idrivetrips.core

/**
 * The language of the app's texts. The English text is the key: [tr] returns it as it is, or its translation when another
 * language is chosen. Placeholders are written {0}, {1}, … and filled from the arguments, so a translation may put them
 * wherever its grammar wants. Dates, numbers and money are formatted by the caller, in the phone's locale.
 */
object Lang {
    /** The language codes the app has texts for, with the name each is written in. */
    val available: List<Pair<String, String>> = listOf("en" to "English", "pl" to "Polski")

    /** What the person picked: [SYSTEM] follows the phone, otherwise a code from [available]. */
    const val SYSTEM = "system"

    @Volatile var code: String = "en"

    /** The code to use for the setting [choice] on a phone whose language is [systemLanguage] ("pl", "de", …). */
    fun resolve(choice: String?, systemLanguage: String): String {
        val wanted = if (choice == null || choice == SYSTEM) systemLanguage.lowercase() else choice
        return available.firstOrNull { it.first == wanted }?.first ?: "en"
    }

    fun tr(en: String, vararg args: Any?): String = fill(translation(en), args)

    /** "1 trip" / "5 trips": [one] when [n] is 1, otherwise [other]; both use {0} for the number. */
    fun trn(n: Long, one: String, other: String): String {
        val text = when (code) {
            "pl" -> {
                val forms = Polish.plurals[other]?.split("|")
                if (forms != null && forms.size == 3) forms[pluralForm(n)] else if (n == 1L) one else other
            }
            else -> if (n == 1L) one else other
        }
        return fill(text, arrayOf(n))
    }

    /** Polish: 0 = one (1), 1 = few (2–4, 22–24, …), 2 = many (0, 5–21, 25–31, …). */
    fun pluralForm(n: Long): Int {
        if (n == 1L) return 0
        val last = n % 10
        val lastTwo = n % 100
        return if (last in 2..4 && (lastTwo < 12 || lastTwo > 14)) 1 else 2
    }

    private fun translation(en: String): String = when (code) {
        "pl" -> Polish.words[en] ?: en
        else -> en
    }

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
