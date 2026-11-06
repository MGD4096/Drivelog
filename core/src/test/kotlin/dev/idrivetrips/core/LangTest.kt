package dev.idrivetrips.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LangTest {
    @After
    fun backToEnglish() { Lang.code = "en" }

    @Test
    fun englishIsReturnedAsItIsWithTheArgumentsFilledIn() {
        Lang.code = "en"
        assertEquals("Spent in 2026: 1 200 zł", tr("Spent in {0}: {1}", 2026, "1 200 zł"))
        assertEquals("Anything at all", tr("Anything at all"))
    }

    @Test
    fun polishIsUsedWhenChosenAndUnknownTextsStayEnglish() {
        Lang.code = "pl"
        assertEquals("Spent in 2026: x".replace("Spent in", "Wydano w"), tr("Spent in {0}: {1}", 2026, "x"))
        assertEquals("Text nobody translated", tr("Text nobody translated"))
    }

    @Test
    fun theSettingFollowsThePhoneOnlyForLanguagesTheAppHas() {
        assertEquals("pl", Lang.resolve(Lang.SYSTEM, "pl"))
        assertEquals("pl", Lang.resolve(null, "PL"))
        assertEquals("en", Lang.resolve(Lang.SYSTEM, "de"))
        assertEquals("en", Lang.resolve("en", "pl"))
        assertEquals("pl", Lang.resolve("pl", "en"))
        assertEquals("en", Lang.resolve("xx", "pl"))
    }

    @Test
    fun polishPluralsFollowTheThreeForms() {
        Lang.code = "pl"
        val forms = listOf(1L to "1 dzień", 2L to "2 dni", 5L to "5 dni", 12L to "12 dni", 22L to "22 dni", 25L to "25 dni", 0L to "0 dni")
        for ((n, text) in forms) assertEquals(text, trn(n, "{0} day", "{0} days"))
        assertEquals("1 przejazd", trn(1, "{0} trip", "{0} trips"))
        assertEquals("3 przejazdy", trn(3, "{0} trip", "{0} trips"))
        assertEquals("14 przejazdów", trn(14, "{0} trip", "{0} trips"))
        Lang.code = "en"
        assertEquals("1 day", trn(1, "{0} day", "{0} days"))
        assertEquals("3 days", trn(3, "{0} day", "{0} days"))
    }

    @Test
    fun everyTranslationKeepsThePlaceholdersOfItsEnglishText() {
        val placeholder = Regex("\\{\\d}")
        for ((en, pl) in Polish.words) {
            assertEquals(en, placeholder.findAll(en).map { it.value }.sorted().toList(), placeholder.findAll(pl).map { it.value }.sorted().toList())
        }
        for ((en, pl) in Polish.plurals) {
            val forms = pl.split("|")
            assertEquals(3, forms.size)
            assertTrue(en, forms.all { it.contains("{0}") })
        }
    }

    /** Every text the code passes to tr(...) has a Polish translation, so none is forgotten when a text is added. */
    @Test
    fun everyTextInTheSourceIsTranslated() {
        val root = listOf(File("."), File("..")).firstOrNull { File(it, "app/src/main/kotlin").isDirectory } ?: return
        val literal = "\"((?:[^\"\\\\]|\\\\.)*)\""
        val single = Regex("(?<![A-Za-z_.])tr\\($literal")
        val plural = Regex("(?<![A-Za-z_.])trn\\([^,]+,\\s*$literal,\\s*$literal")
        val missing = ArrayList<String>()
        for (dir in listOf("app/src/main/kotlin", "core/src/main/kotlin")) {
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Lang.kt" && it.name != "Polish.kt" }.forEach { file ->
                val text = file.readText()
                for (m in single.findAll(text)) if (unescape(m.groupValues[1]) !in Polish.words) missing += "${file.name}: ${m.groupValues[1]}"
                for (m in plural.findAll(text)) if (unescape(m.groupValues[2]) !in Polish.plurals) missing += "${file.name}: plural ${m.groupValues[2]}"
            }
        }
        assertTrue("Not translated:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** Texts that are looked up by variable, not written out where they are used, so the scan above cannot see them. */
    @Test
    fun theNamesOfEventTypesAndCarPartsAreTranslated() {
        Lang.code = "pl"
        for (t in EventType.values()) assertTrue(t.toString(), t.label != tr("zzz") && Polish.words.containsKey(englishOf(t)))
        for (p in CarPart.values()) assertTrue(p.toString(), Polish.words.containsKey(p.label))
    }

    private fun englishOf(t: EventType): String {
        Lang.code = "en"
        val en = t.label
        Lang.code = "pl"
        return en
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t')
                    'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
