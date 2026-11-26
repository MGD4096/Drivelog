package dev.drivelog.core

import java.util.Locale
/** The outcome of reading a text field: the value (null when left empty), or a message for the person when it is wrong. */
class Parsed<out T>(val value: T?, val error: String? = null) {
    val ok: Boolean get() = error == null
}

object Vin {
    /** Upper case, without spaces and dashes. */
    fun normalize(raw: String): String = raw.filter { !it.isWhitespace() && it != '-' }.uppercase(Locale.ROOT)

    /**
     * A VIN has 17 letters and digits, never I, O or Q. The check digit is not tested: only North American VINs have a
     * valid one, European VINs usually do not.
     */
    fun isValid(vin: String): Boolean = vin.length == 17 && vin.all { (it in 'A'..'Z' || it in '0'..'9') && it != 'I' && it != 'O' && it != 'Q' }

    fun parse(text: String): Parsed<String> {
        val v = normalize(text)
        if (v.isEmpty()) return Parsed(null)
        if (v.length != 17) return Parsed(null, tr("A VIN has 17 characters (you typed {0}).", v.length))
        if (!isValid(v)) return Parsed(null, tr("A VIN uses only letters and digits, and never I, O or Q."))
        return Parsed(v)
    }
}

object CarInput {
    const val FIRST_YEAR = 1950

    fun parseYear(text: String, currentYear: Int): Parsed<Int> {
        val t = text.trim()
        if (t.isEmpty()) return Parsed(null)
        val y = t.toIntOrNull()
        if (y == null || y < FIRST_YEAR || y > currentYear + 1) return Parsed(null, tr("Year must be between {0} and {1}.", FIRST_YEAR, currentYear + 1))
        return Parsed(y)
    }

    /** An odometer reading typed in by hand, in kilometres. Empty means not given. */
    fun parseOdometerKm(text: String): Parsed<Double> = parseDecimal(text, tr("Odometer"), 0.0, 3_000_000.0)

    /** A plain number such as a price or a distance; a comma may be the decimal mark. Empty means not given. */
    fun parseDecimal(text: String, what: String, min: Double, max: Double): Parsed<Double> {
        val t = text.trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return Parsed(null)
        val v = t.toDoubleOrNull()
        if (v == null || v.isNaN() || v.isInfinite() || v < min || v > max) return Parsed(null, tr("{0} must be a number from {1} to {2}.", what, min.toLong(), max.toLong()))
        return Parsed(v)
    }

    /**
     * Engine size as people write it: "2.0", "2,0 l", "1998", "1998 cc". Small numbers (or any with a decimal point, or
     * ending in "l") are litres, otherwise cubic centimetres. The result is in cubic centimetres.
     */
    fun parseEngineCc(text: String): Parsed<Int> {
        var t = text.trim().lowercase(Locale.ROOT).replace(',', '.')
        if (t.isEmpty()) return Parsed(null)
        var unit: String? = null
        for (u in listOf("cm3", "ccm", "cc", "litres", "liters", "litre", "liter", "l")) {
            if (t.endsWith(u)) { unit = u; t = t.removeSuffix(u).trim(); break }
        }
        val n = t.toDoubleOrNull() ?: return Parsed(null, tr("Engine size: use litres (2.0) or cubic centimetres (1998)."))
        val litres = when {
            unit == "cc" || unit == "ccm" || unit == "cm3" -> false
            unit != null -> true                          // l, litre(s), liter(s)
            t.contains('.') || n <= 20.0 -> true
            else -> false
        }
        val cc = if (litres) Math.round(n * 1000.0).toInt() else Math.round(n).toInt()
        if (cc < 50 || cc > 20_000) return Parsed(null, tr("Engine size must be between 0.05 and 20 litres."))
        return Parsed(cc)
    }
}
