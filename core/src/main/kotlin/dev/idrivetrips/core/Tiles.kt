package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where map tiles come from. The default is OpenStreetMap's own tile server, whose usage policy
 * (https://operations.osmfoundation.org/policies/tiles/) asks for: a User-Agent that names the app (never a library's
 * default), local caching for at least 7 days, no bulk downloading, the attribution shown on the map, and light use.
 * It is meant for an app like this one, used by one person; an app with many users should use its own tile provider.
 */
data class TileProvider(
    val urlTemplate: String,
    val attribution: String,
    val maxZoom: Int,
    val userAgent: String,
) {
    /** The tile's address, or null when (z, x, y) is not a tile that exists. `x` may be given outside the world: it wraps. */
    fun url(z: Int, x: Int, y: Int): String? {
        if (z < 0 || z > maxZoom) return null
        val count = 1 shl z
        if (y < 0 || y >= count) return null
        return urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", Math.floorMod(x, count).toString())
            .replace("{y}", y.toString())
    }

    companion object {
        val OPEN_STREET_MAP = TileProvider(
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            maxZoom = 19,
            // If you pass this app on, add a way to reach you here, as the OSM policy asks.
            userAgent = "Drivelog/1.0 (personal-use Android app)",
        )
    }
}

/** Downloads single tiles. Only returns bytes that look like a PNG or JPEG picture, and never more than [MAX_BYTES]. */
class TileFetcher(
    private val provider: TileProvider,
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 8000,
) {
    /** The tile's picture, or null on any failure (no network, error status, not a picture, too big). */
    fun fetch(z: Int, x: Int, y: Int): ByteArray? {
        val address = provider.url(z, x, y) ?: return null
        var connection: HttpURLConnection? = null
        try {
            connection = URL(address).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", provider.userAgent)
            connection.setRequestProperty("Accept", "image/png,image/jpeg")
            if (connection.responseCode != 200) return null
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) return null
            val bytes = connection.inputStream.use { readAtMost(it, MAX_BYTES) } ?: return null
            return if (looksLikeImage(bytes)) bytes else null
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 512 * 1024

        fun looksLikeImage(b: ByteArray): Boolean {
            val png = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
            val jpeg = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
            return png || jpeg
        }
    }
}

/**
 * Tiles kept as files `<dir>/<z>/<x>/<y>.tile`, oldest removed first when the total size passes [maxBytes]
 * (the cache is then cut to 80 % of that). The age of a tile is its file's modification time.
 */
class DiskTileCache(
    private val dir: File,
    private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry(val bytes: ByteArray, val savedAtMs: Long)

    private var approximateBytes = -1L

    private fun file(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.tile")

    fun get(z: Int, x: Int, y: Int): Entry? {
        val f = file(z, x, y)
        return try { if (f.isFile) Entry(f.readBytes(), f.lastModified()) else null } catch (e: IOException) { null }
    }

    fun put(z: Int, x: Int, y: Int, bytes: ByteArray) {
        val target = file(z, x, y)
        try {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            temporary.setLastModified(clock())
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            return      // a full or unwritable disk only means the tile is fetched again next time
        }
        synchronized(this) {
            if (approximateBytes < 0) approximateBytes = totalBytes()
            else approximateBytes += bytes.size
            if (approximateBytes > maxBytes) trim()
        }
    }

    fun totalBytes(): Long = tiles().sumOf { it.length() }

    private fun tiles(): List<File> = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tile") }.toList()

    private fun trim() {
        val all = tiles().sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() }
        val goal = maxBytes * 8 / 10
        for (f in all) {
            if (total <= goal) break
            val size = f.length()
            if (f.delete()) total -= size
        }
        approximateBytes = total
    }
}

/**
 * Cache first, network second. A tile younger than [maxAgeMs] (7 days, the OSM minimum) is never downloaded again. An
 * older one is refreshed, and if that fails (no network) the old picture is still used, because an old map beats none.
 */
class TileSource(
    private val cache: DiskTileCache,
    private val fetcher: TileFetcher,
    private val maxAgeMs: Long = SEVEN_DAYS_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** May block on the network: call it from a background thread. Null when there is no picture at all. */
    fun load(z: Int, x: Int, y: Int): ByteArray? {
        val cached = cache.get(z, x, y)
        if (cached != null && clock() - cached.savedAtMs <= maxAgeMs) return cached.bytes
        val fresh = fetcher.fetch(z, x, y)
        if (fresh != null) {
            cache.put(z, x, y, fresh)
            return fresh
        }
        return cached?.bytes
    }

    companion object {
        const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }
}
