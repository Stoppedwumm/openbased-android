package org.openbased.android.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Request
import org.openbased.android.OpenBasedApp
import org.openbased.android.R
import org.openbased.android.api.ApiException
import org.openbased.android.api.CreateDownload
import org.openbased.android.api.ServerDownload
import org.openbased.android.data.DownloadEntity
import org.openbased.android.data.DownloadQuality
import org.openbased.android.data.DownloadState
import org.openbased.android.playback.DeviceCapabilities
import java.io.File
import java.io.IOException

/**
 * Downloads one media item for offline playback:
 *
 * 1. For [DownloadQuality.ORIGINAL], the original file (`/media/{id}/download`). Otherwise asks the server to
 *    prepare a copy this device can play within the quality's limits (`POST /downloads`) and waits until it is
 *    ready. Servers without `/downloads` get the original instead.
 * 2. Fetches the file, resuming a partial one when the server's ETag still matches.
 * 3. Saves the text subtitle tracks as WebVTT and the poster.
 *
 * Progress is kept in the database, so a stopped or killed worker continues where it was.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as OpenBasedApp).container
    private val dao = container.db.downloads()
    private val api = container.api
    private val mediaId = params.inputData.getString(MEDIA_ID)!!
    private var lastUpdate = 0L

    override suspend fun doWork(): Result {
        var download = dao.get(mediaId) ?: return Result.success()
        if (download.state == DownloadState.COMPLETE) return Result.success()
        setForeground(foregroundInfo(download))
        val dir = container.downloads.directory(mediaId).apply { mkdirs() }
        return try {
            if (download.sourcePath == null) {
                download = prepare(download)
            }
            download = fetch(download, dir)
            download = saveExtras(download, dir)
            dao.upsert(download.copy(state = DownloadState.COMPLETE, error = null))
            Result.success()
        } catch (e: ResumableFetcher.StoppedException) {
            Result.retry()
        } catch (e: ApiException) {
            if (e.status >= 500 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(e.message)
        } catch (e: ResumableFetcher.HttpStatusException) {
            if (e.status >= 500 && runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(e.message)
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                dao.get(mediaId)?.let { dao.upsert(it.copy(error = e.message)) }
                Result.retry()
            } else {
                fail(e.message)
            }
        }
    }

    private suspend fun fail(message: String?): Result {
        dao.get(mediaId)?.let { dao.upsert(it.copy(state = DownloadState.FAILED, error = message ?: "Failed")) }
        return Result.failure()
    }

    /** Decides what to fetch; waits for the server to prepare an optimized copy. */
    private suspend fun prepare(start: DownloadEntity): DownloadEntity {
        var download = start
        if (download.quality == DownloadQuality.ORIGINAL) {
            return original(download)
        }
        var server: ServerDownload = try {
            download.serverDownloadId?.let { id ->
                runCatching { api.download(id) }.getOrNull()?.takeIf { it.status == "PENDING" || it.status == "READY" }
            } ?: api.createDownload(
                CreateDownload(
                    mediaId = mediaId,
                    capabilities = DeviceCapabilities.device,
                    maxHeight = download.quality.maxHeight,
                    maxVideoBitrate = download.quality.maxVideoBitrate,
                ),
            )
        } catch (e: ApiException) {
            // Servers without optimized downloads answer 404 for the path.
            if (e.status == 404 && e.code != "MEDIA_NOT_FOUND") return original(download) else throw e
        }
        download = download.copy(state = DownloadState.PREPARING, serverDownloadId = server.id)
        dao.upsert(download)
        while (server.status == "PENDING") {
            if (isStopped) throw ResumableFetcher.StoppedException()
            download = download.copy(preparingPercent = server.progress)
            dao.upsert(download)
            setForeground(foregroundInfo(download))
            delay(POLL_INTERVAL_MS)
            server = api.download(server.id)
        }
        if (server.status != "READY" || server.url == null) {
            throw ApiException(422, "DOWNLOAD_${server.status}", server.error ?: "The server could not prepare it")
        }
        download = download.copy(sourcePath = server.url, totalBytes = server.size, preparingPercent = 100)
        dao.upsert(download)
        return download
    }

    private suspend fun original(download: DownloadEntity): DownloadEntity {
        val detail = api.mediaDetail(mediaId)
        val file = detail.files.firstOrNull() ?: throw ApiException(404, "FILE_NOT_FOUND", "The item has no file")
        return download.copy(
            sourcePath = "/api/v1/media/$mediaId/download?fileId=${file.id}",
            totalBytes = file.size,
            durationMs = detail.duration ?: download.durationMs,
        ).also { dao.upsert(it) }
    }

    private suspend fun fetch(start: DownloadEntity, dir: File): DownloadEntity {
        var download = start.copy(state = DownloadState.DOWNLOADING, error = null)
        dao.upsert(download)
        val part = File(dir, "media.part")
        val url = api.url(download.sourcePath!!)
        val result = withContext(Dispatchers.IO) {
            ResumableFetcher(container.http).fetch(url, part, download.etag) { downloaded, total, etag ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                lastUpdate = now
                    download = download.copy(
                        downloadedBytes = downloaded,
                        totalBytes = total ?: download.totalBytes,
                        etag = etag,
                    )
                    runBlocking {
                        dao.upsert(download)
                        setForeground(foregroundInfo(download))
                    }
                }
                !isStopped
            }
        }
        val extension = result.filename?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: "mp4"
        val target = File(dir, "media.$extension")
        if (!part.renameTo(target)) throw IOException("Could not move the download into place")
        download = download.copy(
            etag = result.etag,
            totalBytes = result.totalBytes,
            downloadedBytes = result.totalBytes,
            filePath = target.absolutePath,
        )
        dao.upsert(download)
        return download
    }

    /** Subtitles and poster are optional: a failure here does not fail the download. */
    private suspend fun saveExtras(download: DownloadEntity, dir: File): DownloadEntity {
        val subtitles = mutableListOf<LocalSubtitle>()
        runCatching { api.subtitles(mediaId, null) }.getOrDefault(emptyList()).filter { it.text }.forEach { track ->
            runCatching {
                val file = File(dir, "subtitle-${track.id}.vtt")
                save("/api/v1/media/$mediaId/subtitles/${track.id}", file)
                subtitles += LocalSubtitle(
                    path = file.absolutePath,
                    language = track.language,
                    label = listOfNotNull(track.language?.uppercase(), track.title, "forced".takeIf { track.forced })
                        .joinToString(" · ").ifBlank { "Subtitles" },
                    forced = track.forced,
                    defaultTrack = track.defaultTrack,
                )
            }
        }
        val poster = container.db.catalog().itemNow(mediaId)?.poster?.let { path ->
            runCatching { File(dir, "poster.jpg").also { save("$path?w=342", it) }.absolutePath }.getOrNull()
        }
        return download.copy(subtitlesJson = container.json.encodeToString(subtitles.toList()), posterPath = poster)
    }

    private suspend fun save(path: String, file: File) {
        api.send(Request.Builder().url(api.url(path)).build()).use { response ->
            file.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
    }

    private fun foregroundInfo(download: DownloadEntity): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW),
        )
        val total = download.totalBytes
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(download.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        when {
            download.state == DownloadState.PREPARING ->
                builder.setContentText("Preparing on the server").setProgress(100, download.preparingPercent ?: 0,
                    download.preparingPercent == null)
            total != null && total > 0 ->
                builder.setContentText(download.subtitle)
                    .setProgress(1000, (download.downloadedBytes * 1000 / total).toInt(), false)
            else -> builder.setContentText(download.subtitle).setProgress(0, 0, true)
        }
        val id = mediaId.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, builder.build())
        }
    }

    companion object {
        const val MEDIA_ID = "mediaId"
        const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 5
        private const val POLL_INTERVAL_MS = 3_000L
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
