package org.openbased.android.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openbased.android.api.OpenBasedApi
import org.openbased.android.data.AppDatabase
import org.openbased.android.data.DownloadEntity
import org.openbased.android.data.DownloadQuality
import org.openbased.android.data.DownloadState
import org.openbased.android.data.MediaEntity
import okhttp3.Request
import java.io.File

/** A subtitle track saved next to a download, as WebVTT. */
@Serializable
data class LocalSubtitle(
    val path: String,
    val language: String?,
    val label: String,
    val forced: Boolean,
    val defaultTrack: Boolean,
)

class DownloadRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val api: OpenBasedApi,
    private val json: Json,
    private val settings: Settings,
) {
    private val dao = db.downloads()

    val all = dao.all()

    fun observe(mediaId: String) = dao.observe(mediaId)

    suspend fun get(mediaId: String) = dao.get(mediaId)

    fun subtitles(download: DownloadEntity): List<LocalSubtitle> =
        download.subtitlesJson?.let { json.decodeFromString<List<LocalSubtitle>>(it) } ?: emptyList()

    suspend fun start(item: MediaEntity, quality: DownloadQuality) {
        val existing = dao.get(item.id)
        if (existing?.state == DownloadState.COMPLETE) return
        dao.upsert(
            DownloadEntity(
                mediaId = item.id,
                title = item.seriesTitle ?: item.title,
                subtitle = if (item.seriesTitle != null) episodeLabel(item) else item.year?.toString(),
                quality = quality,
                state = DownloadState.QUEUED,
                durationMs = item.durationMs,
                createdAt = System.currentTimeMillis(),
            ),
        )
        directory(item.id).deleteRecursively()
        enqueue(item.id)
    }

    /** Starts or resumes the worker for a download that is not complete. */
    fun enqueue(mediaId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.MEDIA_ID to mediaId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(mediaId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun delete(mediaId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(mediaId))
        val download = dao.get(mediaId)
        dao.delete(mediaId)
        directory(mediaId).deleteRecursively()
        download?.serverDownloadId?.let { id ->
            // Frees the prepared file on the server; it expires on its own otherwise.
            runCatching { api.send(Request.Builder().url(api.url("/api/v1/downloads/$id")).delete().build()).close() }
        }
    }

    /** Deletes every download, e.g. after signing out. */
    suspend fun clear() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        dao.clear()
        root(context).deleteRecursively()
    }

    fun directory(mediaId: String): File = File(root(context), mediaId)

    companion object {
        const val TAG = "download"

        fun workName(mediaId: String) = "download-$mediaId"

        fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

        fun episodeLabel(item: MediaEntity): String = buildString {
            if (item.seasonNumber != null && item.episodeNumber != null) {
                append("S%02dE%02d · ".format(item.seasonNumber, item.episodeNumber))
            }
            append(item.title)
        }
    }
}

/** User settings. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) = prefs.edit().putBoolean("wifiOnly", value).apply()

    var defaultQuality: DownloadQuality
        get() = runCatching { DownloadQuality.valueOf(prefs.getString("quality", null)!!) }
            .getOrDefault(DownloadQuality.MEDIUM)
        set(value) = prefs.edit().putString("quality", value.name).apply()
}
