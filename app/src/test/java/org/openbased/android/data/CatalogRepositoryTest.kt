package org.openbased.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openbased.android.api.OpenBasedApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class CatalogRepositoryTest {

    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var catalog: CatalogRepository
    private val requests = mutableListOf<String>()

    /** What the fake server holds: id → title. */
    private val media = linkedMapOf("m1" to "Arrival", "m2" to "The Expanse")
    private var updated = listOf<String>()
    private var removed = listOf<String>()
    private var resetRequired = false
    private var supportsSync = true

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!
                requests += request.path!!
                return when (path.encodedPath) {
                    "/api/v1/libraries" -> json("""{"items":[{"id":"lib1","name":"Movies","type":"MOVIES"}]}""")
                    "/api/v1/media/removed" ->
                        if (!supportsSync) MockResponse().setResponseCode(404).setBody("""{"error":"MEDIA_NOT_FOUND"}""")
                        else json(
                            """{"items":[${removed.joinToString { """{"mediaId":"$it","removedAt":"2026-01-01T00:00:00Z"}""" }}],
                               "page":0,"pageSize":200,"total":${removed.size},"resetRequired":$resetRequired,
                               "syncedAt":"2026-10-14T10:00:00Z"}""",
                        )
                    "/api/v1/media" -> {
                        val ids = if (path.queryParameter("updatedSince") != null) updated else media.keys.toList()
                        json("""{"items":[${ids.joinToString { item(it, media[it]!!) }}],"total":${ids.size}}""")
                    }
                    "/api/v1/continue-watching" -> json("""{"items":[{"mediaId":"m1","position":600.0,"duration":7000.0}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val base = server.url("/").toString().trimEnd('/')
        val api = OpenBasedApi(OkHttpClient(), Json { ignoreUnknownKeys = true }) { base }
        catalog = CatalogRepository(db, api) { Instant.parse("2026-10-14T10:01:00Z") }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun item(id: String, title: String) =
        """{"id":"$id","libraryId":"lib1","type":"MOVIE","title":"$title","duration":7200000}"""

    private fun titles() = runBlocking { db.catalog().search("").first().map { it.title }.sorted() }

    @Test
    fun theFirstSyncLoadsEverythingAndRemembersSyncedAt() = runBlocking {
        assertEquals(CatalogRepository.SyncKind.FULL, catalog.sync())
        assertEquals(listOf("Arrival", "The Expanse"), titles())
        assertEquals("2026-10-14T10:00:00Z", db.catalog().syncState(CatalogRepository.SYNCED_AT))
        assertEquals(listOf("Movies"), catalog.libraries.first().map { it.name })
        // "The Expanse" sorts under E.
        assertEquals("expanse", db.catalog().itemNow("m2")!!.sortTitle)
        // Progress from continue-watching.
        assertEquals(600.0, db.catalog().itemNow("m1")!!.progressPosition!!, 0.0)
        assertEquals(listOf("m1"), catalog.continueWatching().first().map { it.id })
    }

    @Test
    fun laterSyncsOnlyApplyChanges() = runBlocking {
        catalog.sync()
        requests.clear()
        media["m3"] = "Dune"
        media.remove("m2")
        updated = listOf("m3")
        removed = listOf("m2")
        assertEquals(CatalogRepository.SyncKind.INCREMENTAL, catalog.sync())
        assertEquals(listOf("Arrival", "Dune"), titles())
        assertTrue(requests.any { "updatedSince=2026-10-14T10%3A00%3A00Z" in it })
        // Local progress survives an update of the item.
        assertEquals(600.0, db.catalog().itemNow("m1")!!.progressPosition!!, 0.0)
    }

    @Test
    fun resetRequiredReloadsEverything() = runBlocking {
        catalog.sync()
        media["m3"] = "Dune"
        resetRequired = true
        assertEquals(CatalogRepository.SyncKind.FULL, catalog.sync())
        assertEquals(listOf("Arrival", "Dune", "The Expanse"), titles())
    }

    @Test
    fun serversWithoutIncrementalSyncAlwaysReload() = runBlocking {
        supportsSync = false
        assertEquals(CatalogRepository.SyncKind.FULL, catalog.sync())
        media.remove("m1")
        assertEquals(CatalogRepository.SyncKind.FULL, catalog.sync())
        assertEquals(listOf("The Expanse"), titles())
    }

    @Test
    fun searchEscapesWildcards() = runBlocking {
        media["m3"] = "100% Wolf"
        catalog.sync()
        assertEquals(listOf("100% Wolf"), catalog.search("0%").first().map { it.title })
        assertEquals(emptyList<String>(), catalog.search("_rrival").first().map { it.title })
    }
}
