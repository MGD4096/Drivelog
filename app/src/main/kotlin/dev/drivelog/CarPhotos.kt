package dev.drivelog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.drivelog.core.CarDetails
import dev.drivelog.core.CarDetailsStore
import dev.drivelog.core.tr
import java.io.IOException
import java.util.concurrent.ExecutorService

/**
 * The photos of one car on its page: picking pictures from the phone, copying them into the app, the strip of small
 * pictures and opening the gallery. The activity passes its results on through [onActivityResult].
 */
class CarPhotos(
    private val activity: Activity,
    private val ui: Ui,
    private val widgets: CarWidgets,
    private val details: CarDetailsStore,
    private val address: String,
    private val io: ExecutorService,
    private val onChanged: () -> Unit,
) {
    /** Opens the system picker; several pictures can be chosen at once. They are added to the car's gallery. */
    fun pick() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(pick, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            ui.toast(tr("No app found to pick a picture."))
        }
    }

    /** True when the result was the picker's (and is handled here). */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PHOTO) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) add(uris)
        return true
    }

    fun openGallery(index: Int) {
        activity.startActivity(Intent(activity, PhotoGalleryActivity::class.java).putExtra(CarActivity.EXTRA_ADDRESS, address).putExtra(PhotoGalleryActivity.EXTRA_INDEX, index))
    }

    /** The photos as a row of small pictures; the main one is marked with a star. */
    fun strip(all: List<String>): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        all.forEachIndexed { i, name ->
            val image = widgets.roundedImage(ui.dp(10)).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Colors.line)
                contentDescription = if (i == 0) tr("Main photo") else tr("Photo {0}", i + 1)
                setOnClickListener { openGallery(i) }
            }
            widgets.loadInto(image, name, 320)
            val cell = FrameLayout(activity).apply {
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (i == 0) addView(TextView(activity).apply {
                    text = "★"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Colors.primary) }
                }, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(5), ui.dp(5), 0, 0) })
            }
            row.addView(cell, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { if (i > 0) leftMargin = ui.dp(8) })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Copies the chosen pictures into the app and adds them to the gallery; the first one ever added becomes the main photo. */
    private fun add(uris: List<Uri>) {
        io.execute {
            val names = uris.mapNotNull { PhotoStore.import(activity, it) }
            activity.runOnUiThread {
                if (activity.isDestroyed) { names.forEach { PhotoStore.delete(activity, it) }; return@runOnUiThread }
                var d = details.get(address)
                val added = ArrayList<String>()
                for (n in names) {
                    val next = d.withPhoto(n)
                    if (next !== d && next != d) { d = next; added += n } else PhotoStore.delete(activity, n)
                }
                try {
                    if (added.isNotEmpty()) details.put(address, d)
                } catch (e: IOException) {
                    added.forEach { PhotoStore.delete(activity, it) }
                    ui.toast(tr("Could not save: {0}", e.message))
                    return@runOnUiThread
                }
                when {
                    added.size < uris.size && added.isEmpty() -> ui.toast(tr("Could not add the pictures (unreadable, or the gallery is full: {0} photos).", CarDetails.MAX_PHOTOS))
                    added.size < uris.size -> ui.toast(tr("Added {0} of {1} pictures.", added.size, uris.size))
                    else -> ui.toast(if (added.size == 1) tr("Photo added.") else tr("{0} photos added.", added.size))
                }
                onChanged()
            }
        }
    }

    private companion object {
        const val REQ_PHOTO = 3
    }
}
