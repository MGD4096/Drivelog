package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Which picture to show for the weather. */
enum class WeatherKind { CLEAR_DAY, CLEAR_NIGHT, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, STORM }

/** The weather at one place, as reported at [fetchedAtMs]. */
data class WeatherNow(
    val tempC: Double,
    val feelsLikeC: Double?,
    /** WMO weather code, as Open-Meteo reports it. */
    val code: Int,
    val windKmh: Double?,
    val isDay: Boolean,
    val fetchedAtMs: Long,
) {
    val description: String get() = Wmo.describe(code)
    val kind: WeatherKind get() = Wmo.kind(code, isDay)
}

/** The WMO weather interpretation codes used by Open-Meteo. */
object Wmo {
    fun describe(code: Int): String = when (code) {
        0 -> tr("Clear sky")
        1 -> tr("Mainly clear")
        2 -> tr("Partly cloudy")
        3 -> tr("Overcast")
        45, 48 -> tr("Fog")
        51 -> tr("Light drizzle")
        53 -> tr("Drizzle")
        55 -> tr("Heavy drizzle")
        56, 57 -> tr("Freezing drizzle")
        61 -> tr("Light rain")
        63 -> tr("Rain")
        65 -> tr("Heavy rain")
        66, 67 -> tr("Freezing rain")
        71 -> tr("Light snow")
        73 -> tr("Snow")
        75 -> tr("Heavy snow")
        77 -> tr("Snow grains")
        80 -> tr("Light showers")
        81 -> tr("Showers")
        82 -> tr("Heavy showers")
        85, 86 -> tr("Snow showers")
        95 -> tr("Thunderstorm")
        96, 99 -> tr("Thunderstorm with hail")
        else -> tr("Unknown")
    }

    fun kind(code: Int, isDay: Boolean): WeatherKind = when (code) {
        0, 1 -> if (isDay) WeatherKind.CLEAR_DAY else WeatherKind.CLEAR_NIGHT
        2 -> WeatherKind.PARTLY_CLOUDY
        3 -> WeatherKind.CLOUDY
        45, 48 -> WeatherKind.FOG
        in 51..67, in 80..82 -> WeatherKind.RAIN
        in 71..77, 85, 86 -> WeatherKind.SNOW
        95, 96, 99 -> WeatherKind.STORM
        else -> WeatherKind.CLOUDY
    }
}

object OpenMeteo {
    const val BASE_URL = "https://api.open-meteo.com"
    const val ATTRIBUTION = "Weather data by Open-Meteo.com"

    /** Coordinates are rounded to two decimals (about a kilometre): the server never learns the exact parking spot. */
    fun url(base: String, lat: Double, lon: Double): String = String.format(
        Locale.ROOT,
        "%s/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day&timezone=auto",
        base.trimEnd('/'), lat, lon,
    )

    /** Reads the answer of the forecast API. Null when it is not what was asked for. */
    fun parse(json: String, fetchedAtMs: Long): WeatherNow? {
        val root = try { Json.parseObject(json) } catch (e: Json.JsonException) { return null } ?: return null
        val current = root["current"] as? Map<*, *> ?: return null
        val temp = current["temperature_2m"] as? Double ?: return null
        val code = (current["weather_code"] as? Double)?.toInt() ?: return null
        if (temp.isNaN() || temp < -90.0 || temp > 65.0) return null
        val feels = (current["apparent_temperature"] as? Double)?.takeIf { it >= -120.0 && it <= 80.0 }
        val wind = (current["wind_speed_10m"] as? Double)?.takeIf { it >= 0.0 && it < 500.0 }
        val day = (current["is_day"] as? Double)?.let { it != 0.0 } ?: true
        return WeatherNow(temp, feels, code, wind, day, fetchedAtMs)
    }
}

/** Fetches the current weather for a place. One short request, no key needed. */
class WeatherClient(
    private val baseUrl: String = OpenMeteo.BASE_URL,
    private val userAgent: String = "Drivelog/1.0 (personal-use Android app)",
    private val timeoutMs: Int = 8000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun fetch(lat: Double, lon: Double): WeatherNow? {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(OpenMeteo.url(baseUrl, lat, lon)).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return null
            if (connection.contentLengthLong > MAX_BYTES) return null
            val body = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
            return OpenMeteo.parse(body, clock())
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * The weather with a small cache in one file, so that the list of cars does not ask the server again and again and
 * still shows something with no network. A reading younger than [freshMs] is used as it is; an older one only when the
 * server cannot be reached and it is not older than [staleMs]. Places are told apart to about one kilometre.
 */
class WeatherSource(
    private val cacheFile: File,
    private val client: WeatherClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val freshMs: Long = 30L * 60 * 1000,
    private val staleMs: Long = 6L * 60 * 60 * 1000,
) {
    class Result(val weather: WeatherNow, val stale: Boolean)

    /** May block on the network: call it from a background thread. */
    @Synchronized
    fun get(lat: Double, lon: Double): Result? {
        val key = key(lat, lon)
        val cache = readAll()
        val cached = cache[key]
        val now = clock()
        if (cached != null && now - cached.fetchedAtMs in 0..freshMs) return Result(cached, false)
        val fresh = client.fetch(lat, lon)
        if (fresh != null) {
            val updated = LinkedHashMap(cache)
            updated[key] = fresh
            // Keep the file small: only the few most recent places
            val keep = updated.entries.sortedByDescending { it.value.fetchedAtMs }.take(MAX_PLACES).associate { it.key to it.value }
            write(keep)
            return Result(fresh, false)
        }
        if (cached != null && now - cached.fetchedAtMs <= staleMs) return Result(cached, true)
        return null
    }

    private fun key(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.2f,%.2f", lat, lon)

    private fun readAll(): Map<String, WeatherNow> {
        val text = try { if (cacheFile.isFile) cacheFile.readText() else null } catch (e: IOException) { null } ?: return emptyMap()
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, WeatherNow>()
        for ((k, v) in root) {
            val m = v as? Map<*, *> ?: continue
            out[k] = WeatherNow(
                tempC = m["t"] as? Double ?: continue,
                feelsLikeC = m["f"] as? Double,
                code = (m["c"] as? Double)?.toInt() ?: continue,
                windKmh = m["w"] as? Double,
                isDay = (m["d"] as? Boolean) ?: true,
                fetchedAtMs = (m["at"] as? Double)?.toLong() ?: continue,
            )
        }
        return out
    }

    private fun write(map: Map<String, WeatherNow>) {
        val json = Json.stringify(map.mapValues { (_, w) ->
            val m = LinkedHashMap<String, Any?>()
            m["t"] = w.tempC
            w.feelsLikeC?.let { m["f"] = it }
            m["c"] = w.code.toDouble()
            w.windKmh?.let { m["w"] = it }
            m["d"] = w.isDay
            m["at"] = w.fetchedAtMs.toDouble()
            m
        })
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(json)
            try {
                Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // The cache is only a convenience
        }
    }

    companion object {
        const val MAX_PLACES = 20
    }
}

/** A position fix of the phone, with the time it was taken. */
data class Fix(val lat: Double, val lon: Double, val atMs: Long)

object Fixes {
    /** The newest fix that is valid and not older than [maxAgeMs]; null when there is none. */
    fun newest(candidates: List<Fix>, nowMs: Long, maxAgeMs: Long): Fix? = candidates
        .filter { !it.lat.isNaN() && !it.lon.isNaN() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        .filter { nowMs - it.atMs in 0..maxAgeMs }
        .maxByOrNull { it.atMs }
}
