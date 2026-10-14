package org.openbased.android.download

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads a URL into a file, continuing a partial file when the server still has the same version.
 *
 * It sends `Range: bytes=<have>-` with `If-Range: <ETag of the partial file>`. A `206` continues the file; a `200`
 * means the file changed on the server (or ranges are unsupported), so it starts over. Without a stored ETag a
 * partial file is never continued, as its version cannot be checked.
 */
class ResumableFetcher(private val http: OkHttpClient) {

    data class Result(val etag: String?, val totalBytes: Long, val filename: String?)

    fun interface Progress {
        /** Called with the bytes in the file so far; return false to stop. */
        fun update(downloaded: Long, total: Long?, etag: String?): Boolean
    }

    fun fetch(url: String, target: File, etag: String?, progress: Progress): Result {
        val have = if (target.exists() && etag != null) target.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (have > 0) {
                header("Range", "bytes=$have-")
                header("If-Range", etag!!)
            }
        }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416 && have > 0) {
                // The partial file already has every byte.
                return Result(etag, have, null)
            }
            if (response.code != 200 && response.code != 206) {
                throw HttpStatusException(response.code)
            }
            val append = response.code == 206
            val newEtag = response.header("ETag")
            val start = if (append) have else 0L
            val length = response.body!!.contentLength().takeIf { it >= 0 }
            val total = length?.let { it + start }
                ?: response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            var downloaded = start
            FileOutputStream(target, append).use { out ->
                response.body!!.source().use { source ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (!progress.update(downloaded, total, newEtag)) {
                            out.flush()
                            throw StoppedException()
                        }
                    }
                }
            }
            if (total != null && downloaded != total) {
                throw IOException("Connection closed after $downloaded of $total bytes")
            }
            return Result(newEtag, downloaded, filename(response.header("Content-Disposition")))
        }
    }

    class HttpStatusException(val status: Int) : IOException("HTTP $status")

    class StoppedException : IOException("Stopped")

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** The name from `filename*=UTF-8''…` or `filename="…"`. */
        fun filename(contentDisposition: String?): String? {
            if (contentDisposition == null) return null
            Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                return java.net.URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8")
            }
            return Regex("filename=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(contentDisposition)?.groupValues?.get(1)
        }
    }
}
