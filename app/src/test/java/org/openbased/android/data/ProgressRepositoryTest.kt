package org.openbased.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openbased.android.api.OpenBasedApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ProgressRepositoryTest {

    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var progress: ProgressRepository
    private var now = Instant.parse("2026-10-14T20:00:00Z")
    private var queued = 0

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val base = server.url("/").toString().trimEnd('/')
        progress = ProgressRepository(db, OpenBasedApi(OkHttpClient(), Json { ignoreUnknownKeys = true }) { base },
            onQueued = { queued++ }) { now }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun onlyTheNewestUpdatePerItemIsSentWithItsRecordedTime() = runBlocking {
        progress.record("m1", 100.0, 7200.0)
        now = now.plusSeconds(10)
        progress.record("m1", 110.0, 7200.0)
        progress.record("m2", 50.0, 3000.0)
        assertEquals(3, queued)
        server.enqueue(MockResponse().setBody("""{"results":[{"mediaId":"m1","status":"APPLIED"},{"mediaId":"m2","status":"IGNORED"}]}"""))

        assertEquals(2, progress.flush())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, """"mediaId":"m1","position":110.0""" in body)
        assertTrue(body, """"recordedAt":"2026-10-14T20:00:10Z"""" in body)
        assertTrue(body, """"position":100.0""" !in body)
        assertEquals(0, db.progress().oldest(10).size)
    }

    @Test
    fun failuresThatMaySucceedLaterStayQueued() = runBlocking {
        progress.record("m1", 100.0, 7200.0)
        progress.record("gone", 10.0, 100.0)
        server.enqueue(MockResponse().setBody(
            """{"results":[{"mediaId":"gone","status":"FAILED","error":"MEDIA_NOT_FOUND"},
               {"mediaId":"m1","status":"FAILED","error":"INTERNAL_ERROR"}]}""",
        ))
        server.enqueue(MockResponse().setBody("""{"results":[{"mediaId":"m1","status":"FAILED","error":"INTERNAL_ERROR"}]}"""))
        // The first batch removes "gone"; the second accepts nothing, which stops the flush for a retry later.
        assertThrows(IOException::class.java) { runBlocking { progress.flush() } }
        assertEquals(listOf("m1"), db.progress().oldest(10).map { it.mediaId })
    }

    @Test
    fun localProgressIsUpdatedAtOnce() = runBlocking {
        db.catalog().upsertMedia(listOf(MediaEntity("m1", "lib", "MOVIE", "Heat", "heat", 1995, 10_000_000, null, "",
            null, null, null, null, null, null, null, false, null)))
        progress.record("m1", 9600.0, 10_000.0)
        val item = db.catalog().itemNow("m1")!!
        assertEquals(9600.0, item.progressPosition!!, 0.0)
        assertTrue("96% counts as watched", item.progressCompleted)
    }
}
