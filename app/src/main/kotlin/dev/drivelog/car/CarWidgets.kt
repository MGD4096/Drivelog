package dev.drivelog.car

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.drivelog.AppState
import dev.drivelog.CarRef
import dev.drivelog.Colors
import dev.drivelog.Phase
import dev.drivelog.R
import dev.drivelog.Ui
import dev.drivelog.core.car.CarDetails
import dev.drivelog.core.car.CarSnapshot
import dev.drivelog.core.car.Due
import dev.drivelog.core.car.DueLevel
import dev.drivelog.core.car.EventType
import dev.drivelog.core.car.LastKnownCar
import dev.drivelog.core.car.OpenPartsText
import dev.drivelog.core.car.VehicleSummary
import dev.drivelog.core.text.tr
import dev.drivelog.core.trip.TrackPoint
import dev.drivelog.map.MapActivity
import dev.drivelog.map.MapResources
import dev.drivelog.map.MapView
import java.util.Locale
import java.util.concurrent.ExecutorService

/** Pieces shared by the car list and the car details screen: the photo, the small parking map, the odometer text. */
class CarWidgets(
    private val activity: Activity,
    private val ui: Ui,
    private val io: ExecutorService,
) {
    private val maps = ArrayList<MapView>()

    fun releaseMaps() {
        for (m in maps) m.release()
        maps.clear()
    }

    fun roundedImage(radiusPx: Int) = ImageView(activity).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx.toFloat())
            }
        }
    }

    /** The car's photo, or a grey box saying there is none. */
    fun photo(name: String?, heightDp: Int): View {
        if (name == null) {
            return FrameLayout(activity).apply {
                setBackgroundColor(Colors.line)
                addView(TextView(activity).apply {
                    text = tr("No photo yet")
                    setTextColor(Colors.muted)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(90))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, ui.dp(12).toFloat())
                    }
                }
            }
        }
        val image = roundedImage(ui.dp(12)).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Colors.line)
            contentDescription = tr("Photo of the car")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(heightDp))
        }
        loadInto(image, name, 1100)
        return image
    }

    fun loadInto(image: ImageView, name: String?, maxSide: Int) {
        image.tag = name
        image.setImageDrawable(null)
        if (name == null) return
        io.execute {
            val bmp = PhotoStore.load(activity, name, maxSide)
            activity.runOnUiThread { if (!activity.isDestroyed && image.tag == name) image.setImageBitmap(bmp) }
        }
    }

    /** A small, fixed map with a pin. Tapping it calls [onClick]. */
    fun miniMap(lat: Double, lon: Double, onClick: () -> Unit): View {
        val map = MapView(activity, MapResources.provider, MapResources.tiles(activity), MapResources.executor, bitmapCacheSize = 12)
        map.interactive = false
        map.setTrack(listOf(TrackPoint(timeMs = 0L, lat = lat, lon = lon, altitudeM = null, speedMps = null, accuracyM = null)))
        map.contentDescription = tr("Map of where the car is parked")
        map.setOnClickListener { onClick() }
        map.clipToOutline = true
        map.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, ui.dp(12).toFloat())
            }
        }
        maps.add(map)
        return map
    }

    fun openBigMap(lat: Double, lon: Double, label: String) {
        activity.startActivity(
            Intent(activity, MapActivity::class.java)
                .putExtra(MapActivity.EXTRA_LAT, lat)
                .putExtra(MapActivity.EXTRA_LON, lon)
                .putExtra(MapActivity.EXTRA_LABEL, label)
        )
    }

    fun openInMapsApp(lat: Double, lon: Double, label: String) {
        val coords = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, tr("No maps app found."), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Three tiles side by side (oil, inspection, insurance): an icon, the days left under it and the kilometres left
     * below that, all in the colour of how close the date is. A kind without an entry shows "not set" in grey.
     */
    fun serviceTiles(dues: List<Due>): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val kinds = listOf(
            Triple(EventType.OIL_CHANGE, R.drawable.ic_service_oil, tr("Oil change")),
            Triple(EventType.INSPECTION, R.drawable.ic_service_inspection, tr("Technical inspection")),
            Triple(EventType.INSURANCE, R.drawable.ic_service_insurance, tr("Insurance")),
        )
        kinds.forEachIndexed { i, (type, icon, name) ->
            val due = dues.firstOrNull { it.type == type }
            val mileage = if(type == EventType.OIL_CHANGE) due?.nextAtKm else null;
            row.addView(tile(icon, name, due, mileage), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = ui.dp(8)
            })
        }
        return row
    }

    private fun tile(icon: Int, name: String, due: Due?, mileage: Double?): View {
        val (strong, soft) = when (due?.level) {
            DueLevel.OVERDUE -> Colors.bad to Colors.badBg
            DueLevel.SOON -> Colors.warn to Colors.warnBg
            DueLevel.OK -> Colors.good to Colors.goodBg
            null -> Colors.muted to Colors.neutralBg
        }
        val (line1, line2, ) = CarTexts.tileLines(ui, due)
        fun text(value: String, sp: Float, bold: Boolean) = TextView(activity).apply {
            text = value
            setTextColor(strong)
            gravity = Gravity.CENTER
            maxLines = 1
            if (bold) setTypeface(typeface, Typeface.BOLD)
            // Long dates and kilometre figures shrink to fit instead of wrapping
            setAutoSizeTextTypeUniformWithConfiguration(9, sp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(10))
            background = GradientDrawable().apply { setColor(soft); cornerRadius = ui.dp(14).toFloat() }
            contentDescription = "$name: $line1 $line2".trim()
            addView(ImageView(activity).apply {
                setImageResource(icon)
                setColorFilter(strong)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            addView(text(line1, 15f, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) })
            addView(text(line2.ifEmpty { " " }, 13f, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if(mileage!=null){
                addView(text("${mileage.toInt()} km", 13f, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    /** The state of doors, windows, roof, tailgate and bonnet: [closed] is null when the car never reported it. */
    class DoorsStatus(val text: String, val closed: Boolean?, val note: String?, val parts: List<String>? = null)

    /** Live while a trip runs in this car, otherwise as the car reported it last (saved reading, or the end of a trip). */
    fun doorsStatus(car: CarRef, known: LastKnownCar?): DoorsStatus {
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) {
            AppState.car.snapshot().openParts?.let { return DoorsStatus(OpenPartsText.describe(it), it.isEmpty(), tr("live"), it) }
        }
        val parts = known?.openParts
        if (parts != null) {
            val at = known.openPartsAtMs ?: known.atMs
            val note = ui.date(at)                         // just the date: from a reading or from the end of a trip
            return DoorsStatus(OpenPartsText.describe(parts), parts.isEmpty(), note, parts)
        }
        return DoorsStatus("—", null, null)
    }

    fun currentOdometerKm(car: CarRef, s: VehicleSummary, d: CarDetails): Double? = CarTexts.currentOdometerKm(car, s, d)

    /** "32 L · range 410 km (live)", or from the end of the latest trip that had a reading. */
    fun fuelText(car: CarRef, known: LastKnownCar?, withWhen: Boolean = true): String {
        fun format(c: CarSnapshot): String? {
            val parts = ArrayList<String>()
            c.fuelLiters?.let { parts += "${ui.f0(it)} L" }
            c.rangeKm?.let { parts += tr("range {0} km", ui.f0(it)) }
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) {
            format(AppState.car.snapshot())?.let { return tr("{0} (live)", it) }
        }
        if (known != null) format(known.snapshot)?.let {
            if (!withWhen) return it                              // the car list: without when it was read
            return tr("{0} ({1})", it, ui.date(known.atMs))
        }
        return "—"
    }

    /**
     * The odometer and where it comes from: live, the latest reading, the end of the latest trip, or typed in.
     * [withWhen] false (the car list): a reading without when it was made, one typed in with just the date.
     */
    fun odometerText(car: CarRef, s: VehicleSummary, d: CarDetails, withWhen: Boolean = true): String {
        if (!d.usesPlugin) {
            val km = s.manualOdometerNow(d) ?: return "—"
            if (!withWhen) return d.manualOdometerAtMs?.let { tr("{0} km ({1})", ui.km(km), ui.date(it)) } ?: tr("{0} km", ui.km(km))
            return tr("{0} km", ui.km(km)) + (d.manualOdometerAtMs?.let { tr(" (entered on {0}, plus trips)", ui.date(it)) } ?: "")
        }
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) {
            AppState.car.snapshot().odometerKm?.let { return tr("{0} km (live)", ui.km(it)) }
        }
        val km = s.odometerKm ?: return "—"
        if (!withWhen) return tr("{0} km", ui.km(km))
        return tr("{0} km ({1})", ui.km(km), ui.date(s.odometerAtMs ?: 0L))   // just the date, from a reading or a trip
    }
}
