package dev.idrivetrips.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Web Mercator, the projection of OpenStreetMap tiles. "World" coordinates run from 0 to 1 on both axes:
 * x from the date line westwards to eastwards, y from the north edge (about 85.05 N) to the south edge.
 */
object Mercator {
    const val MAX_LAT = 85.0511287798

    fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0

    fun latToY(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)
    }

    fun xToLon(x: Double): Double = x * 360.0 - 180.0

    fun yToLat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
}

data class GeoBounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    companion object {
        /** The box around all points, or null for an empty track. A track over the date line is not handled. */
        fun of(points: List<TrackPoint>): GeoBounds? {
            if (points.isEmpty()) return null
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (p in points) {
                minLat = min(minLat, p.lat); maxLat = maxOf(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = maxOf(maxLon, p.lon)
            }
            return GeoBounds(minLat, maxLat, minLon, maxLon)
        }
    }
}

/** One map tile to draw: which tile (`z`/`x`/`y` as the server names it) and where it goes on the screen. */
data class TileRef(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double, val size: Double)

/**
 * A view onto the map: the centre (in world coordinates), a zoom that may be fractional, and the size of the screen.
 * Immutable: every gesture makes a new camera. [tileSizePx] is how large a tile is drawn at a whole zoom level;
 * 256 x the screen density keeps map labels readable on a dense screen.
 */
data class MapCamera(
    val worldX: Double,
    val worldY: Double,
    val zoom: Double,
    val widthPx: Int,
    val heightPx: Int,
    val tileSizePx: Double = 256.0,
) {
    /** The whole world, in pixels, at this zoom. */
    val worldSizePx: Double get() = tileSizePx * 2.0.pow(zoom)

    val centerLat: Double get() = Mercator.yToLat(worldY)
    val centerLon: Double get() = Mercator.xToLon(worldX)

    fun screenX(lon: Double): Double = widthPx / 2.0 + (Mercator.lonToX(lon) - worldX) * worldSizePx
    fun screenY(lat: Double): Double = heightPx / 2.0 + (Mercator.latToY(lat) - worldY) * worldSizePx
    fun lonAt(screenX: Double): Double = Mercator.xToLon(worldX + (screenX - widthPx / 2.0) / worldSizePx)
    fun latAt(screenY: Double): Double = Mercator.yToLat(worldY + (screenY - heightPx / 2.0) / worldSizePx)

    fun resized(widthPx: Int, heightPx: Int): MapCamera = copy(widthPx = widthPx, heightPx = heightPx)

    /** Drag the map by (dx, dy) pixels: the point under the finger stays under the finger. */
    fun panBy(dx: Double, dy: Double): MapCamera = copy(
        worldX = (worldX - dx / worldSizePx).coerceIn(0.0, 1.0),
        worldY = (worldY - dy / worldSizePx).coerceIn(0.0, 1.0),
    )

    /** Zoom to [newZoom] (limited to the allowed range) keeping the point at screen position (fx, fy) in place. */
    fun zoomAt(fx: Double, fy: Double, newZoom: Double): MapCamera {
        val target = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val underX = worldX + (fx - widthPx / 2.0) / worldSizePx
        val underY = worldY + (fy - heightPx / 2.0) / worldSizePx
        val newSize = tileSizePx * 2.0.pow(target)
        return copy(
            zoom = target,
            worldX = (underX - (fx - widthPx / 2.0) / newSize).coerceIn(0.0, 1.0),
            worldY = (underY - (fy - heightPx / 2.0) / newSize).coerceIn(0.0, 1.0),
        )
    }

    /**
     * The tiles that cover the screen. The server's zoom level is the whole part of [zoom]; the tiles are scaled up by
     * the rest. Tiles that fall off the top or bottom of the world are left out; across the date line the tile column
     * wraps (`x` is the wrapped column, `left` the true place on the screen).
     */
    fun visibleTiles(): List<TileRef> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val z = floor(zoom).toInt().coerceIn(0, MAX_TILE_ZOOM)
        val count = 1 shl z
        val size = tileSizePx * 2.0.pow(zoom - z)
        val centerTileX = worldX * count
        val centerTileY = worldY * count
        val firstX = floor(centerTileX - widthPx / 2.0 / size).toInt()
        val lastX = floor(centerTileX + widthPx / 2.0 / size).toInt()
        val firstY = maxOf(0, floor(centerTileY - heightPx / 2.0 / size).toInt())
        val lastY = min(count - 1, floor(centerTileY + heightPx / 2.0 / size).toInt())
        val tiles = ArrayList<TileRef>()
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                tiles.add(
                    TileRef(
                        z = z,
                        x = Math.floorMod(tx, count),
                        y = ty,
                        left = widthPx / 2.0 + (tx - centerTileX) * size,
                        top = heightPx / 2.0 + (ty - centerTileY) * size,
                        size = size,
                    )
                )
            }
        }
        return tiles
    }

    companion object {
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 19.0
        /** Zoom to the track, but no closer than this: a short track does not need to be shown at street-corner level. */
        const val FIT_MAX_ZOOM = 17.0
        const val SINGLE_POINT_ZOOM = 16.0
        /** The highest zoom level OpenStreetMap serves. */
        const val MAX_TILE_ZOOM = 19

        /**
         * The camera that shows all of [bounds] inside the screen minus the given paddings (in pixels), as large as
         * possible. A single point (or a track that did not move) is shown at [SINGLE_POINT_ZOOM].
         */
        fun fit(
            bounds: GeoBounds,
            widthPx: Int,
            heightPx: Int,
            tileSizePx: Double = 256.0,
            padLeft: Double = 0.0,
            padTop: Double = 0.0,
            padRight: Double = 0.0,
            padBottom: Double = 0.0,
        ): MapCamera {
            val x0 = Mercator.lonToX(bounds.minLon)
            val x1 = Mercator.lonToX(bounds.maxLon)
            val y0 = Mercator.latToY(bounds.maxLat)      // the north edge has the smaller y
            val y1 = Mercator.latToY(bounds.minLat)
            val availW = maxOf(1.0, widthPx - padLeft - padRight)
            val availH = maxOf(1.0, heightPx - padTop - padBottom)
            val ex = x1 - x0
            val ey = y1 - y0
            val zoom = if (ex < 1e-9 && ey < 1e-9) {
                SINGLE_POINT_ZOOM
            } else {
                val byWidth = if (ex < 1e-9) Double.MAX_VALUE else log2(availW / (ex * tileSizePx))
                val byHeight = if (ey < 1e-9) Double.MAX_VALUE else log2(availH / (ey * tileSizePx))
                min(byWidth, byHeight).coerceIn(MIN_ZOOM, FIT_MAX_ZOOM)
            }
            val worldSize = tileSizePx * 2.0.pow(zoom)
            // The padded area's centre is off the screen centre by half the difference of the paddings
            return MapCamera(
                worldX = (x0 + x1) / 2.0 - (padLeft - padRight) / 2.0 / worldSize,
                worldY = (y0 + y1) / 2.0 - (padTop - padBottom) / 2.0 / worldSize,
                zoom = zoom,
                widthPx = widthPx,
                heightPx = heightPx,
                tileSizePx = tileSizePx,
            )
        }
    }
}

/** Speed of every segment of a track, for colouring it. */
object TrackSegments {
    /**
     * Speed in km/h of each segment (point i to point i + 1), so the result has one entry less than there are points.
     * The speed the GPS reported at the two ends is preferred; otherwise distance over time. A segment that would mean
     * more than about 300 km/h is a GPS glitch and takes the speed of the one before it.
     */
    fun speedsKmh(points: List<TrackPoint>): DoubleArray {
        if (points.size < 2) return DoubleArray(0)
        val out = DoubleArray(points.size - 1)
        var previous = 0.0
        for (i in out.indices) {
            val a = points[i]
            val b = points[i + 1]
            val reported = listOfNotNull(a.speedMps, b.speedMps).filter { it in 0.0..TripStats.MAX_PLAUSIBLE_SPEED_MPS }
            val mps = if (reported.isNotEmpty()) {
                reported.average()
            } else {
                val dt = (b.timeMs - a.timeMs) / 1000.0
                val computed = if (dt > 0) TripStats.haversineMeters(a.lat, a.lon, b.lat, b.lon) / dt else Double.NaN
                if (computed.isNaN() || computed > TripStats.MAX_PLAUSIBLE_SPEED_MPS) previous else computed
            }
            out[i] = mps * 3.6
            previous = mps
        }
        return out
    }
}

/** Colour scale of the track: blue (slow) to green, yellow and red at [SCALE_MAX_KMH] and above. */
object SpeedColors {
    const val SCALE_MAX_KMH = 130.0

    private val STOPS = intArrayOf(0xFF2979FF.toInt(), 0xFF00C853.toInt(), 0xFFFFD600.toInt(), 0xFFD50000.toInt())

    /** ARGB colour for a speed. Anything below 0 or not a number counts as 0. */
    fun color(kmh: Double): Int {
        val t = if (kmh.isNaN()) 0.0 else (kmh / SCALE_MAX_KMH).coerceIn(0.0, 1.0)
        val scaled = t * (STOPS.size - 1)
        val i = min(scaled.toInt(), STOPS.size - 2)
        val f = scaled - i
        return lerp(STOPS[i], STOPS[i + 1], f)
    }

    /** The scale cut into [bands] steps, so that segments can be drawn in a few batches. */
    fun bandOf(kmh: Double, bands: Int): Int {
        val t = if (kmh.isNaN()) 0.0 else (kmh / SCALE_MAX_KMH).coerceIn(0.0, 1.0)
        return min((t * bands).toInt(), bands - 1)
    }

    /** The colour of the middle of band [index]. */
    fun bandColor(index: Int, bands: Int): Int = color((index + 0.5) / bands * SCALE_MAX_KMH)

    private fun lerp(a: Int, b: Int, f: Double): Int {
        fun channel(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
