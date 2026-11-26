package dev.drivelog.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import dev.drivelog.core.map.DiskTileCache
import dev.drivelog.core.map.TileFetcher
import dev.drivelog.core.map.TileProvider
import dev.drivelog.core.map.TileSource
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What every map in the app shares: one tile cache on disk and two background threads that load tiles. The threads are
 * daemon threads and live as long as the process, so a screen closing never has to wait for them.
 */
object MapResources {
    val provider: TileProvider = TileProvider.OPEN_STREET_MAP

    val executor: ExecutorService by lazy {
        Executors.newFixedThreadPool(2) { r -> Thread(r, "map-tiles").apply { isDaemon = true } }
    }

    private var source: TileSource? = null

    @Synchronized
    fun tiles(context: Context): TileSource =
        source ?: TileSource(
            DiskTileCache(File(context.applicationContext.cacheDir, "tiles"), 64L * 1024 * 1024),
            TileFetcher(provider),
        ).also { source = it }

    /** Dark tiles: hue turned half way round and brightness inverted, so water stays blue and parks stay green. */
    fun darkTiles(): ColorMatrixColorFilter {
        val m = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        m.postConcat(ColorMatrix(floatArrayOf(
            -0.9f, 0f, 0f, 0f, 230f,
            0f, -0.9f, 0f, 0f, 230f,
            0f, 0f, -0.9f, 0f, 230f,
            0f, 0f, 0f, 1f, 0f
        )))
        return ColorMatrixColorFilter(m)
    }
}
