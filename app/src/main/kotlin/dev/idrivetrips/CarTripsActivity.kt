package dev.idrivetrips

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.TripMeta
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import dev.idrivetrips.core.tr

/** The trips of one car, newest first. */
class CarTripsActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var actions: TripActions
    private lateinit var address: String
    private lateinit var summary: TextView
    private lateinit var box: LinearLayout
    private lateinit var heading: TextView
    private var store: FileTripStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        address = intent.getStringExtra(CarActivity.EXTRA_ADDRESS) ?: run { finish(); return }
        val car = Prefs.cars(this).firstOrNull { it.address.equals(address, true) }
        store = try { FileTripStore(File(filesDir, "trips")) } catch (e: IOException) { null }
        actions = TripActions(this, ui, store, io) { load() }

        heading = ui.title(car?.let { c -> try { dev.idrivetrips.core.FileCarDetailsStore(File(filesDir, "cars.json")).get(c.address).model?.takeIf { it.isNotBlank() } } catch (e: Exception) { null } ?: c.name } ?: tr("Trips"))
        summary = ui.body().apply { setTextColor(Colors.muted); setPadding(0, ui.dp(4), 0, ui.dp(8)) }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
            addView(LinearLayout(this@CarTripsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.button("‹") { finish() }.apply { contentDescription = tr("Back"); minWidth = 0; minimumWidth = 0 },
                    LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(8) })
            })
            addView(summary)
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
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun load() {
        val s = store ?: return
        io.execute {
            val mine = try {
                s.list().filter { it.carId.equals(address, ignoreCase = true) }          // already newest first
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { if (!isDestroyed) show(mine) }
        }
    }

    private fun show(list: List<TripMeta>) {
        box.removeAllViews()
        if (list.isEmpty()) {
            summary.text = tr("No trips recorded for this car yet.")
            summary.visibility = android.view.View.VISIBLE
            return
        }
        summary.text = ""
        summary.visibility = android.view.View.GONE
        box.addView(actions.summaryTiles(list))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.fill(listBox, list, showCar = false)
        box.addView(listBox)
    }
}
