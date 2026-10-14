package org.openbased.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request and response bodies of the OpenBased REST API (/api/v1). Unknown fields are ignored, so newer servers
// that add fields keep working.

@Serializable
data class Discovery(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 300,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
data class OAuthError(val error: String = "", @SerialName("error_description") val description: String? = null)

@Serializable
data class ApiError(val error: String = "", val message: String = "")

@Serializable
data class Page<T>(val items: List<T>, val page: Int = 0, val pageSize: Int = 0, val total: Long = 0)

@Serializable
data class Library(val id: String, val name: String, val type: String)

@Serializable
data class LibraryList(val items: List<Library>)

@Serializable
data class UserProgress(val position: Double, val duration: Double? = null, val completed: Boolean = false)

@Serializable
data class MediaSummary(
    val id: String,
    val libraryId: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    /** Milliseconds. */
    val duration: Long? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val poster: String? = null,
    val backdrop: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val progress: UserProgress? = null,
)

@Serializable
data class FileInfo(
    val id: String,
    val container: String? = null,
    val size: Long = 0,
    val duration: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ArtworkLinks(val poster: String? = null, val backdrop: String? = null)

@Serializable
data class MediaDetail(
    val id: String,
    val libraryId: String,
    val type: String,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val duration: Long? = null,
    val artwork: ArtworkLinks = ArtworkLinks(),
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val files: List<FileInfo> = emptyList(),
    val progress: UserProgress? = null,
)

@Serializable
data class RemovedItem(val mediaId: String, val removedAt: String)

@Serializable
data class RemovedMedia(
    val items: List<RemovedItem> = emptyList(),
    val page: Int = 0,
    val pageSize: Int = 0,
    val total: Long = 0,
    val resetRequired: Boolean = false,
    val syncedAt: String,
)

@Serializable
data class ContinueItem(val mediaId: String, val position: Double, val duration: Double? = null)

@Serializable
data class Permissions(val permissions: Set<String>)

@Serializable
data class Capabilities(val containers: Set<String>, val videoCodecs: Set<String>, val audioCodecs: Set<String>)

@Serializable
data class Device(val name: String, val platform: String)

@Serializable
data class CreateSession(
    val mediaId: String,
    val fileId: String? = null,
    val device: Device,
    val capabilities: Capabilities,
)

@Serializable
data class PlaybackSession(
    val sessionId: String,
    val mediaId: String,
    val fileId: String,
    /** DIRECT_PLAY, REMUX or TRANSCODE. */
    val mode: String,
    val container: String? = null,
    val streamUrl: String,
)

@Serializable
data class SubtitleTrack(
    val id: String,
    val source: String,
    val language: String? = null,
    val title: String? = null,
    val format: String? = null,
    val forced: Boolean = false,
    val defaultTrack: Boolean = false,
    val hearingImpaired: Boolean = false,
    val text: Boolean = true,
)

/** Positions and durations in seconds; [recordedAt] is ISO-8601. */
@Serializable
data class ProgressUpdate(
    val mediaId: String,
    val position: Double,
    val duration: Double? = null,
    val completed: Boolean? = null,
    val recordedAt: String,
)

@Serializable
data class ProgressBatch(val updates: List<ProgressUpdate>)

@Serializable
data class ProgressBatchResult(val mediaId: String, val status: String, val error: String? = null)

@Serializable
data class ProgressBatchResponse(val results: List<ProgressBatchResult>)

@Serializable
data class CreateDownload(
    val mediaId: String,
    val fileId: String? = null,
    val capabilities: Capabilities,
    val maxHeight: Int? = null,
    val maxVideoBitrate: Long? = null,
)

@Serializable
data class ServerDownload(
    val id: String,
    val mediaId: String,
    val fileId: String,
    /** PENDING, READY, FAILED, CANCELLED or EXPIRED. */
    val status: String,
    val mode: String,
    val container: String? = null,
    val progress: Int? = null,
    val size: Long? = null,
    val error: String? = null,
    val url: String? = null,
)
