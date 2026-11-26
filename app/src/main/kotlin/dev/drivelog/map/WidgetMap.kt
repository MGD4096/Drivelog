package dev.drivelog.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import dev.drivelog.core.map.GeoBounds
import dev.drivelog.core.map.LatLon
import dev.drivelog.core.map.MapCamera
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small maps as pictures for the home-screen widgets: OpenStreetMap tiles (from the app's tile cache, fetched when
 * missing) with a route or a pin on them, rounded corners, darkened in the dark theme like the app's maps. Loading
 * tiles may use the network: call [route] and [pin] only on [executor], never on the main thread.
 */
object WidgetMap {
    /** One thread for all widget pictures, so they never load the same tiles twice at a time. */
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "widget-maps").apply { isDaemon = true } }
    }

    private const val ROUTE_COLOUR = 0xFF1E88E5.toInt()
    private const val START_COLOUR = 0xFF43A047.toInt()
    private const val END_COLOUR = 0xFFE53935.toInt()

    /** The route [shape] fitted into [width] × [height] pixels, with its start (green) and end (red). */
    fun route(context: Context, shape: List<LatLon>, width: Int, height: Int, dark: Boolean): Bitmap {
        val bounds = GeoBounds(shape.minOf { it.lat }, shape.maxOf { it.lat }, shape.minOf { it.lon }, shape.maxOf { it.lon })
        val pad = height * 0.14
        val camera = MapCamera.fit(bounds, width, height, padLeft = pad, padTop = pad, padRight = pad, padBottom = pad)
        return draw(context, camera, dark) { canvas ->
            val path = Path()
            shape.forEachIndexed { i, p ->
                val x = camera.screenX(p.lon).toFloat()
                val y = camera.screenY(p.lat).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, stroke(if (dark) Color.rgb(0x10, 0x11, 0x14) else Color.WHITE, height * 0.045f))
            canvas.drawPath(path, stroke(ROUTE_COLOUR, height * 0.025f))
            dot(canvas, camera, shape.first(), START_COLOUR, height, dark)
            dot(canvas, camera, shape.last(), END_COLOUR, height, dark)
        }
    }

    /** A pin at [lat], [lon] in the middle of [width] × [height] pixels, at street level. */
    fun pin(context: Context, lat: Double, lon: Double, width: Int, height: Int, dark: Boolean): Bitmap {
        val camera = MapCamera.fit(GeoBounds(lat, lat, lon, lon), width, height)
        return draw(context, camera, dark) { canvas -> dot(canvas, camera, LatLon(lat, lon), END_COLOUR, (height * 1.4f).toInt(), dark) }
    }

    private fun draw(context: Context, camera: MapCamera, dark: Boolean, over: (Canvas) -> Unit): Bitmap {
        val w = camera.widthPx
        val h = camera.heightPx
        val tiles = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tiles)
        canvas.drawColor(if (dark) Color.rgb(0x1B, 0x1D, 0x21) else Color.rgb(0xE8, 0xE4, 0xDC))
        val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (dark) colorFilter = MapResources.darkTiles() }
        val source = MapResources.tiles(context)
        for (t in camera.visibleTiles()) {
            val bytes = source.load(t.z, t.x, t.y) ?: continue
            val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            canvas.drawBitmap(tile, null, RectF(t.left.toFloat(), t.top.toFloat(), (t.left + t.size).toFloat(), (t.top + t.size).toFloat()), tilePaint)
            tile.recycle()
        }
        over(canvas)
        // rounded corners, like the widget's tiles
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val radius = h * 0.08f
        Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(tiles, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        })
        tiles.recycle()
        return out
    }

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun dot(canvas: Canvas, camera: MapCamera, p: LatLon, colour: Int, height: Int, dark: Boolean) {
        val x = camera.screenX(p.lon).toFloat()
        val y = camera.screenY(p.lat).toFloat()
        val r = height * 0.045f
        canvas.drawCircle(x, y, r * 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0x10, 0x11, 0x14) else Color.WHITE })
        canvas.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
    }
}
