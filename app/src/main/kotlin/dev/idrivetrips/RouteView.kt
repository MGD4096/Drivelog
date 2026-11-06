package dev.idrivetrips

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.LatLon
import dev.idrivetrips.core.RouteCache
import dev.idrivetrips.core.RouteShape
import dev.idrivetrips.core.Spot
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/** The outline of a trip drawn as a line on a plain background (no map): green dot at the start, red at the end. */
class RouteView(context: Context) : View(context) {
    private var shape: List<LatLon> = emptyList()
    private var spots: List<Spot> = emptyList()
    private var fittedFor = ""
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.soft }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Colors.strong
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun setShape(value: List<LatLon>) {
        shape = value
        fittedFor = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f * density, 12f * density, background)
        if (shape.size < 2) return
        val key = "${shape.size}/${width}x$height"
        if (key != fittedFor) {
            spots = RouteShape.fit(shape, w, h, 10f * density)
            fittedFor = key
        }
        path.reset()
        spots.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        canvas.drawPath(path, line)
        val r = 4.5f * density
        dot.color = Colors.good
        canvas.drawCircle(spots.first().x, spots.first().y, r, dot)
        dot.color = Colors.bad
        canvas.drawCircle(spots.last().x, spots.last().y, r, dot)
    }
}

/** Finds the outline of a trip: from the small cache when it is there, otherwise from the trip file (and remembers it). */
object RouteLoader {
    private var cache: RouteCache? = null

    @Synchronized
    private fun cache(context: Context): RouteCache =
        cache ?: RouteCache(File(context.applicationContext.filesDir, "routes.json")).also { cache = it }

    fun forget(context: Context, id: String) = cache(context).remove(id)

    /** Calls [show] on the main thread; not at all when the trip has no track or the screen is gone. */
    fun load(activity: Activity, store: FileTripStore?, id: String, io: ExecutorService, show: (List<LatLon>) -> Unit) {
        val c = cache(activity)
        try {
            io.execute {
                var shape = c.get(id)
                if (shape == null) {
                    val trip = try { store?.load(id) } catch (e: Exception) { null }
                    if (trip != null) {
                        shape = RouteShape.simplify(trip.points)
                        if (shape.size >= 2) c.put(id, shape)
                    }
                }
                val result = shape
                if (result != null && result.size >= 2) {
                    activity.runOnUiThread { if (!activity.isDestroyed) show(result) }
                }
            }
        } catch (e: RejectedExecutionException) {
            // the screen is closing
        }
    }
}
