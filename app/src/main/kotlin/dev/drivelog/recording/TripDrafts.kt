package dev.drivelog.recording

import dev.drivelog.core.storage.TripStore
import dev.drivelog.core.trip.TripRecorder
import dev.drivelog.core.trip.TripStats
import java.io.IOException

/**
 * Crash safety: the trip being recorded is saved as a draft now and then, so a killed service loses little; a draft
 * left behind becomes a normal saved trip at the next start (when it is worth keeping).
 */
class TripDrafts(private val store: TripStore, private val recorder: TripRecorder) {

    fun save() {
        if (!recorder.isRecording) return
        try { store.saveDraft(recorder.snapshotTrip(System.currentTimeMillis())) } catch (e: IOException) { /* try again next minute */ }
    }

    fun clear() = store.clearDraft()

    fun recover() {
        val draft = store.loadDraft() ?: return
        try {
            if (TripStats.isWorthKeeping(TripStats.summarize(draft))) store.save(draft)
            store.clearDraft()
        } catch (e: IOException) {
            // keep it and try again next time
        }
    }
}
