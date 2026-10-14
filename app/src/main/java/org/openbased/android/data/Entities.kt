package org.openbased.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** MOVIES, TV, MUSIC or OTHER. */
    val type: String,
)

/** The catalogue, kept in sync with the server so it can be browsed and searched offline. */
@Entity(tableName = "media", indices = [Index("libraryId"), Index("seriesTitle")])
data class MediaEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val type: String,
    val title: String,
    val sortTitle: String,
    val year: Int?,
    val durationMs: Long?,
    val overview: String?,
    val genres: String,
    val poster: String?,
    val backdrop: String?,
    val seriesTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** Seconds. */
    val progressPosition: Double?,
    val progressDuration: Double?,
    val progressCompleted: Boolean,
    /** Epoch milliseconds of the last local progress change, for "continue watching" ordering. */
    val progressUpdatedAt: Long?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val key: String, val value: String)

enum class DownloadState { QUEUED, PREPARING, DOWNLOADING, COMPLETE, FAILED }

enum class DownloadQuality(val label: String, val maxHeight: Int?, val maxVideoBitrate: Long?) {
    ORIGINAL("Original", null, null),
    HIGH("1080p", 1080, 8_000_000),
    MEDIUM("720p", 720, 4_000_000),
    LOW("480p", 480, 1_500_000),
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val subtitle: String?,
    val quality: DownloadQuality,
    val state: DownloadState,
    /** The server's prepared download, for optimized qualities. */
    val serverDownloadId: String? = null,
    /** Server path of the file to fetch, once known. */
    val sourcePath: String? = null,
    val etag: String? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    /** Server-side preparation progress, 0–100. */
    val preparingPercent: Int? = null,
    val filePath: String? = null,
    /** Folder holding this download's files (internal storage or an SD card); null for older downloads. */
    val directory: String? = null,
    val posterPath: String? = null,
    /** JSON list of [LocalSubtitle]. */
    val subtitlesJson: String? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val createdAt: Long,
)

/** A progress update not yet accepted by the server; one per media item, the newest. */
@Entity(tableName = "pending_progress")
data class PendingProgressEntity(
    @PrimaryKey val mediaId: String,
    val position: Double,
    val duration: Double?,
    val completed: Boolean?,
    /** ISO-8601. */
    val recordedAt: String,
)
