package org.openbased.android.data

import org.openbased.android.api.ApiException
import org.openbased.android.api.OpenBasedApi
import org.openbased.android.api.ProgressUpdate
import java.time.Instant

/**
 * Playback progress goes into a local queue first, so viewing offline (or with a flaky connection) is never lost.
 * The queue keeps the newest update per media item and is sent with `/progress/batch`; each update carries the
 * time it was recorded, so the server keeps whichever device's progress is newest.
 */
class ProgressRepository(
    private val db: AppDatabase,
    private val api: OpenBasedApi,
    private val onQueued: () -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    /** Positions and durations in seconds. */
    suspend fun record(mediaId: String, position: Double, duration: Double?, completed: Boolean? = null) {
        val now = clock()
        val done = completed ?: (duration != null && duration > 0 && position / duration >= 0.95)
        db.progress().upsert(PendingProgressEntity(mediaId, position, duration, completed, now.toString()))
        db.catalog().updateProgress(mediaId, position, duration, done, now.toEpochMilli())
        onQueued()
    }

    /**
     * Sends queued updates until the queue is empty. Updates the server applied, ignored as older, or rejected
     * because the media is gone are removed; others stay for the next attempt. Returns how many were sent.
     */
    suspend fun flush(): Int {
        var sent = 0
        while (true) {
            val batch = db.progress().oldest(BATCH_SIZE)
            if (batch.isEmpty()) return sent
            val results = api.progressBatch(batch.map {
                ProgressUpdate(it.mediaId, it.position, it.duration, it.completed, it.recordedAt)
            }).results
            // One queued update per media item, so results are matched by ID.
            val byId = results.associateBy { it.mediaId }
            var removed = 0
            batch.forEach { update ->
                val result = byId[update.mediaId] ?: return@forEach
                if (result.status != "FAILED" || result.error in FINAL_ERRORS) {
                    db.progress().deleteIfUnchanged(update.mediaId, update.recordedAt)
                    removed++
                }
            }
            sent += batch.size
            if (removed == 0) {
                throw ApiException(500, "PROGRESS_NOT_ACCEPTED", "The server did not accept queued progress")
            }
        }
    }

    suspend fun clear() = db.progress().clear()

    companion object {
        const val BATCH_SIZE = 500

        /** Failures that will not succeed later; such updates are dropped. */
        val FINAL_ERRORS = setOf("MEDIA_NOT_FOUND", "VALIDATION_FAILED")
    }
}
