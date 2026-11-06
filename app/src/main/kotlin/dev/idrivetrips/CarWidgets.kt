package dev.idrivetrips

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.idrivetrips.core.CarDetails
import dev.idrivetrips.core.CarSnapshot
import dev.idrivetrips.core.Due
import dev.idrivetrips.core.DueLevel
import dev.idrivetrips.core.EventType
import dev.idrivetrips.core.LastKnownCar
import dev.idrivetrips.core.OpenPartsText
import dev.idrivetrips.core.TrackPoint
import dev.idrivetrips.core.WeatherKind
import dev.idrivetrips.core.VehicleSummary
import java.util.Locale
import java.util.concurrent.ExecutorService
import dev.idrivetrips.core.tr

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

    /**
     * The weather at a place (here, or where the car is parked): a picture, the temperature and the wording, then how it feels and the wind,
     * then where it comes from. Fills in a moment later, once the network has answered.
     */
    fun weatherTile(lat: Double, lon: Double): View = weatherTile(tr("at the parking spot")) { Pair(lat, lon) }

    /** The weather where the phone is now. Asks for the location permission when a tap is needed, and says so on the tile. */
    fun weatherTileHere(): View = weatherTile(tr("at your location")) {
        PhoneLocation.find(activity)?.let { Pair(it.lat, it.lon) }
    }

    /** [where] is shown on the tile; [locate] runs on a background thread and gives the place, or null when unknown. */
    private fun weatherTile(where: String, locate: () -> Pair<Double, Double>?): View {
        val strong = Colors.strong
        val icon = ImageView(activity).apply {
            setColorFilter(strong)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun label(sp: Float, bold: Boolean, color: Int) = TextView(activity).apply {
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val main = label(17f, true, strong).apply { text = tr("Weather: loading…") }
        val feels = label(13f, false, strong)
        val source = label(11f, false, Colors.link)
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(main)
            addView(feels)
            addView(source)
        }
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = GradientDrawable().apply { setColor(Colors.soft); cornerRadius = ui.dp(14).toFloat() }
            addView(icon, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(12) })
        }
        icon.setImageResource(R.drawable.ic_weather_cloud)
        icon.alpha = 0.35f

        WeatherLoader.executor.execute {
            val place = try { locate() } catch (e: Exception) { null }
            val result = if (place == null) null else try { WeatherLoader.source(activity).get(place.first, place.second) } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                if (place == null) {
                    main.text = tr("Weather here: no location")
                    if (PhoneLocation.permitted(activity)) {
                        feels.text = tr("Turn on location in the phone's quick settings.")
                    } else {
                        feels.text = tr("Tap to allow location.")
                        tile.setOnClickListener {
                            activity.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION), MainActivity.REQ_BASIC)
                        }
                    }
                    source.text = tr("Weather data by Open-Meteo.com")
                    return@runOnUiThread
                }
                if (result == null) {
                    main.text = tr("Weather unavailable")
                    feels.text = tr("No connection, or the weather service did not answer.")
                    source.text = tr("Weather data by Open-Meteo.com")
                    return@runOnUiThread
                }
                val w = result.weather
                icon.setImageResource(weatherIcon(w.kind))
                icon.alpha = 1f
                main.text = "${Math.round(w.tempC).toInt()} °C · ${w.description}"
                feels.text = buildString {
                    w.feelsLikeC?.let { append(tr("Feels like {0} °C", Math.round(it).toInt())) }
                    w.windKmh?.let {
                        if (isNotEmpty()) append(" · ")
                        append(tr("wind {0} km/h", Math.round(it).toInt()))
                    }
                }.ifEmpty { " " }
                source.text = (if (result.stale) tr("Offline, as of {0}", ui.time(w.fetchedAtMs)) else tr("Updated {0}", ui.time(w.fetchedAtMs))) +
                    " · $where · " + tr("Weather data by Open-Meteo.com")
                tile.contentDescription = tr("Weather {0}: {1}. {2}", where, main.text, feels.text)
            }
        }
        return tile
    }

    private fun weatherIcon(kind: WeatherKind): Int = when (kind) {
        WeatherKind.CLEAR_DAY -> R.drawable.ic_weather_sun
        WeatherKind.CLEAR_NIGHT -> R.drawable.ic_weather_moon
        WeatherKind.PARTLY_CLOUDY -> R.drawable.ic_weather_cloud_sun
        WeatherKind.CLOUDY -> R.drawable.ic_weather_cloud
        WeatherKind.FOG -> R.drawable.ic_weather_fog
        WeatherKind.RAIN -> R.drawable.ic_weather_rain
        WeatherKind.SNOW -> R.drawable.ic_weather_snow
        WeatherKind.STORM -> R.drawable.ic_weather_storm
    }

    /** The state of doors, windows, roof, tailgate and bonnet: [closed] is null when the car never reported it. */
    class DoorsStatus(val text: String, val closed: Boolean?, val note: String?, val parts: List<String>? = null)

    /** Live while a trip runs in this car, otherwise as the car reported it at the end of its latest trip. */
    fun doorsStatus(car: CarRef, known: LastKnownCar?): DoorsStatus {
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) {
            AppState.car.snapshot().openParts?.let { return DoorsStatus(OpenPartsText.describe(it), it.isEmpty(), tr("live"), it) }
        }
        val parts = known?.openParts
        if (parts != null) {
            val at = known.openPartsAtMs ?: known.atMs
            return DoorsStatus(OpenPartsText.describe(parts), parts.isEmpty(), tr("at the end of the trip on {0}", ui.date(at)), parts)
        }
        return DoorsStatus("—", null, null)
    }

    fun currentOdometerKm(car: CarRef, s: VehicleSummary, d: CarDetails): Double? = CarTexts.currentOdometerKm(car, s, d)

    /** "32 L · range 410 km (live)", or from the end of the latest trip that had a reading. */
    fun fuelText(car: CarRef, known: LastKnownCar?): String {
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
        if (known != null) format(known.snapshot)?.let { return tr("{0} (at the end of the trip on {1})", it, ui.date(known.atMs)) }
        return "—"
    }

    fun odometerText(car: CarRef, s: VehicleSummary, d: CarDetails): String {
        if (!d.usesPlugin) {
            val km = s.manualOdometerNow(d) ?: return "—"
            return tr("{0} km", ui.km(km)) + (d.manualOdometerAtMs?.let { tr(" (entered on {0}, plus trips)", ui.date(it)) } ?: "")
        }
        val status = AppState.status
        if (status.phase != Phase.IDLE && status.carName == car.name) {
            AppState.car.snapshot().odometerKm?.let { return tr("{0} km (live)", ui.km(it)) }
        }
        val km = s.odometerKm ?: return "—"
        return tr("{0} km (at the end of the trip on {1})", ui.km(km), ui.date(s.odometerAtMs ?: 0L))
    }
}
