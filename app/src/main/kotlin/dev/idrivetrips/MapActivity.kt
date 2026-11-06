package dev.idrivetrips

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.idrivetrips.core.FileTripStore
import dev.idrivetrips.core.TrackPoint
import dev.idrivetrips.core.Trip
import dev.idrivetrips.core.TripStats
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import dev.idrivetrips.core.tr

/**
 * A full-screen map. Started either with a trip ([EXTRA_TRIP_ID]: its route, coloured by speed) or with a place
 * ([EXTRA_LAT], [EXTRA_LON], [EXTRA_LABEL]: where a car is parked).
 */
class MapActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var info: TextView
    private lateinit var ui: Ui
    private val loader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)

        mapView = MapView(this, MapResources.provider, MapResources.tiles(this), MapResources.executor)

        info = TextView(this).apply {
            setTextColor(if (Colors.dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22))
            textSize = 14f
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            text = tr("Loading…")
            background = panel()
        }

        val back = mapButton("‹") { finish() }.also { it.contentDescription = tr("Back") }
        val zoomIn = mapButton("+") { mapView.zoomBy(1.0) }.also { it.contentDescription = tr("Zoom in") }
        val zoomOut = mapButton("−") { mapView.zoomBy(-1.0) }.also { it.contentDescription = tr("Zoom out") }
        val fit = mapButton("⤢") { mapView.fit() }.also { it.contentDescription = tr("Show the whole route") }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(zoomIn, buttonParams())
            addView(zoomOut, buttonParams())
            addView(fit, buttonParams())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, buttonParams())
            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(8) })
        }

        val root = FrameLayout(this).apply {
            addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                setMargins(ui.dp(8), ui.dp(8), ui.dp(8), 0)
            })
            addView(buttons, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, ui.dp(8), ui.dp(40))
            })
            // Android 15 draws apps edge to edge: keep the controls clear of the status and navigation bars
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
        setContentView(root)
        // Fit the route into the part of the screen the info card and the buttons leave free
        mapView.setFitPadding(ui.dp(24), ui.dp(96), ui.dp(64), ui.dp(40))

        val id = intent.getStringExtra(EXTRA_TRIP_ID)
        when {
            id != null -> loadTrip(id)
            intent.hasExtra(EXTRA_LAT) && intent.hasExtra(EXTRA_LON) -> showPlace(
                intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0), intent.getStringExtra(EXTRA_LABEL) ?: "",
            )
            else -> finish()
        }
    }

    private fun showPlace(lat: Double, lon: Double, label: String) {
        info.text = "$label\n${String.format(Locale.getDefault(), "%.5f, %.5f", lat, lon)}\nTap here to open it in a maps app."
        info.setOnClickListener {
            val coords = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, tr("No maps app found."), Toast.LENGTH_LONG).show()
            }
        }
        mapView.setTrack(listOf(TrackPoint(timeMs = 0L, lat = lat, lon = lon, altitudeM = null, speedMps = null, accuracyM = null)))
    }

    private fun loadTrip(id: String) {
        loader.execute {
            val trip: Trip? = try { FileTripStore(File(filesDir, "trips")).load(id) } catch (e: IOException) { null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (trip == null || trip.points.isEmpty()) {
                    Toast.makeText(this, tr("This trip has no GPS track."), Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                info.text = describe(trip)
                mapView.setTrack(trip.points)
            }
        }
    }

    private fun describe(trip: Trip): String {
        var meters = 0.0
        val pts = trip.points
        for (i in 1 until pts.size) meters += TripStats.haversineMeters(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        return tr("{0}\n{1} · {2} km on the map · {3} points", carLabel(trip), ui.dateTime(trip.startMs), ui.f1(meters / 1000.0), pts.size)
    }

    /** The car's model when it has one, otherwise the name of its Bluetooth device. */
    private fun carLabel(trip: Trip): String =
        try { dev.idrivetrips.core.FileCarDetailsStore(File(filesDir, "cars.json")).get(trip.carId).model?.takeIf { it.isNotBlank() } } catch (e: Exception) { null } ?: trip.carName

    private fun panel() = GradientDrawable().apply {
        setColor(if (Colors.dark) Color.argb(235, 0x24, 0x27, 0x2C) else Color.argb(235, 255, 255, 255))
        cornerRadius = ui.dp(12).toFloat()
    }

    private fun mapButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setTextColor(if (Colors.dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22))
        background = panel()
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { bottomMargin = ui.dp(8) }

    override fun onDestroy() {
        mapView.release()
        loader.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LABEL = "label"
    }
}
