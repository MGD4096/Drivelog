package dev.idrivetrips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Car photos. A picked picture is copied into the app's own storage (scaled down, turned the right way up), so it keeps
 * working when the original is moved or deleted, and the app needs no storage permission.
 */
object PhotoStore {
    private const val MAX_SIDE = 1280
    private val NAME = Regex("p[0-9]{1,20}\\.jpg")

    private fun dir(c: Context) = File(c.filesDir, "car_photos")

    /** The file for a stored name, or null for a name this store would never have made. */
    fun file(c: Context, name: String?): File? = if (name != null && NAME.matches(name)) File(dir(c), name) else null

    /** Copies the picture at [uri] into the store. Returns its name, or null when it cannot be read as a picture. */
    fun import(c: Context, uri: Uri): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // In bounds-only mode decodeStream returns null by design; the size is in `bounds`
            val stream = c.contentResolver.openInputStream(uri) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

            val orientation = try {
                c.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
            val largest = maxOf(decoded.width, decoded.height)
            if (largest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / largest
                matrix.postScale(s, s)
            }
            val result = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            val folder = dir(c)
            if (!folder.isDirectory && !folder.mkdirs()) return null
            val name = "p${System.currentTimeMillis()}.jpg"
            FileOutputStream(File(folder, name)).use { result.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (result !== decoded) decoded.recycle()
            result.recycle()
            return name
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
    }

    fun delete(c: Context, name: String?) {
        file(c, name)?.delete()
    }

    /** Loads a stored photo no larger than about [maxSide] pixels on its longer side. */
    fun load(c: Context, name: String?, maxSide: Int): Bitmap? {
        val f = file(c, name) ?: return null
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
