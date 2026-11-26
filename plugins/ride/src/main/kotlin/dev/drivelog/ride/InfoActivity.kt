package dev.drivelog.ride

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.drivelog.pluginapi.CarPluginContract
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The plugin's only screen, opened from Drivelog (car page → "Open Drivelog Ride"): what it does, whether its screen
 * reader is on (with the way to the accessibility settings), the tank size and the latest reading.
 */
class InfoActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tank: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(TextView(this).apply { text = getString(R.string.info_text); textSize = 16f })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad, 0, pad) }
        column.addView(status)
        column.addView(Button(this).apply {
            text = getString(R.string.open_accessibility)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@InfoActivity, R.string.no_accessibility_settings, Toast.LENGTH_LONG).show()
                }
            }
        })
        column.addView(Switch(this).apply {
            text = getString(R.string.auto_open)
            textSize = 16f
            isChecked = RideStore(this@InfoActivity).autoOpen
            setPadding(0, pad, 0, 0)
            setOnCheckedChangeListener { _, on -> RideStore(this@InfoActivity).autoOpen = on }
        })
        column.addView(Button(this).apply {
            text = getString(R.string.read_now)
            setOnClickListener {
                if (!ScreenReaderService.readNow()) Toast.makeText(this@InfoActivity, R.string.read_now_failed, Toast.LENGTH_LONG).show()
            }
        })
        column.addView(TextView(this).apply { text = getString(R.string.tank_label); textSize = 16f; setPadding(0, pad, 0, 0) })
        tank = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.tank_hint)
            RideStore(this@InfoActivity).tankLiters?.let { setText(String.format(Locale.getDefault(), "%.1f", it)) }
        }
        column.addView(tank)
        column.addView(Button(this).apply {
            text = getString(R.string.save)
            setOnClickListener { saveTank() }
        })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    private fun saveTank() {
        val text = tank.text.toString().trim().replace(',', '.')
        val litres = text.toDoubleOrNull()
        if (text.isNotEmpty() && (litres == null || litres < 1 || litres > 100)) {
            tank.error = getString(R.string.tank_error)
            return
        }
        RideStore(this).tankLiters = litres
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        show()
    }

    private fun show() {
        val drivelog = try {
            packageManager.getPackageInfo(CarPluginContract.DRIVELOG_PACKAGE, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }
        val reader = ScreenReaderService.isEnabled(this)
        val latest = RideStore(this).latest
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val lines = ArrayList<String>()
        lines += getString(if (drivelog) R.string.drivelog_installed else R.string.drivelog_missing)
        lines += getString(if (reader) R.string.reader_on else R.string.reader_off)
        if (latest == null) {
            lines += getString(R.string.no_reading)
        } else {
            val v = latest.values
            fun n(d: Double, digits: Int) = String.format(Locale.getDefault(), "%.${digits}f", d)
            lines += getString(R.string.reading_label, format.format(Date(latest.atMs)))
            v.odometerKm?.let { lines += getString(R.string.odometer_value, n(it, 0)) }
            v.fuelLiters?.let { lines += getString(R.string.fuel_value, n(it, 1)) }
            v.rangeKm?.let { lines += getString(R.string.range_value, n(it, 0)) }
            v.tyrePressuresBar?.let { p -> lines += getString(R.string.tyres_value, p.joinToString(" / ") { n(it, 1) }) }
        }
        lines += getString(R.string.version_label, packageManager.getPackageInfo(packageName, 0).versionName)
        status.text = lines.joinToString("\n")
    }
}
