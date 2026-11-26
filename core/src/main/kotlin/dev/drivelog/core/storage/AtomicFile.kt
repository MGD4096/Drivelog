package dev.drivelog.core.storage

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replaces a file in one step: the new content goes to `name.tmp` next to it, which is then moved over the old file.
 * A crash or a full disk while writing leaves the old file whole, never half of the new one. Every store writes
 * through here.
 */
object AtomicFile {
    /** Writes [text] as UTF-8. Creates the folder when needed; throws [IOException] when it cannot write. */
    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))

    /** Writes [bytes]; [lastModifiedMs], when given, becomes the file's time (caches that expire by age use it). */
    fun write(target: File, bytes: ByteArray, lastModifiedMs: Long? = null) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            tmp.writeBytes(bytes)
            lastModifiedMs?.let { tmp.setLastModified(it) }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    /** The ending of the half-written file; folders scanned for data skip such files. */
    const val TMP_SUFFIX = ".tmp"
}
