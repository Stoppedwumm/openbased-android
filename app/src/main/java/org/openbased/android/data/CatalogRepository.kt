package org.openbased.android.data

import androidx.room.withTransaction
import org.openbased.android.api.ApiException
import org.openbased.android.api.MediaSummary
import org.openbased.android.api.OpenBasedApi
import java.time.Instant
import java.util.Locale

/**
 * Keeps the local catalogue in sync with the server. With a stored `syncedAt` it asks only for what changed
 * (`/media/removed`, then `/media?updatedSince=`); it reloads everything the first time, when the server says
 * `resetRequired`, or when the server is older and lacks `/media/removed`.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val api: OpenBasedApi,
    private val clock: () -> Instant = Instant::now,
) {
    private val dao = db.catalog()

    val libraries = dao.libraries()

    fun library(id: String) = dao.library(id)
    fun itemsWithoutSeries(libraryId: String) = dao.itemsWithoutSeries(libraryId)
    fun series(libraryId: String) = dao.series(libraryId)
    fun episodes(libraryId: String, seriesTitle: String) = dao.episodes(libraryId, seriesTitle)
    fun item(id: String) = dao.item(id)
    fun continueWatching() = dao.continueWatching()

    fun search(query: String) =
        dao.search(query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))

    enum class SyncKind { FULL, INCREMENTAL }

    /** Brings the catalogue up to date; returns what kind of sync ran. */
    suspend fun sync(): SyncKind {
        dao.replaceLibraries(api.libraries().map { LibraryEntity(it.id, it.name, it.type) })
        val since = dao.syncState(SYNCED_AT)
        val kind = if (since == null) SyncKind.FULL else incremental(since) ?: SyncKind.FULL
        if (kind == SyncKind.FULL) {
            full()
        }
        refreshContinueWatching()
        return kind
    }

    /** Applies removals and changes since [since]; null when a full reload is needed instead. */
    private suspend fun incremental(since: String): SyncKind? {
        val removed = mutableListOf<String>()
        var page = 0
        var syncedAt: String
        while (true) {
            val result = try {
                api.removedMedia(since, page)
            } catch (e: ApiException) {
                // Servers without incremental sync answer 404 (the path looks like a media ID).
                if (e.status == 404) return null else throw e
            }
            if (result.resetRequired) return null
            syncedAt = result.syncedAt
            removed += result.items.map { it.mediaId }
            if ((page + 1).toLong() * result.pageSize >= result.total || result.items.isEmpty()) break
            page++
        }
        val changed = allPages { p -> api.media(p, PAGE_SIZE, updatedSince = since).items }
        db.withTransaction {
            if (removed.isNotEmpty()) dao.deleteMedia(removed)
            dao.upsertMedia(changed.map { it.toEntity(dao.itemNow(it.id)) })
            dao.setSyncState(SyncStateEntity(SYNCED_AT, syncedAt))
        }
        return SyncKind.INCREMENTAL
    }

    private suspend fun full() {
        // Ask for syncedAt before loading, so changes made while loading are picked up next time.
        val syncedAt = try {
            api.removedMedia(clock().toString(), 0).syncedAt
        } catch (e: ApiException) {
            if (e.status == 404) null else throw e
        }
        val items = allPages { p -> api.media(p, PAGE_SIZE).items }
        db.withTransaction {
            val local = items.map { it.toEntity(dao.itemNow(it.id)) }
            dao.clearMedia()
            dao.upsertMedia(local)
            if (syncedAt != null) {
                dao.setSyncState(SyncStateEntity(SYNCED_AT, syncedAt))
            }
        }
    }

    /** Progress changes do not count as item changes on the server, so read it separately. */
    private suspend fun refreshContinueWatching() {
        val now = clock().toEpochMilli()
        val items = api.continueWatching().items
        items.forEachIndexed { index, item ->
            // Keep the server's order (most recent first).
            dao.updateProgress(item.mediaId, item.position, item.duration, false, now - index)
        }
    }

    private suspend fun allPages(load: suspend (Int) -> List<MediaSummary>): List<MediaSummary> {
        val all = mutableListOf<MediaSummary>()
        var page = 0
        while (true) {
            val items = load(page)
            all += items
            if (items.size < PAGE_SIZE) return all
            page++
        }
    }

    /** Forgets everything, e.g. after signing out. */
    suspend fun clear() {
        db.withTransaction {
            dao.clearMedia()
            dao.clearLibraries()
            dao.clearSyncState()
        }
    }

    companion object {
        const val SYNCED_AT = "catalogSyncedAt"
        const val PAGE_SIZE = 200

        /** Keeps local progress newer than what the list reported. */
        fun MediaSummary.toEntity(existing: MediaEntity?): MediaEntity = MediaEntity(
            id = id,
            libraryId = libraryId,
            type = type,
            title = title,
            sortTitle = sortKey(title),
            year = year,
            durationMs = duration,
            overview = overview,
            genres = genres.joinToString(", "),
            poster = poster,
            backdrop = backdrop,
            seriesTitle = seriesTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            progressPosition = progress?.position ?: existing?.progressPosition,
            progressDuration = progress?.duration ?: existing?.progressDuration,
            progressCompleted = progress?.completed ?: existing?.progressCompleted ?: false,
            progressUpdatedAt = existing?.progressUpdatedAt,
        )

        /** "The Expanse" sorts under E. */
        fun sortKey(title: String): String {
            val lower = title.lowercase(Locale.ROOT)
            for (article in listOf("the ", "a ", "an ")) {
                if (lower.startsWith(article)) return lower.removePrefix(article)
            }
            return lower
        }
    }
}
