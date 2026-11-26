package dev.drivelog.trips

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.drivelog.Colors
import dev.drivelog.Prefs
import dev.drivelog.Stores
import dev.drivelog.Ui
import dev.drivelog.car.CarActivity
import dev.drivelog.core.car.CarEvent
import dev.drivelog.core.car.FuelEntry
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.CounterFuel
import dev.drivelog.core.trip.MonthStats
import dev.drivelog.core.trip.MonthlyStats
import dev.drivelog.core.trip.TripMeta
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Month by month for the last twelve months: a bar chart (kilometres, costs or consumption) and this month against the
 * one before. One car ([CarActivity.EXTRA_ADDRESS]) or, without it, all cars together.
 */
class StatsActivity : Activity() {
    private val stores by lazy { Stores(this) }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var box: LinearLayout
    private var address: String? = null
    private var months: List<MonthStats> = emptyList()
    private var metric = Metric.KM

    private enum class Metric { KM, COST, CONSUMPTION }

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        address = intent.getStringExtra(CarActivity.EXTRA_ADDRESS)
        val car = address?.let { a -> Prefs.cars(this).firstOrNull { it.address.equals(a, true) } }
        val name = car?.let { c -> stores.carName(c.address, c.name) }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
            addView(LinearLayout(this@StatsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.button("‹") { finish() }.apply { contentDescription = tr("Back"); minWidth = 0; minimumWidth = 0 },
                    LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ui.title(tr("Statistics")), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(8) })
            })
            addView(ui.body().apply { text = name ?: tr("All cars"); setTextColor(Colors.muted); setPadding(0, 0, 0, ui.dp(8)) })
            addView(box)
        }
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        io.execute {
            val all = stores.trips?.list() ?: emptyList()
            val cars = address?.let { listOf(it) } ?: Prefs.cars(this).map { it.address }
            val trips = all.filter { t -> address == null || t.carId.equals(address, true) }
            val fuelStore = stores.fuel
            val events = stores.events
            val fuel = ArrayList<FuelEntry>()
            val log = ArrayList<CarEvent>()
            for (c in cars) {
                fuel += fuelStore.list(c)
                log += events.list(c)
            }
            val factors = trips.groupBy { it.carId.lowercase(Locale.ROOT) }.mapValues { CounterFuel.factor(it.value) }
            val result = MonthlyStats.of(trips, fuel, log, YearMonth.now(), 12, { t: TripMeta -> factors[t.carId.lowercase(Locale.ROOT)] ?: 1.0 })
            runOnUiThread { if (!isDestroyed) { months = result; show() } }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun value(m: MonthStats): Double? = when (metric) {
        Metric.KM -> m.km
        Metric.COST -> m.cost
        Metric.CONSUMPTION -> m.lPer100Km
    }

    private fun format(v: Double): String = when (metric) {
        Metric.KM -> tr("{0} km", ui.km(v))
        Metric.COST -> ui.money(v)
        Metric.CONSUMPTION -> "${ui.f1(v)} L/100 km"
    }

    private fun show() {
        box.removeAllViews()
        if (months.all { it.trips == 0 && it.cost == 0.0 }) {
            box.addView(ui.body().apply { text = tr("No trips or costs in the last twelve months."); setTextColor(Colors.muted) })
            return
        }
        // what the chart shows
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Metric.KM to tr("Kilometres"), Metric.COST to tr("Costs"), Metric.CONSUMPTION to tr("Consumption")).forEachIndexed { i, (m, label) ->
            choices.addView(ui.pill(label, filled = m == metric, heightDp = 38) { metric = m; show() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
        box.addView(choices)
        val label = DateTimeFormatter.ofPattern("LLL", Locale.getDefault())
        box.addView(BarChart(this, months.map { value(it) }, months.map { label.format(it.month).take(3) }, ::format),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(220)).apply { topMargin = ui.dp(12) })
        if (metric == Metric.CONSUMPTION) box.addView(ui.body().apply {
            text = tr("Only months whose fuel is known for most of the kilometres."); textSize = 12f; setTextColor(Colors.subtle)
        })

        // this month against the one before
        val now = months.last()
        val before = months[months.size - 2]
        val monthName = DateTimeFormatter.ofPattern("LLLL", Locale.getDefault())
        box.addView(ui.heading(tr("{0} against {1}", monthName.format(now.month), monthName.format(before.month))))
        fun row(name: String, a: Double?, b: Double?, text: (Double) -> String, moreIsWorse: Boolean) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9))
                background = GradientDrawable().apply { setColor(Colors.card); cornerRadius = ui.dp(12).toFloat() }
            }
            line.addView(ui.body().apply { this.text = name; textSize = 14f; setTextColor(Colors.muted) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(ui.body().apply { this.text = a?.let(text) ?: "—"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
            val change = if (a != null && b != null) MonthlyStats.change(a, b) else null
            line.addView(TextView(this).apply {
                this.text = change?.let { (if (it >= 0) "+" else "−") + ui.f0(kotlin.math.abs(it) * 100) + " %" } ?: ""
                textSize = 13f
                gravity = Gravity.END
                setTextColor(when {
                    change == null || kotlin.math.abs(change) < 0.005 -> Colors.muted
                    (change > 0) == moreIsWorse -> Colors.warn
                    else -> Colors.good
                })
            }, LinearLayout.LayoutParams(ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
        }
        row(tr("Distance"), now.km, before.km, { tr("{0} km", ui.km(it)) }, false)
        row(tr("Trips"), now.trips.toDouble(), before.trips.toDouble(), { ui.f0(it) }, false)
        row(tr("Driving time"), now.durationMs.toDouble(), before.durationMs.toDouble(), { ui.duration(it.toLong()) }, false)
        row(tr("Fuel used"), now.fuelL, before.fuelL, { "${ui.f1(it)} L" }, true)
        row(tr("Consumption"), now.lPer100Km, before.lPer100Km, { "${ui.f1(it)} L/100 km" }, true)
        row(tr("Fuel costs"), now.fuelCost, before.fuelCost, { ui.money(it) }, true)
        row(tr("Service and other costs"), now.otherCost, before.otherCost, { ui.money(it) }, true)
        row(tr("Cost per km"), now.costPerKm, before.costPerKm, { ui.money(it) }, true)
        box.addView(ui.body().apply {
            text = tr("Costs come from the fuel log and the service log of the car page.")
            textSize = 12f; setTextColor(Colors.subtle); setPadding(0, ui.dp(8), 0, 0)
        })
    }
}

/** Bars for a few values with a label under each; the last bar (the current month) is drawn in the main colour. */
class BarChart(
    context: Context,
    private val values: List<Double?>,
    private val labels: List<String>,
    private val format: (Double) -> String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * density; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init {
        contentDescription = labels.zip(values).joinToString(", ") { (l, v) -> "$l: " + (v?.let(format) ?: "—") }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val top = 22 * density
        val bottom = height - 20 * density
        val slot = width.toFloat() / n
        val w = slot * 0.62f
        for (i in 0 until n) {
            val x = slot * i + (slot - w) / 2
            val v = values[i]
            val last = i == n - 1
            text.color = Colors.muted
            canvas.drawText(labels[i], x + w / 2, height - 5 * density, text)
            if (v == null || v <= 0) continue
            val h = ((bottom - top) * (v / max)).toFloat().coerceAtLeast(2 * density)
            rect.set(x, bottom - h, x + w, bottom)
            bar.color = if (last) Colors.primary else Colors.track
            canvas.drawRoundRect(rect, 4 * density, 4 * density, bar)
            if (last || v == max) {
                text.color = if (last) Colors.strong else Colors.subtle
                canvas.drawText(format(v), (x + w / 2).coerceIn(30 * density, width - 30 * density), bottom - h - 6 * density, text)
            }
        }
    }
}
