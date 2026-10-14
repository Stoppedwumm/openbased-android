package org.openbased.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openbased.android.api.ApiException
import org.openbased.android.api.AuthInterceptor
import org.openbased.android.api.Capabilities
import org.openbased.android.api.CreateDownload
import org.openbased.android.api.CreateSession
import org.openbased.android.api.Device
import org.openbased.android.api.OpenBasedApi
import org.openbased.android.auth.AuthManager
import org.openbased.android.auth.InMemoryTokenStore
import org.openbased.android.data.AppDatabase
import org.openbased.android.data.CatalogRepository
import org.openbased.android.data.ProgressRepository
import org.openbased.android.download.ResumableFetcher
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the app's networking against a real OpenBased server: sign-in through the server's login page as the
 * browser would, token rotation, catalogue sync, playback sessions, subtitles, progress and downloads. Skipped
 * unless -PliveServer, -PliveUser and -PlivePassword are given; the server needs at least one video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LiveServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = System.getProperty("openbased.liveServer")
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val store = InMemoryTokenStore()
    private lateinit var auth: AuthManager
    private lateinit var http: OkHttpClient
    private lateinit var api: OpenBasedApi
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        assumeTrue("Set -PliveServer to run", server != null)
        val base = OkHttpClient()
        auth = AuthManager(base, store, json)
        http = base.newBuilder().addInterceptor(AuthInterceptor(auth)).build()
        api = OpenBasedApi(http, json) { auth.serverUrl }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /** What the Custom Tab does: follow the sign-in until the server redirects to the app. */
    private fun browserSignIn(authorizeUrl: String): String {
        val jar = mutableMapOf<String, Cookie>()
        val browser = OkHttpClient.Builder().followRedirects(false).cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = cookies.forEach { jar[it.name] = it }
            override fun loadForRequest(url: HttpUrl) = jar.values.toList()
        }).build()
        fun get(url: String) = browser.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute()
        var response = get(authorizeUrl)
        assertTrue(response.header("Location")!!.endsWith("/login"))
        response.close()
        val page = get("$server/login").use { it.body!!.string() }
        val csrf = Regex("name=\"_csrf\" type=\"hidden\" value=\"([^\"]+)\"").find(page)!!.groupValues[1]
        response = browser.newCall(
            Request.Builder().url("$server/login").post(
                FormBody.Builder().add("username", System.getProperty("openbased.liveUser"))
                    .add("password", System.getProperty("openbased.livePassword")).add("_csrf", csrf).build(),
            ).build(),
        ).execute()
        var next = response.header("Location")!!
        response.close()
        while (!next.startsWith(AuthManager.REDIRECT_URI)) {
            response = get(next)
            next = response.header("Location") ?: error("No redirect from $next: HTTP ${response.code}")
            response.close()
        }
        return next
    }

    @Test
    fun theAppWorksAgainstARealServer() = runBlocking {
        // Sign in with PKCE through the login page.
        auth.completeLogin(browserSignIn(auth.beginLogin(server!!)))
        assertTrue(auth.signedIn.value)
        val firstRefresh = store.session()!!.refreshToken!!
        assertTrue("media.download" in api.permissions())

        // Several requests seeing the same expired token refresh once; the sign-in survives (no reuse detection).
        val token = store.session()!!.accessToken
        val refreshed = (1..5).map { async(Dispatchers.IO) { auth.refresh(token) } }.awaitAll()
        assertEquals(1, refreshed.toSet().size)
        assertTrue(store.session()!!.refreshToken != firstRefresh)
        assertTrue(api.permissions().isNotEmpty())

        // Catalogue: a full sync, then an incremental one.
        val catalog = CatalogRepository(db, api)
        assertEquals(CatalogRepository.SyncKind.FULL, catalog.sync())
        val kind = catalog.sync()
        println("Second sync: $kind")
        val item = db.catalog().search("").first().first { it.durationMs != null }
        val detail = api.mediaDetail(item.id)
        val file = detail.files.first()

        // Playback: an MP4-only device gets a remuxed or transcoded stream, which honours ?start=.
        val session = api.createSession(
            CreateSession(item.id, device = Device("Test", "JVM"),
                capabilities = Capabilities(setOf("mp4"), setOf("h264"), setOf("aac"))),
        )
        println("Playback mode for ${file.container}: ${session.mode}")
        http.newCall(Request.Builder().url(api.url(session.streamUrl) + "?start=1").build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.source().request(8))
        }
        api.endSession(session.sessionId)

        // Subtitles: every text track is WebVTT.
        val subtitles = api.subtitles(item.id, file.id)
        println("Subtitle tracks: ${subtitles.map { "${it.source}:${it.language}" }}")
        subtitles.filter { it.text }.forEach { track ->
            api.send(Request.Builder().url(api.url("/api/v1/media/${item.id}/subtitles/${track.id}")).build()).use {
                assertTrue(it.body!!.string().startsWith("WEBVTT"))
            }
        }

        // Progress recorded offline is sent with its time and shows up on the server.
        val progress = ProgressRepository(db, api, onQueued = {})
        progress.record(item.id, 3.0, (item.durationMs ?: 10_000) / 1000.0)
        assertEquals(1, progress.flush())
        assertEquals(3.0, api.mediaDetail(item.id).progress!!.position, 0.01)

        // Original download, resumed halfway with If-Range.
        val target = tmp.newFile()
        val fetcher = ResumableFetcher(http)
        val full = fetcher.fetch(api.url("/api/v1/media/${item.id}/download?fileId=${file.id}"), target, null) { _, _, _ -> true }
        assertEquals(file.size, full.totalBytes)
        val bytes = target.readBytes()
        target.writeBytes(bytes.copyOf(bytes.size / 2))
        fetcher.fetch(api.url("/api/v1/media/${item.id}/download?fileId=${file.id}"), target, full.etag) { _, _, _ -> true }
        assertTrue(bytes.contentEquals(target.readBytes()))

        // Optimized download, if the server has it: scaled to 144p by a job, then fetched.
        try {
            var download = api.createDownload(
                CreateDownload(item.id, capabilities = Capabilities(setOf("mp4"), setOf("h264"), setOf("aac")), maxHeight = 144),
            )
            while (download.status == "PENDING") {
                delay(500)
                download = api.download(download.id)
            }
            println("Optimized download: ${download.mode} ${download.status} ${download.size} bytes ${download.error ?: ""}")
            assertEquals("READY", download.status)
            val prepared = tmp.newFile()
            val result = fetcher.fetch(api.url(download.url!!), prepared, null) { _, _, _ -> true }
            assertEquals(download.size, result.totalBytes)
            assertEquals("ftyp", String(prepared.readBytes(), 4, 4))
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            println("Server has no optimized downloads; skipped")
        }

        // Signing out revokes the sign-in on the server.
        val access = store.session()!!.accessToken
        auth.signOut()
        assertFalse(auth.signedIn.value)
        OkHttpClient().newCall(Request.Builder().url("$server/api/v1/users/me/permissions")
            .header("Authorization", "Bearer $access").build()).execute().use { assertEquals(401, it.code) }
        assertNotNull(kind)
    }
}
