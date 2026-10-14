package org.openbased.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** The server answered with an error; [code] is the API's error code, e.g. MEDIA_NOT_FOUND. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/** Calls the OpenBased REST API of the signed-in server. */
class OpenBasedApi(
    private val http: OkHttpClient,
    private val json: Json,
    private val serverUrl: () -> String?,
) {
    /** An absolute URL for a server path such as `/api/v1/artwork/…`. */
    fun url(path: String): String {
        val base = serverUrl() ?: throw ApiException(401, "SIGNED_OUT", "Not signed in")
        return if (path.startsWith("http://") || path.startsWith("https://")) path else base + path
    }

    /** An artwork URL scaled to [width] pixels (rounded up by the server). */
    fun artworkUrl(path: String?, width: Int): String? = path?.let { url("$it?w=$width") }

    suspend fun libraries(): List<Library> = get<LibraryList>("/api/v1/libraries").items

    suspend fun media(page: Int, pageSize: Int, updatedSince: String? = null): Page<MediaSummary> =
        get("/api/v1/media", "page" to page.toString(), "pageSize" to pageSize.toString(), "sort" to "title",
            "updatedSince" to updatedSince)

    suspend fun mediaDetail(id: String): MediaDetail = get("/api/v1/media/$id")

    suspend fun removedMedia(since: String, page: Int): RemovedMedia =
        get("/api/v1/media/removed", "since" to since, "page" to page.toString(), "pageSize" to "200")

    suspend fun continueWatching(): Page<ContinueItem> = get("/api/v1/continue-watching", "pageSize" to "50")

    suspend fun permissions(): Set<String> = get<Permissions>("/api/v1/users/me/permissions").permissions

    suspend fun createSession(request: CreateSession): PlaybackSession = post("/api/v1/playback/sessions", request)

    suspend fun endSession(sessionId: String) {
        send(Request.Builder().url(url("/api/v1/playback/$sessionId")).delete().build()).close()
    }

    suspend fun subtitles(mediaId: String, fileId: String?): List<SubtitleTrack> =
        get("/api/v1/media/$mediaId/subtitles", "fileId" to fileId)

    suspend fun progressBatch(updates: List<ProgressUpdate>): ProgressBatchResponse =
        post("/api/v1/progress/batch", ProgressBatch(updates))

    suspend fun createDownload(request: CreateDownload): ServerDownload = post("/api/v1/downloads", request)

    suspend fun download(id: String): ServerDownload = get("/api/v1/downloads/$id")

    /** Runs a request against the server, with the token added by [AuthInterceptor]. The caller closes it. */
    suspend fun send(request: Request): Response = withContext(Dispatchers.IO) {
        val response = http.newCall(request).execute()
        if (!response.isSuccessful && response.code != 304) {
            val body = response.body?.string().orEmpty()
            response.close()
            val error = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
            val finalUrl = response.request.url
            if (response.code == 401 && !AuthInterceptor.sameOrigin(finalUrl, request.url)) {
                throw ApiException(401, "REDIRECTED",
                    "The server redirected to ${finalUrl.scheme}://${finalUrl.host}:${finalUrl.port}, which drops the " +
                        "sign-in. Sign out and sign in with that address.")
            }
            throw ApiException(response.code, error?.error ?: "HTTP_${response.code}",
                error?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
        }
        response
    }

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String?>): T {
        val url = url(path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        return send(Request.Builder().url(url).build()).use { json.decodeFromString(it.body!!.string()) }
    }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T {
        val request = Request.Builder().url(url(path))
            .post(json.encodeToString(body).toRequestBody(JSON)).build()
        return send(request).use { json.decodeFromString(it.body!!.string()) }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
