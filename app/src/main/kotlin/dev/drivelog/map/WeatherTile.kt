package dev.drivelog.map

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.drivelog.Colors
import dev.drivelog.MainActivity
import dev.drivelog.R
import dev.drivelog.Ui
import dev.drivelog.core.map.WeatherKind
import dev.drivelog.core.text.tr
import dev.drivelog.recording.PhoneLocation

/**
 * The weather tile of the Cars tab: a picture, the temperature and the wording, then how it feels and the wind, then
 * where it comes from. Fills in a moment later, once the network has answered ([WeatherLoader]).
 */
class WeatherTile(private val activity: Activity, private val ui: Ui) {

    /** The weather where the car is parked. */
    fun at(lat: Double, lon: Double): View = build(tr("at the parking spot")) { Pair(lat, lon) }

    /** The weather where the phone is now. Asks for the location permission when a tap is needed, and says so on the tile. */
    fun here(): View = build(tr("at your location")) {
        PhoneLocation.find(activity)?.let { Pair(it.lat, it.lon) }
    }

    /** [where] is shown on the tile; [locate] runs on a background thread and gives the place, or null when unknown. */
    private fun build(where: String, locate: () -> Pair<Double, Double>?): View {
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
            val place = try { locate() } catch (e: SecurityException) { null }      // location permission taken back
            val result = if (place == null) null else WeatherLoader.source(activity).get(place.first, place.second)      // network and cache errors are handled inside
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
                icon.setImageResource(icon(w.kind))
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

    private fun icon(kind: WeatherKind): Int = when (kind) {
        WeatherKind.CLEAR_DAY -> R.drawable.ic_weather_sun
        WeatherKind.CLEAR_NIGHT -> R.drawable.ic_weather_moon
        WeatherKind.PARTLY_CLOUDY -> R.drawable.ic_weather_cloud_sun
        WeatherKind.CLOUDY -> R.drawable.ic_weather_cloud
        WeatherKind.FOG -> R.drawable.ic_weather_fog
        WeatherKind.RAIN -> R.drawable.ic_weather_rain
        WeatherKind.SNOW -> R.drawable.ic_weather_snow
        WeatherKind.STORM -> R.drawable.ic_weather_storm
    }
}
