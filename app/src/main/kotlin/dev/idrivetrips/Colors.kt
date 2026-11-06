package dev.idrivetrips

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * The colours of the app in the light and the dark theme. Screens ask for a name ("card", "strong"), never for a fixed
 * colour, so the same code draws both themes. [dark] is set by [prepare] before a screen is built.
 */
object Colors {
    @Volatile var dark = false

    private fun pick(light: Long, darkValue: Long): Int = (if (dark) darkValue else light).toInt() or 0xFF000000.toInt()

    /** Text and icons in the app's blue. */
    val strong get() = pick(0x0D47A1, 0x90CAF9)
    /** A pale blue panel (round buttons, selected tile). */
    val soft get() = pick(0xE3F2FD, 0x1E3A56)
    /** The main blue: filled buttons, the selected tab. */
    val primary get() = pick(0x1565C0, 0x4A90E2)
    /** A grey panel (cards, tiles). */
    val card get() = pick(0xF2F3F5, 0x2A2D33)
    val line get() = pick(0xDDDFE3, 0x3A3E45)
    val track get() = pick(0xD0D5DB, 0x3F444C)
    val bar get() = pick(0xF7F7F8, 0x1B1D21)
    val text get() = pick(0x212121, 0xECEFF1)
    /** Secondary text. */
    val muted get() = pick(0x757575, 0x9AA0A6)
    val subtle get() = pick(0x444444, 0xBDC1C6)
    val link get() = pick(0x546E9A, 0x8FA3C7)
    val warn get() = pick(0xE65100, 0xFF9E40)
    val good get() = pick(0x2E7D32, 0x66BB6A)
    val bad get() = pick(0xC62828, 0xEF5350)
    val badBg get() = pick(0xFFEBEE, 0x3B2124)
    val warnBg get() = pick(0xFFF3E0, 0x3B2D18)
    val goodBg get() = pick(0xE8F5E9, 0x1F3524)
    val neutralBg get() = pick(0xE6E8EB, 0x2A2D33)

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    /** Decides light or dark for [choice] (a setting) on a phone that is in night mode or not. */
    fun isDark(choice: String, systemNight: Boolean): Boolean = when (choice) {
        LIGHT -> false
        DARK -> true
        else -> systemNight
    }

    /** Call first in every screen's onCreate, before super.onCreate: sets [dark] and the window theme. */
    fun prepare(activity: Activity) {
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        dark = isDark(AppSettings.theme(activity), night)
        activity.setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme_Light)
    }

    fun init(context: Context) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        dark = isDark(AppSettings.theme(context), night)
    }
}
