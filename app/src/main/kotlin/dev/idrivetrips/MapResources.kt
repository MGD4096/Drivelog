package dev.idrivetrips

import android.content.Context
import dev.idrivetrips.core.DiskTileCache
import dev.idrivetrips.core.TileFetcher
import dev.idrivetrips.core.TileProvider
import dev.idrivetrips.core.TileSource
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
}
