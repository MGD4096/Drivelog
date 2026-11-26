package dev.drivelog.core

import dev.drivelog.core.text.Texts
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's string resources as the tests see them: the `t_…` strings and `p_…` plurals of
 * `app/src/main/res/values/strings.xml` (English) and of a language's folder, read the way Android reads them (quotes,
 * `\'`, `\n` …). [polish] is what the app gets from [dev.drivelog.ResourceTexts] when Polish is chosen.
 */
class XmlTexts(english: File, local: File, private val pluralOf: (Long) -> String) : Texts {
    /** English text → translation. */
    val words: Map<String, String>
    /** English "other" form → the language's forms by quantity ("one", "few", …). */
    val plurals: Map<String, Map<String, String>>
    /** English "other" form → the English "one" form. */
    val englishOne: Map<String, String>

    init {
        val en = read(english)
        val loc = read(local)
        words = en.strings.mapNotNull { (k, v) -> loc.strings[k]?.let { v to it } }.toMap()
        plurals = en.plurals.mapNotNull { (k, forms) -> loc.plurals[k]?.let { forms.getValue("other") to it } }.toMap()
        englishOne = en.plurals.values.associate { it.getValue("other") to it.getValue("one") }
    }

    override fun word(en: String): String? = words[en]

    override fun plural(n: Long, one: String, other: String): String? = plurals[other]?.let { it[pluralOf(n)] ?: it["other"] }

    class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    companion object {
        /** The repository's root, from the module the tests run in; null when the files are not there. */
        val root: File? = listOf(File("."), File("..")).firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }

        fun english(): File = File(root, "app/src/main/res/values/strings.xml")
        fun polishFile(): File = File(root, "app/src/main/res/values-pl/strings.xml")

        /** Polish plural categories, as Android (CLDR) has them for whole numbers. */
        fun polishCategory(n: Long): String {
            if (n == 1L) return "one"
            val last = n % 10
            val lastTwo = n % 100
            return if (last in 2..4 && lastTwo !in 12..14) "few" else "many"
        }

        fun polish(): XmlTexts = XmlTexts(english(), polishFile(), ::polishCategory)

        /** The `t_…` strings and `p_…` plurals of one strings.xml, unescaped. */
        fun read(file: File): Resources {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                when {
                    e.tagName == "string" && name.startsWith("t_") -> strings[name] = unescape(e.textContent)
                    e.tagName == "plurals" && name.startsWith("p_") -> {
                        val items = e.getElementsByTagName("item")
                        plurals[name] = (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to unescape(it.textContent) }
                    }
                }
            }
            return Resources(strings, plurals)
        }

        /** What Android makes of a string resource's text: quoted parts keep their spaces, backslash escapes. */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        when (val n = raw[i + 1]) { 'n' -> out.append('\n'); 't' -> out.append('\t'); else -> out.append(n) }
                        i += 2
                        continue
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
                    else -> out.append(c)
                }
                i++
            }
            return if (raw.trim().startsWith("\"")) out.toString() else out.toString().trim()
        }
    }
}
