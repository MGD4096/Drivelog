package dev.drivelog

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.drivelog.core.trn

/**
 * The building blocks of the car page and its dialogs: headings with a round button, tiles, form fields. Only views,
 * no data or saving, so every part of the page ([CarActivity], [ServiceLogPanel], [CarEditor]...) draws alike.
 */
class CarPageViews(private val context: Context, private val ui: Ui) {

    /** A section heading; with [symbol] it gets a round button at its right end (add, open all, open in maps). */
    fun sectionHeader(title: String, symbol: String? = null, description: String = "", onClick: () -> Unit = {}): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        container.addView(ui.heading(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (symbol != null) {
            container.addView(roundButton(symbol, description, onClick), LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { topMargin = ui.dp(7) })
        }
        return container
    }

    /** A grey tile: small caption, the value big, and a line of detail (live, or when it was read). */
    fun detailTile(label: String, value: String, note: String?): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = rounded(Colors.card, 14)
        addView(ui.body().apply { text = label; textSize = 13f; setTextColor(Colors.muted) })
        addView(ui.body().apply { text = value; textSize = 20f; setTypeface(typeface, Typeface.BOLD) })
        if (note != null) addView(ui.body().apply { text = note; textSize = 12f; setTextColor(Colors.subtle) })
    }

    /** A big number with a small caption under it. */
    fun statCell(value: String, label: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(ui.body().apply { text = value; textSize = 19f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        addView(ui.body().apply { text = label; textSize = 12f; setTextColor(Colors.muted) })
    }

    /** A green tile for money: what it covers, the amount big, and how many entries it is made of. */
    fun costTile(caption: String, amount: String, entries: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = rounded(Colors.goodBg, 14)
        addView(ui.body().apply { text = caption; textSize = 13f; setTextColor(Colors.good) })
        addView(ui.body().apply {
            text = amount
            textSize = 22f; setTextColor(Colors.good); setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        addView(ui.body().apply { text = trn(entries, "{0} entry", "{0} entries"); textSize = 13f; setTextColor(Colors.good) })
    }

    /** "2026" / "1,240 km" / "37 trips" on a light-blue tile; "no trips" when there are none. */
    fun tripTile(caption: String, count: Int, km: Double): View {
        val strong = Colors.strong
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = rounded(Colors.soft, 14)
            addView(ui.body().apply { text = caption; textSize = 13f; setTextColor(strong) })
            addView(ui.body().apply {
                text = if (count == 0) "—" else "${ui.km(km)} km"
                textSize = 22f; setTextColor(strong); setTypeface(typeface, Typeface.BOLD); maxLines = 1
            })
            addView(ui.body().apply {
                text = if (count == 0) dev.drivelog.core.tr("no trips") else trn(count, "{0} trip", "{0} trips")
                textSize = 13f; setTextColor(strong)
            })
        }
    }

    /** The round light-blue button used in every header: a symbol on a circle, 30 dp (set the size in the layout params). */
    fun roundButton(symbol: String, description: String, onClick: () -> Unit) = TextView(context).apply {
        text = symbol
        textSize = 22f
        setTextColor(Colors.strong)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = description
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Colors.soft)
        }
        isClickable = true
        setOnClickListener { onClick() }
        setPadding(0, 0, 0, ui.dp(1))
    }

    // ---- forms --------------------------------------------------------------------------------------------------------

    /** A one-line input. */
    fun field(hint: String, value: String?, type: Int, maxLength: Int = 60) = EditText(context).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        setText(value ?: "")
    }

    /** The small grey upper-case caption over a group of fields. */
    fun caption(text: String, top: Int = 16, bottom: Int = 6) = ui.body().apply {
        this.text = text.uppercase()
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(Colors.muted)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, ui.dp(top), 0, ui.dp(bottom))
    }

    /** [input] with a label above it; the label is the first child (callers may change its text). */
    fun labelled(label: String, input: EditText, hint: String): LinearLayout {
        input.hint = hint
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.body().apply { text = label; textSize = 13f; setTextColor(Colors.subtle) })
            addView(input)
        }
    }

    fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = ui.dp(radiusDp).toFloat() }
}
