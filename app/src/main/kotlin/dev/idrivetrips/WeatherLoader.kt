package dev.idrivetrips

import android.content.Context
import dev.idrivetrips.core.WeatherClient
import dev.idrivetrips.core.WeatherSource
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One background thread and one cache file for the weather tiles. */
object WeatherLoader {
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "weather").apply { isDaemon = true } }
    }

    private var source: WeatherSource? = null

    @Synchronized
    fun source(context: Context): WeatherSource =
        source ?: WeatherSource(File(context.applicationContext.cacheDir, "weather.json"), WeatherClient()).also { source = it }
}
