package dev.idrivetrips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.idrivetrips.core.GeoBounds
import dev.idrivetrips.core.MapCamera
import dev.idrivetrips.core.SpeedColors
import dev.idrivetrips.core.TileProvider
import dev.idrivetrips.core.TileSource
import dev.idrivetrips.core.TrackPoint
import dev.idrivetrips.core.TrackSegments
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ln
import kotlin.math.max

/**
 * A small map drawn by hand: OpenStreetMap tiles underneath, the trip's route on top, coloured by speed.
 * All geometry is in `core` (MapCamera); this class only draws it and passes touches on. One finger drags, two fingers
 * zoom, a double tap zooms in. Tiles are loaded on [executor] (owned by the caller) through [tiles].
 */
class MapView(
    context: Context,
    private val provider: TileProvider,
    private val tiles: TileSource,
    private val executor: ExecutorService,
    bitmapCacheSize: Int = 48,
) : View(context) {

    /** False for a small preview: it only shows the map and reports taps through setOnClickListener. */
    var interactive = true

    private val density = resources.displayMetrics.density
    private val tileSizePx = 256.0 * density

    private var camera: MapCamera? = null
    private var points: List<TrackPoint> = emptyList()
    private var speeds = DoubleArray(0)
    private var bounds: GeoBounds? = null
    private var pendingFit = true

    private var padLeft = 0.0
    private var padTop = 0.0
    private var padRight = 0.0
    private var padBottom = 0.0

    // Tile bitmaps: about a quarter of the heap budget is plenty for a couple of screens of tiles
    private val bitmaps = object : LruCache<Long, Bitmap>(bitmapCacheSize) {}
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    @Volatile private var wanted: Set<Long> = emptySet()
    @Volatile private var released = false

    private val dark = Colors.dark
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (dark) colorFilter = darkTiles() }
    private val groundColor = if (dark) Color.rgb(0x1B, 0x1D, 0x21) else Color.rgb(0xE8, 0xE4, 0xDC)
    private val emptyTilePaint = Paint().apply { color = groundColor }
    private val casingPaint = line(if (dark) Color.rgb(0x10, 0x11, 0x14) else Color.WHITE, 8f)
    private val bandPaints = Array(BANDS) { line(SpeedColors.bandColor(it, BANDS), 5f) }
    private val bandPaths = Array(BANDS) { Path() }
    private val casingPath = Path()
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.rgb(0x10, 0x11, 0x14) else Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; color = if (dark) Color.rgb(0xDD, 0xDD, 0xDD) else Color.rgb(0x33, 0x33, 0x33) }
    private val textBackground = Paint().apply { color = if (dark) Color.argb(210, 0x1B, 0x1D, 0x21) else Color.argb(200, 255, 255, 255) }
    private val legendPaint = Paint()
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; color = if (dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22) }

    /** Dark tiles: hue turned half way round and brightness inverted, so water stays blue and parks stay green. */
    private fun darkTiles(): ColorMatrixColorFilter {
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

    private fun line(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val c = camera ?: return false
            val factor = detector.scaleFactor.toDouble()
            if (factor <= 0.0) return false
            camera = c.zoomAt(detector.focusX.toDouble(), detector.focusY.toDouble(), c.zoom + ln(factor) / ln(2.0))
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val c = camera ?: return false
            if (scaleDetector.isInProgress) return false
            camera = c.panBy(-distanceX.toDouble(), -distanceY.toDouble())
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val c = camera ?: return false
            camera = c.zoomAt(e.x.toDouble(), e.y.toDouble(), c.zoom + 1.0)
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(groundColor)
    }

    // ---- what the owner can do ----------------------------------------------------------------------------------------

    fun setTrack(track: List<TrackPoint>) {
        points = track
        speeds = TrackSegments.speedsKmh(track)
        bounds = GeoBounds.of(track)
        pendingFit = true
        fitIfPossible()
        invalidate()
    }

    /** Room to keep clear of the route when fitting it (cards and buttons laid over the map), in pixels. */
    fun setFitPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padLeft = left.toDouble(); padTop = top.toDouble(); padRight = right.toDouble(); padBottom = bottom.toDouble()
    }

    fun fit() {
        pendingFit = true
        fitIfPossible()
        invalidate()
    }

    fun zoomBy(steps: Double) {
        val c = camera ?: return
        camera = c.zoomAt(width / 2.0, height / 2.0, c.zoom + steps)
        invalidate()
    }

    /** Stop loading tiles and drop the pictures. Call from onDestroy. */
    fun release() {
        released = true
        wanted = emptySet()
        bitmaps.evictAll()
    }

    // ---- camera -------------------------------------------------------------------------------------------------------

    private fun fitIfPossible() {
        val b = bounds
        if (width <= 0 || height <= 0) return
        if (b == null) {
            camera = camera?.resized(width, height) ?: MapCamera(0.5, 0.5, MapCamera.MIN_ZOOM, width, height, tileSizePx)
        } else if (pendingFit) {
            camera = MapCamera.fit(b, width, height, tileSizePx, padLeft, padTop, padRight, padBottom)
            pendingFit = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val c = camera
        if (c == null || pendingFit) fitIfPossible() else camera = c.resized(w, h)
    }

    // ---- touch --------------------------------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    // ---- drawing ------------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val c = camera ?: return
        drawTiles(canvas, c)
        drawRoute(canvas, c)
        drawLegend(canvas)
        drawAttribution(canvas)
    }

    private fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    private fun drawTiles(canvas: Canvas, c: MapCamera) {
        val visible = c.visibleTiles()
        val now = System.currentTimeMillis()
        val keys = HashSet<Long>()
        val dest = RectF()
        for (t in visible) {
            val k = key(t.z, t.x, t.y)
            keys.add(k)
            dest.set(t.left.toFloat(), t.top.toFloat(), (t.left + t.size).toFloat(), (t.top + t.size).toFloat())
            val bmp = bitmaps.get(k)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dest, tilePaint)
            } else {
                canvas.drawRect(dest, emptyTilePaint)
                val failed = failedAt[k]
                if ((failed == null || now - failed > RETRY_AFTER_MS) && inFlight.add(k)) request(t.z, t.x, t.y, k)
            }
        }
        wanted = keys
    }

    private fun request(z: Int, x: Int, y: Int, k: Long) {
        try {
            executor.execute {
                try {
                    if (released || k !in wanted) return@execute         // scrolled away while waiting in the queue
                    val bytes = tiles.load(z, x, y)
                    val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    if (bmp != null) {
                        bitmaps.put(k, bmp)
                        failedAt.remove(k)
                    } else {
                        failedAt[k] = System.currentTimeMillis()
                    }
                } catch (e: RuntimeException) {
                    failedAt[k] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(k)
                }
                if (!released) postInvalidate()
            }
        } catch (e: RejectedExecutionException) {
            inFlight.remove(k)        // the activity is closing
        }
    }

    private fun drawRoute(canvas: Canvas, c: MapCamera) {
        val n = points.size
        if (n == 0) return
        // Project once. A point closer than 2 px to the last drawn one adds nothing visible, so it is skipped.
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val keep = BooleanArray(n)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (i in 0 until n) {
            xs[i] = c.screenX(points[i].lon).toFloat()
            ys[i] = c.screenY(points[i].lat).toFloat()
            val far = lastX.isNaN() || i == n - 1 || max(kotlin.math.abs(xs[i] - lastX), kotlin.math.abs(ys[i] - lastY)) >= 2f
            if (far) { keep[i] = true; lastX = xs[i]; lastY = ys[i] }
        }
        casingPath.rewind()
        for (p in bandPaths) p.rewind()
        var prev = -1
        for (i in 0 until n) {
            if (!keep[i]) continue
            if (prev >= 0) {
                val speed = speeds.getOrElse(i - 1) { 0.0 }       // the segment arriving at point i
                val path = bandPaths[SpeedColors.bandOf(speed, BANDS)]
                path.moveTo(xs[prev], ys[prev]); path.lineTo(xs[i], ys[i])
                casingPath.moveTo(xs[prev], ys[prev]); casingPath.lineTo(xs[i], ys[i])
            }
            prev = i
        }
        canvas.drawPath(casingPath, casingPaint)
        for (b in 0 until BANDS) canvas.drawPath(bandPaths[b], bandPaints[b])

        // A single point is a place (such as where the car is parked), not a route: no start/end pair
        marker(canvas, xs[0], ys[0], if (n == 1) Color.rgb(0x15, 0x65, 0xC0) else Color.rgb(0x00, 0x96, 0x4F))
        if (n > 1) marker(canvas, xs[n - 1], ys[n - 1], Color.rgb(0x21, 0x21, 0x21))
    }

    private fun marker(canvas: Canvas, x: Float, y: Float, color: Int) {
        markerFill.color = color
        canvas.drawCircle(x, y, 9f * density, markerRing)
        canvas.drawCircle(x, y, 6.5f * density, markerFill)
    }

    private fun drawLegend(canvas: Canvas) {
        if (points.size < 2) return
        val w = 110f * density
        val h = 8f * density
        val left = 12f * density
        val bottom = height - padBottomForLegend()
        val top = bottom - h
        legendPaint.shader = LinearGradient(
            left, 0f, left + w, 0f,
            IntArray(5) { SpeedColors.color(it / 4.0 * SpeedColors.SCALE_MAX_KMH) }, null, Shader.TileMode.CLAMP,
        )
        val pad = 4f * density
        canvas.drawRoundRect(left - pad, top - 16f * density, left + w + pad * 3, bottom + pad, 6f * density, 6f * density, textBackground)
        canvas.drawRect(left, top, left + w, bottom, legendPaint)
        canvas.drawText("0", left, top - 4f * density, legendTextPaint)
        val end = "${SpeedColors.SCALE_MAX_KMH.toInt()}+ km/h"
        canvas.drawText(end, left + w - legendTextPaint.measureText(end) + 2f * density, top - 4f * density, legendTextPaint)
    }

    /** The legend sits above the bottom fit-padding so cards laid over the map do not hide it. */
    private fun padBottomForLegend(): Float = (padBottom.toFloat() / 2f).coerceAtLeast(24f * density)

    private fun drawAttribution(canvas: Canvas) {
        val text = provider.attribution
        val w = textPaint.measureText(text)
        val pad = 4f * density
        val right = width.toFloat()
        val bottom = height.toFloat()
        canvas.drawRect(right - w - pad * 2, bottom - textPaint.textSize - pad * 2, right, bottom, textBackground)
        canvas.drawText(text, right - w - pad, bottom - pad - textPaint.descent(), textPaint)
    }

    companion object {
        private const val BANDS = 24
        private const val RETRY_AFTER_MS = 15_000L
    }
}
