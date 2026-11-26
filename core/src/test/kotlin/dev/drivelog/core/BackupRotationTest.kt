package dev.drivelog.core

import dev.drivelog.core.storage.BackupRotation
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRotationTest {
    private val day = 86_400_000L

    @Test
    fun dueAtFirstThenWeekly() {
        assertTrue(BackupRotation.isDue(0, 5 * day))
        assertFalse(BackupRotation.isDue(10 * day, 16 * day))
        assertTrue(BackupRotation.isDue(10 * day, 17 * day))
        assertTrue(BackupRotation.isDue(10 * day, 9 * day))                  // the clock went back
    }

    @Test
    fun keepsTheNewestAutomaticOnesAndNothingElseIsDeleted() {
        assertEquals("drivelog-auto-2026-11-26.zip", BackupRotation.fileName(LocalDate.of(2026, 11, 26)))
        val names = listOf(
            "drivelog-auto-2026-10-01.zip", "drivelog-auto-2026-11-26.zip", "drivelog-auto-2026-11-19.zip",
            "drivelog-backup-2026-01-01.zip", "notes.txt", "drivelog-auto-2026-11-12.zip", "drivelog-auto-2026-11-05.zip",
        )
        assertEquals(listOf("drivelog-auto-2026-10-01.zip"), BackupRotation.toDelete(names))
        assertEquals(emptyList<String>(), BackupRotation.toDelete(names.take(3)))
    }
}
