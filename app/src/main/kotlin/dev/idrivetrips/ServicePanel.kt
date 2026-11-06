package dev.idrivetrips

import dev.idrivetrips.core.trn
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.idrivetrips.core.Due
import dev.idrivetrips.core.DueLevel
import dev.idrivetrips.core.EventType
import dev.idrivetrips.core.Reminders
import kotlin.math.min
import dev.idrivetrips.core.tr

/**
 * A service gauge like the one in the instrument cluster: an open ring of five segments that empties as the date or the
 * kilometres run out, with the icon of the service in the middle.
 */
class ServiceGaugeView(context: Context) : View(context) {
    var fraction: Double? = null
        set(value) { field = value; invalidate() }
    var level: DueLevel? = null
        set(value) { field = value; invalidate() }
    var icon: Int = 0
        set(value) { field = value; invalidate() }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val box = RectF()

    private fun colour(): Int = when (level) {
        DueLevel.OVERDUE -> Colors.bad
        DueLevel.SOON -> Colors.warn
        else -> Colors.primary
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.11f
        arc.strokeWidth = stroke
        val left = (width - size) / 2f + stroke / 2f
        val top = (height - size) / 2f + stroke / 2f
        box.set(left, top, left + size - stroke, top + size - stroke)
        val gap = 5f
        val segment = (270f - 4 * gap) / 5f
        val filled = (fraction ?: 0.0).coerceIn(0.0, 1.0) * 5.0
        for (i in 0 until 5) {
            val start = 135f + i * (segment + gap)
            arc.color = Colors.track
            canvas.drawArc(box, start, segment, false, arc)
            val part = (filled - i).coerceIn(0.0, 1.0).toFloat()
            if (part > 0f) {
                arc.color = colour()
                canvas.drawArc(box, start, segment * part, false, arc)
            }
        }
        if (icon != 0) {
            val d = context.getDrawable(icon)?.mutate() ?: return
            d.setTint(if (level == DueLevel.OVERDUE || level == DueLevel.SOON) colour() else Colors.strong)
            val half = (size * 0.23f).toInt()
            val cx = width / 2
            val cy = height / 2
            d.setBounds(cx - half, cy - half, cx + half, cy + half)
            d.draw(canvas)
        }
    }
}

/**
 * The service block of a car on the list: a light panel with one big gauge (the most urgent service to begin with) and its
 * figures beside it, and a row of small gauges below to switch to oil, inspection or insurance.
 */
class ServicePanel(private val activity: Activity, private val ui: Ui) {
    private val labelColour = Colors.muted
    private val ink = Colors.text
    private val kinds = listOf(
        Triple(EventType.OIL_CHANGE, R.drawable.ic_service_oil, tr("Oil change")),
        Triple(EventType.INSPECTION, R.drawable.ic_service_inspection, tr("Technical inspection")),
        Triple(EventType.INSURANCE, R.drawable.ic_service_insurance, tr("Insurance")),
    )

    /** [compact]: a smaller panel for the car list (smaller gauges, tighter spacing and text). */
    fun build(dues: List<Due>, odometerKm: Double?, compact: Boolean = false): View {
        val big = if (compact) 92 else 132            // dp, the main gauge
        val small = 46                                // dp, the gauges in the row below (the same on the list and in the details)
        val gap = if (compact) 5 else 10              // dp between the lines of figures
        val gauge = ServiceGaugeView(activity)
        fun text(sp: Float, bold: Boolean, colour: Int) = TextView(activity).apply {
            textSize = sp
            setTextColor(colour)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val label1 = text(if (compact) 10f else 12f, false, labelColour)
        val value1 = text(if (compact) 11f else 18f, true, ink)
        val label2 = text(if (compact) 10f else 12f, false, labelColour)
        val value2 = text(if (compact) 11f else 16f, true, ink)
        val label3 = text(if (compact) 10f else 12f, false, labelColour).apply { setText(tr("Odometer")) }
        val value3 = text(if (compact) 11f else 16f, true, ink).apply { text = odometerKm?.let { "${ui.km(it)} km" } ?: "—" }
        val figures = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label1); addView(value1)
            addView(label2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(gap) }); addView(value2)
            addView(label3, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(gap) }); addView(value3)
        }
        val main = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(gauge, LinearLayout.LayoutParams(ui.dp(big), ui.dp(big)))
            addView(figures, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(if (compact) 10 else 14) })
        }

        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val chipBackgrounds = HashMap<EventType, View>()

        fun show(type: EventType) {
            val (_, icon, name) = kinds.first { it.first == type }
            val due = dues.firstOrNull { it.type == type }
            gauge.icon = icon
            gauge.level = due?.level
            gauge.fraction = due?.let { Reminders.remainingFraction(it) }
            gauge.contentDescription = "$name: " + CarTexts.tileLines(ui, due).let { "${it.first} ${it.second}".trim() }
            label1.text = when (type) {
                EventType.OIL_CHANGE -> tr("Next oil change")
                EventType.INSPECTION -> tr("Next inspection")
                else -> tr("Insurance valid until")
            }
            if (due == null) {
                value1.text = tr("Not set")
                label2.text = tr("Add it in the car details")
                value2.text = " "
            } else {
                value1.text = due.dueDate?.let { ui.date(it) } ?: due.nextAtKm?.takeIf { type == EventType.OIL_CHANGE }?.let { "${ui.km(it)} km" } ?: "—"
                val km = if (type == EventType.OIL_CHANGE) due.kmLeft else null          // inspection and insurance run by date only
                val days = due.daysLeft
                when {
                    km != null -> { label2.text = if (km < 0) tr("Over by") else tr("Remaining distance"); value2.text = tr("{0} km", ui.km(Math.abs(km))) }
                    days != null -> { label2.text = if (days < 0) tr("Expired") else tr("Days left"); value2.text = if (days < 0) tr("{0} ago", trn(-days, "{0} day", "{0} days")) else "$days" }
                    else -> { label2.text = " "; value2.text = " " }
                }
            }
            val oil = type == EventType.OIL_CHANGE
            label3.visibility = if (oil) View.VISIBLE else View.GONE
            value3.visibility = if (oil) View.VISIBLE else View.GONE
            for ((t, v) in chipBackgrounds) {
                (v.background as GradientDrawable).setColor(if (t == type) Colors.soft else Color.TRANSPARENT)
            }
        }

        kinds.forEachIndexed { i, (type, icon, name) ->
            val due = dues.firstOrNull { it.type == type }
            val mini = ServiceGaugeView(activity).apply { this.icon = icon; level = due?.level; fraction = due?.let { Reminders.remainingFraction(it) } }
            val caption = TextView(activity).apply {
                text = CarTexts.tileLines(ui, due).first
                textSize = 11f
                setTextColor(ink)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor(Color.TRANSPARENT) }
                contentDescription = name
                isClickable = true
                setOnClickListener { show(type) }
                addView(mini, LinearLayout.LayoutParams(ui.dp(small), ui.dp(small)))
                addView(caption, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            chipBackgrounds[type] = chip
            chips.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }

        show(dues.firstOrNull()?.type ?: EventType.OIL_CHANGE)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(if (compact) 10 else 14), ui.dp(if (compact) 8 else 12), ui.dp(if (compact) 10 else 14), ui.dp(if (compact) 4 else 8))
            background = GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(14).toFloat() }
            addView(main)
            addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
        }
    }
}
