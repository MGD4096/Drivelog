package dev.drivelog.core

import java.time.LocalDate

/** When the automatic backup runs, what its files are called, and which old ones go. */
object BackupRotation {
    const val EVERY_DAYS = 7
    const val KEEP = 4
    private const val PREFIX = "drivelog-auto-"
    private val NAME = Regex("""drivelog-auto-\d{4}-\d{2}-\d{2}\.zip""")

    /** Due when there was none yet ([lastMs] 0) or the last one is [everyDays] old (or the clock went back). */
    fun isDue(lastMs: Long, nowMs: Long, everyDays: Int = EVERY_DAYS): Boolean =
        lastMs <= 0 || nowMs < lastMs || nowMs - lastMs >= everyDays * 86_400_000L

    fun fileName(date: LocalDate): String = "$PREFIX$date.zip"

    /** Of the files in the folder, the automatic backups beyond the newest [keep]; other files are never touched. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { NAME.matches(it) }.sortedDescending().drop(keep)
}
