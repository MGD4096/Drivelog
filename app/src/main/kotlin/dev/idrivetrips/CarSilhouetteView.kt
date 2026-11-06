package dev.idrivetrips

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.idrivetrips.core.CarPart
import dev.idrivetrips.core.Silhouette

/** A drawing of the car from above (front at the top, driver on the left); whatever is open is painted orange over it. */
class CarSilhouetteView(context: Context) : View(context) {
    private var open: List<CarPart> = emptyList()
    private var known = true
    private val picture = BitmapFactory.decodeResource(resources, R.drawable.car_top)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** For the dark theme: the line drawing turned into light lines on black, laid over the dark panel. */
    private val invert = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0xE6, 0x51, 0x00) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Colors.warn }
    private val box = RectF()

    /** [known] false: the car has not reported its state, so the picture is shown faded. */
    fun setState(openParts: List<CarPart>, known: Boolean) {
        open = openParts
        this.known = known
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * Silhouette.HEIGHT).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val unit = width.toFloat()
        alpha = if (known) 1f else 0.45f
        val area = RectF(0f, 0f, unit, unit * Silhouette.HEIGHT)
        if (Colors.dark) {
            panelPaint.color = Colors.card
            canvas.drawRoundRect(area, unit * 0.06f, unit * 0.06f, panelPaint)
            bitmapPaint.colorFilter = invert
            bitmapPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        } else {
            bitmapPaint.colorFilter = null
            bitmapPaint.xfermode = null
        }
        if (picture != null) canvas.drawBitmap(picture, null, area, bitmapPaint)
        bitmapPaint.xfermode = null
        line.strokeWidth = 1.5f * resources.displayMetrics.density
        for (z in Silhouette.zones(open)) {
            if (!z.open) continue
            box.set(z.x * unit, z.y * unit, (z.x + z.w) * unit, (z.y + z.h) * unit)
            val r = z.radius * unit
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawRoundRect(box, r, r, line)
        }
    }
}
