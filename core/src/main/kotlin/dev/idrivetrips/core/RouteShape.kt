package dev.idrivetrips.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class LatLon(val lat: Double, val lon: Double)

/** A point of a drawn route, in view coordinates. */
data class Spot(val x: Float, val y: Float)

/** The outline of a trip: a few dozen points that keep its shape, and the arithmetic to draw it in a box. */
object RouteShape {
    const val DEFAULT_MAX_POINTS = 80

    /** Drops points that are not places, then thins the track out to at most [maxPoints] (Douglas-Peucker); first and last stay. */
    fun simplify(points: List<TrackPoint>, maxPoints: Int = DEFAULT_MAX_POINTS): List<LatLon> {
        val valid = points.filter { !it.lat.isNaN() && !it.lon.isNaN() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            .map { LatLon(it.lat, it.lon) }
        if (valid.size <= 2 || maxPoints < 2) return if (valid.size <= maxPoints) valid else listOf(valid.first(), valid.last())
        val extent = max(
            valid.maxOf { it.lat } - valid.minOf { it.lat },
            (valid.maxOf { it.lon } - valid.minOf { it.lon }) * cos(Math.toRadians(valid.sumOf { it.lat } / valid.size)),
        )
        var eps = extent / 1000.0
        var out = douglasPeucker(valid, eps)
        var guard = 0
        while (out.size > maxPoints && guard++ < 60) {
            eps = if (eps <= 0.0) extent / 1000.0 else eps * 1.4
            out = douglasPeucker(valid, eps)
        }
        return out
    }

    private fun douglasPeucker(p: List<LatLon>, eps: Double): List<LatLon> {
        val keep = BooleanArray(p.size)
        keep[0] = true
        keep[p.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, p.size - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1
            var farD = eps
            for (i in a + 1 until b) {
                val d = distance(p[i], p[a], p[b])
                if (d > farD) { farD = d; far = i }
            }
            if (far >= 0) {
                keep[far] = true
                stack.addLast(intArrayOf(a, far))
                stack.addLast(intArrayOf(far, b))
            }
        }
        return p.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance (in degrees, east-west stretched like on the screen) from [p] to the segment [a]-[b]. */
    private fun distance(p: LatLon, a: LatLon, b: LatLon): Double {
        val k = cos(Math.toRadians(a.lat))
        val ax = a.lon * k; val ay = a.lat
        val bx = b.lon * k; val by = b.lat
        val px = p.lon * k; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Fits the outline into a box of [width] x [height] with [pad] around it, north up, centred, not distorted. */
    fun fit(shape: List<LatLon>, width: Float, height: Float, pad: Float): List<Spot> {
        if (shape.isEmpty()) return emptyList()
        val k = cos(Math.toRadians(shape.sumOf { it.lat } / shape.size))
        val minX = shape.minOf { it.lon * k }; val maxX = shape.maxOf { it.lon * k }
        val minY = shape.minOf { it.lat }; val maxY = shape.maxOf { it.lat }
        val dx = maxX - minX; val dy = maxY - minY
        val availW = max(1f, width - 2 * pad); val availH = max(1f, height - 2 * pad)
        val scale = if (dx <= 0.0 && dy <= 0.0) 0.0 else min(if (dx > 0) availW / dx else Double.MAX_VALUE, if (dy > 0) availH / dy else Double.MAX_VALUE)
        val offX = (width - dx * scale) / 2.0
        val offY = (height - dy * scale) / 2.0
        return shape.map { Spot((offX + (it.lon * k - minX) * scale).toFloat(), (offY + (maxY - it.lat) * scale).toFloat()) }
    }
}

/**
 * The outlines of the trips in one small file, so the trip list does not have to read every track to draw its cards.
 * Only the most recent [MAX_TRIPS] are kept.
 */
class RouteCache(private val file: File) {
    @Synchronized
    fun get(id: String): List<LatLon>? = readAll()[id]

    @Synchronized
    fun put(id: String, shape: List<LatLon>) {
        val all = LinkedHashMap(readAll())
        all.remove(id)
        all[id] = shape
        while (all.size > MAX_TRIPS) all.remove(all.keys.first())
        write(all)
    }

    @Synchronized
    fun remove(id: String) {
        val all = LinkedHashMap(readAll())
        if (all.remove(id) != null) write(all)
    }

    private fun readAll(): Map<String, List<LatLon>> {
        val text = try { if (file.isFile) file.readText() else null } catch (e: IOException) { null } ?: return emptyMap()
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { null } ?: return emptyMap()
        val out = LinkedHashMap<String, List<LatLon>>()
        for ((id, v) in root) {
            val flat = v as? List<*> ?: continue
            val pts = ArrayList<LatLon>()
            var i = 0
            while (i + 1 < flat.size) {
                val la = flat[i] as? Double; val lo = flat[i + 1] as? Double
                if (la != null && lo != null) pts += LatLon(la, lo)
                i += 2
            }
            out[id] = pts
        }
        return out
    }

    private fun write(all: Map<String, List<LatLon>>) {
        val json = Json.stringify(all.mapValues { (_, shape) -> shape.flatMap { listOf(Math.round(it.lat * 1e5) / 1e5, Math.round(it.lon * 1e5) / 1e5) } })
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            // only a convenience
        }
    }

    companion object {
        const val MAX_TRIPS = 300
    }
}
