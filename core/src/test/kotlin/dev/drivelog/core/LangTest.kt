package dev.drivelog.core

import dev.drivelog.core.car.CarPart
import dev.drivelog.core.car.EventType
import dev.drivelog.core.text.Lang
import dev.drivelog.core.text.Texts
import dev.drivelog.core.text.tr
import dev.drivelog.core.text.trn
import dev.drivelog.core.trip.TripKind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The texts: written in English in the code ([tr], [trn]), translated in the app's string resources
 * (`app/src/main/res/values/strings.xml` and `values-pl/strings.xml`, read here by [XmlTexts]).
 */
class LangTest {
    @Before
    fun filesAreThere() = assumeTrue("the app's resources are not next to this module", XmlTexts.root != null)

    @After
    fun backToEnglish() {
        Lang.code = "en"
        Lang.texts = Texts.ENGLISH
    }

    private fun polish() {
        Lang.code = "pl"
        Lang.texts = XmlTexts.polish()
    }

    @Test
    fun englishIsReturnedAsItIsWithTheArgumentsFilledIn() {
        assertEquals("Driver: Jan", tr("Driver: {0}", "Jan"))
        assertEquals("Anything at all", tr("Anything at all"))
    }

    @Test
    fun polishIsUsedWhenChosenAndUnknownTextsStayEnglish() {
        polish()
        assertEquals("Kierowca: Jan", tr("Driver: {0}", "Jan"))
        assertEquals(" (uchylone)", tr(" (tilted)"))                       // spaces at the ends survive the XML
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
    fun pluralsUseTheLanguagesOwnForms() {
        assertEquals("1 trip", trn(1, "{0} trip", "{0} trips"))
        assertEquals("3 trips", trn(3, "{0} trip", "{0} trips"))
        polish()
        val days = listOf(1L to "1 dzień", 2L to "2 dni", 5L to "5 dni", 12L to "12 dni", 22L to "22 dni", 0L to "0 dni")
        for ((n, text) in days) assertEquals(text, trn(n, "{0} day", "{0} days"))
        assertEquals("1 przejazd", trn(1, "{0} trip", "{0} trips"))
        assertEquals("3 przejazdy", trn(3, "{0} trip", "{0} trips"))
        assertEquals("14 przejazdów", trn(14, "{0} trip", "{0} trips"))
        assertEquals("24 przejazdy", trn(24, "{0} trip", "{0} trips"))
        assertEquals("a plural without a translation stays English", "5 widgets", trn(5, "{0} widget", "{0} widgets"))
    }

    @Test
    fun bothFilesHaveTheSameTextsAndKeepThePlaceholders() {
        val en = XmlTexts.read(XmlTexts.english())
        val pl = XmlTexts.read(XmlTexts.polishFile())
        assertEquals("strings only in one of the files", en.strings.keys, pl.strings.keys)
        assertEquals("plurals only in one of the files", en.plurals.keys, pl.plurals.keys)
        val placeholder = Regex("\\{\\d}")
        fun holes(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()
        for ((key, text) in en.strings) assertEquals(key, holes(text), holes(pl.strings.getValue(key)))
        for ((key, forms) in pl.plurals) {
            assertEquals(key, setOf("one", "few", "many", "other"), forms.keys)
            assertTrue(key, forms.values.all { it.contains("{0}") })
        }
        val englishTexts = en.strings.values.toList()
        assertEquals("two keys for the same English text", englishTexts.size, englishTexts.toSet().size)
    }

    /** Every text the code passes to tr(...) is in the resources, so none is forgotten when a text is added. */
    @Test
    fun everyTextInTheSourceIsTranslated() {
        val (single, plural) = sourceTexts()
        val texts = XmlTexts.polish()
        val missing = single.filter { it.second !in texts.words }.map { "${it.first}: ${it.second}" } +
            plural.filter { it.second !in texts.plurals }.map { "${it.first}: plural ${it.second}" }
        assertTrue("Not in values/ and values-pl/strings.xml:\n" + missing.distinct().joinToString("\n"), missing.isEmpty())
    }

    /** No translation is kept for a text the code no longer uses (they pile up when texts change). */
    @Test
    fun everyTranslationIsStillUsed() {
        val (single, plural) = sourceTexts()
        val used = single.map { it.second }.toHashSet()
        // looked up by variable: the names of event types, trip kinds and car parts
        used += EventType.values().map { it.label }
        used += TripKind.values().map { it.label }
        used += CarPart.values().map { it.label }
        val texts = XmlTexts.polish()
        val unused = texts.words.keys.filter { it !in used } + texts.plurals.keys.filter { p -> plural.none { it.second == p } }.map { "plural $it" }
        assertTrue("Texts nothing uses (remove them from values/ and values-pl/strings.xml):\n" + unused.joinToString("\n"), unused.isEmpty())
    }

    /** Texts that are looked up by variable, not written out where they are used, so the scan cannot see them. */
    @Test
    fun theNamesOfEventTypesTripKindsAndCarPartsAreTranslated() {
        val texts = XmlTexts.polish()
        for (t in EventType.values()) assertTrue(t.toString(), texts.words.containsKey(t.label))
        for (k in TripKind.values()) assertTrue(k.toString(), texts.words.containsKey(k.label))
        for (p in CarPart.values()) assertTrue(p.toString(), texts.words.containsKey(p.label))
    }

    /** The English texts given to tr(...) and the "other" forms given to trn(...), with the file each is in. */
    private fun sourceTexts(): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val root = XmlTexts.root ?: return emptyList<Pair<String, String>>() to emptyList()
        val literal = "\"((?:[^\"\\\\]|\\\\.)*)\""
        val single = Regex("(?<![A-Za-z_.])tr\\($literal")
        val plural = Regex("(?<![A-Za-z_.])trn\\([^,]+,\\s*$literal,\\s*$literal")
        val singles = ArrayList<Pair<String, String>>()
        val plurals = ArrayList<Pair<String, String>>()
        for (dir in listOf("app/src/main/kotlin", "core/src/main/kotlin")) {
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Lang.kt" }.forEach { file ->
                val text = file.readText()
                for (m in single.findAll(text)) singles += file.name to unescape(m.groupValues[1])
                for (m in plural.findAll(text)) plurals += file.name to unescape(m.groupValues[2])
            }
        }
        return singles to plurals
    }

    /** A Kotlin string literal's content as the text it stands for. */
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
