package org.openbased.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM libraries ORDER BY name COLLATE NOCASE")
    fun libraries(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM libraries WHERE id = :id")
    fun library(id: String): Flow<LibraryEntity?>

    @Query("SELECT * FROM media WHERE libraryId = :libraryId AND seriesTitle IS NULL ORDER BY sortTitle")
    fun itemsWithoutSeries(libraryId: String): Flow<List<MediaEntity>>

    /**
     * One row per series: its first episode, for the artwork. SQLite returns the other columns from the row that
     * holds the MIN() in the result columns.
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT *, MIN(COALESCE(seasonNumber, 0) * 10000 + COALESCE(episodeNumber, 0)) AS firstEpisode
        FROM media WHERE libraryId = :libraryId AND seriesTitle IS NOT NULL
        GROUP BY seriesTitle ORDER BY seriesTitle COLLATE NOCASE
        """,
    )
    fun series(libraryId: String): Flow<List<MediaEntity>>

    @Query(
        "SELECT * FROM media WHERE libraryId = :libraryId AND seriesTitle = :seriesTitle " +
            "ORDER BY seasonNumber, episodeNumber, sortTitle",
    )
    fun episodes(libraryId: String, seriesTitle: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    fun item(id: String): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun itemNow(id: String): MediaEntity?

    @Query(
        """
        SELECT * FROM media WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR seriesTitle LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY sortTitle LIMIT 200
        """,
    )
    fun search(query: String): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT * FROM media WHERE progressCompleted = 0 AND progressPosition > 0
        ORDER BY progressUpdatedAt DESC LIMIT 30
        """,
    )
    fun continueWatching(): Flow<List<MediaEntity>>

    @Upsert
    suspend fun upsertMedia(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteMedia(ids: List<String>)

    @Query("DELETE FROM media")
    suspend fun clearMedia()

    @Query("DELETE FROM libraries")
    suspend fun clearLibraries()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLibraries(libraries: List<LibraryEntity>)

    @Transaction
    suspend fun replaceLibraries(libraries: List<LibraryEntity>) {
        clearLibraries()
        insertLibraries(libraries)
    }

    @Query(
        "UPDATE media SET progressPosition = :position, progressDuration = :duration, " +
            "progressCompleted = :completed, progressUpdatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateProgress(id: String, position: Double, duration: Double?, completed: Boolean, updatedAt: Long)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun syncState(key: String): String?

    @Upsert
    suspend fun setSyncState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE mediaId = :mediaId")
    fun observe(mediaId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): DownloadEntity?

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: PendingProgressEntity)

    @Query("SELECT * FROM pending_progress ORDER BY recordedAt LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingProgressEntity>

    /** Removes the update only if nothing newer replaced it while it was being sent. */
    @Query("DELETE FROM pending_progress WHERE mediaId = :mediaId AND recordedAt = :recordedAt")
    suspend fun deleteIfUnchanged(mediaId: String, recordedAt: String)

    @Query("DELETE FROM pending_progress")
    suspend fun clear()
}
