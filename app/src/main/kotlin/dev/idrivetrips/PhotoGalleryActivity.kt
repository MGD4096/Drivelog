package dev.idrivetrips

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.idrivetrips.core.CarDetails
import dev.idrivetrips.core.FileCarDetailsStore
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import dev.idrivetrips.core.tr

/** The photos of one car, one at a time: swipe or use the arrows to move, make one the main photo, or delete it. */
class PhotoGalleryActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var details: FileCarDetailsStore
    private lateinit var address: String
    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private lateinit var mainButton: TextView
    private var index = 0
    private var shown: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Colors.prepare(this)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        address = intent.getStringExtra(CarActivity.EXTRA_ADDRESS) ?: run { finish(); return }
        details = FileCarDetailsStore(File(filesDir, "cars.json"))
        index = savedInstanceState?.getInt(KEY_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = tr("Photo of the car")
        }
        counter = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        fun round(symbol: String, description: String, onClick: () -> Unit) = TextView(this).apply {
            text = symbol
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = description
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(110, 0, 0, 0)) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = ui.dp(22).toFloat(); setColor(Color.argb(140, 0, 0, 0)) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        mainButton = pill(tr("★  Set as main photo")) { setAsMain() }
        val deleteButton = pill(tr("Delete")) { confirmDelete() }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(mainButton)
            addView(deleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(12) })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(round("‹", tr("Back")) { finish() }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(12), ui.dp(12), 0, 0) })
            addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(12) })
            addView(round("‹", tr("Previous photo")) { move(-1) }, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = ui.dp(8) })
            addView(round("›", tr("Next photo")) { move(1) }, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = ui.dp(8) })
            addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(20) })
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                }
                insets
            }
        }
        // Swiping left or right moves to the next or the previous photo
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 600f) { move(if (vx < 0) 1 else -1); return true }
                return false
            }
        })
        image.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun photos(): List<String> = details.get(address).allPhotos

    private fun move(step: Int) {
        val all = photos()
        if (all.size < 2) return
        index = (index + step + all.size) % all.size
        show()
    }

    private fun show() {
        val all = photos()
        if (all.isEmpty()) { finish(); return }
        index = index.coerceIn(0, all.size - 1)
        val name = all[index]
        shown = name
        counter.text = "${index + 1} / ${all.size}" + if (index == 0) "  ·  " + tr("main") else ""
        mainButton.visibility = if (index == 0) android.view.View.GONE else android.view.View.VISIBLE
        image.setImageDrawable(null)
        val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceAtMost(2048)
        try {
            io.execute {
                val bmp = PhotoStore.load(this, name, side)
                runOnUiThread { if (!isDestroyed && shown == name) image.setImageBitmap(bmp) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // closing
        }
    }

    private fun setAsMain() {
        val name = shown ?: return
        try {
            details.put(address, details.get(address).withMainPhoto(name))
        } catch (e: IOException) {
            toast(tr("Could not save: {0}", e.message))
            return
        }
        index = 0
        show()
        toast(tr("This is now the main photo."))
    }

    private fun confirmDelete() {
        val name = shown ?: return
        AlertDialog.Builder(this)
            .setTitle(tr("Delete this photo?"))
            .setPositiveButton(tr("Delete")) { _, _ ->
                try {
                    details.put(address, details.get(address).withoutPhoto(name))
                } catch (e: IOException) {
                    toast(tr("Could not save: {0}", e.message))
                    return@setPositiveButton
                }
                PhotoStore.delete(this, name)
                show()
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_INDEX = "index"
        private const val KEY_INDEX = "index"
    }
}
