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
        existing?.let { directory(it).deleteRecursively() }
        val directory = DownloadLocations.directory(
            DownloadLocations.choose(DownloadLocations.all(context), settings.downloadLocation), item.id,
        )
        directory.deleteRecursively()
        dao.upsert(
            DownloadEntity(
                mediaId = item.id,
                title = item.seriesTitle ?: item.title,
                subtitle = if (item.seriesTitle != null) episodeLabel(item) else item.year?.toString(),
                quality = quality,
                state = DownloadState.QUEUED,
                durationMs = item.durationMs,
                directory = directory.absolutePath,
                createdAt = System.currentTimeMillis(),
            ),
        )
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
        download?.let { directory(it).deleteRecursively() }
        download?.serverDownloadId?.let { id ->
            // Frees the prepared file on the server; it expires on its own otherwise.
            runCatching { api.send(Request.Builder().url(api.url("/api/v1/downloads/$id")).delete().build()).close() }
        }
    }

    /** Deletes every download, e.g. after signing out. */
    suspend fun clear() {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        dao.clear()
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads").deleteRecursively()
        DownloadLocations.all(context).forEach { File(it.root, "downloads").deleteRecursively() }
    }

    /** Where a download's files are; downloads from before storage could be chosen are on internal storage. */
    fun directory(download: DownloadEntity): File =
        download.directory?.let(::File)
            ?: File(File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads"), download.mediaId)

    /** False when the download is on an SD card that is not inserted (or its files are gone). */
    fun isAvailable(download: DownloadEntity): Boolean =
        download.state == DownloadState.COMPLETE && download.filePath?.let { File(it).exists() } == true

    companion object {
        const val TAG = "download"

        fun workName(mediaId: String) = "download-$mediaId"

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

    /** Keep playing in a small window when leaving the player. */
    var pictureInPicture: Boolean
        get() = prefs.getBoolean("pictureInPicture", true)
        set(value) = prefs.edit().putBoolean("pictureInPicture", value).apply()

    /** On devices with a second screen, show the video there and the controls on this one. */
    var dualScreen: Boolean
        get() = prefs.getBoolean("dualScreen", true)
        set(value) = prefs.edit().putBoolean("dualScreen", value).apply()

    /** The storage folder new downloads go to (see [DownloadLocations]); null for internal storage. */
    var downloadLocation: String?
        get() = prefs.getString("downloadLocation", null)
        set(value) = prefs.edit().putString("downloadLocation", value).apply()

    var defaultQuality: DownloadQuality
        get() = runCatching { DownloadQuality.valueOf(prefs.getString("quality", null)!!) }
            .getOrDefault(DownloadQuality.MEDIUM)
        set(value) = prefs.edit().putString("quality", value.name).apply()
}
