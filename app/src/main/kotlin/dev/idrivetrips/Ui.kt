package dev.idrivetrips

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/** Small helpers for building screens in code, and for writing numbers and dates the way the phone's language does. */
class Ui(private val context: Context) {

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    fun heading(text: String) = TextView(context).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    fun body() = TextView(context).apply { textSize = 15f }

    fun button(label: String, onClick: () -> Unit) = Button(context).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    /**
     * A rounded button in the app's blue: [filled] for the main action, otherwise a light tint. Fixed height, one line.
     * [heightDp] 44 for a normal button, about 32 for one inside a list row.
     */
    fun pill(label: String, filled: Boolean = false, heightDp: Int = 44, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = if (heightDp >= 44) 15f else 13f
        setTypeface(typeface, Typeface.BOLD)
        gravity = android.view.Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        minHeight = dp(heightDp)
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (filled) Color.WHITE else Colors.strong)
        background = GradientDrawable().apply {
            setColor(if (filled) Colors.primary else Colors.soft)
            cornerRadius = dp(heightDp / 2).toFloat()
        }
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e -> v.alpha = if (e.action == android.view.MotionEvent.ACTION_DOWN) 0.7f else if (e.action == android.view.MotionEvent.ACTION_MOVE) v.alpha else 1f; false }
    }

    fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A rounded light panel to group the parts of one thing, such as a car. */
    fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(10))
        background = GradientDrawable().apply {
            setColor(Colors.card)
            cornerRadius = dp(14).toFloat()
        }
    }

    fun gap(heightDp: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

    // ---- formatting ---------------------------------------------------------------------------------------------------

    fun f0(v: Double): String = String.format(Locale.getDefault(), "%.0f", v)
    fun f1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
    fun f5(v: Double): String = String.format(Locale.getDefault(), "%.5f", v)

    /** 50030.4 -> "50 030" in the phone's grouping. */
    fun km(v: Double): String = String.format(Locale.getDefault(), "%,.0f", v)

    fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun date(d: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(d)

    /** An amount in the currency of the phone's region (zł on a Polish phone). */
    fun money(v: Double): String = NumberFormat.getCurrencyInstance().format(v)

    fun currencySymbol(): String = NumberFormat.getCurrencyInstance().currency?.symbol ?: ""

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            ms < 60_000 -> "${ms / 1000} s"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** "2.0 L (1998 cc)" */
    fun engine(cc: Int): String = String.format(Locale.getDefault(), "%.1f L (%d cc)", cc / 1000.0, cc)
}
