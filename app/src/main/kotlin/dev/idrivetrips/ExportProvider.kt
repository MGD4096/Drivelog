package dev.idrivetrips

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the exported GPX/CSV files in `cache/exports` to other apps (share sheet) without needing AndroidX.
 * Read-only, and only files directly inside that folder can be reached.
 */
class ExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun exportsDir(): File = File(context!!.cacheDir, EXPORT_DIR)

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val dir = exportsDir()
        val file = File(dir, name)
        val inside = file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        return if (inside && file.isFile) file else null
    }

    override fun getType(uri: Uri): String = when {
        uri.lastPathSegment?.endsWith(".gpx") == true -> "application/gpx+xml"
        uri.lastPathSegment?.endsWith(".csv") == true -> "text/csv"
        else -> "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val file = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val EXPORT_DIR = "exports"
    }
}
