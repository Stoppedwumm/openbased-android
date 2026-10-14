package org.openbased.android.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val fetcher = ResumableFetcher(OkHttpClient())
    private val content = ByteArray(10_000) { (it % 251).toByte() }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun aFullDownloadKeepsTheETagAndName() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(content)).setHeader("ETag", "\"v1\"")
                .setHeader("Content-Disposition", "attachment; filename=\"Heat (1995).mkv\"; filename*=UTF-8''Heat%20%281995%29.mkv"),
        )
        val file = tmp.newFile()
        val result = fetcher.fetch(server.url("/f").toString(), file, null) { _, _, _ -> true }
        assertArrayEquals(content, file.readBytes())
        assertEquals("\"v1\"", result.etag)
        assertEquals(10_000L, result.totalBytes)
        assertEquals("Heat (1995).mkv", result.filename)
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun aPartialFileContinuesWhenTheVersionMatches() {
        val file = tmp.newFile().apply { writeBytes(content.copyOf(4000)) }
        server.enqueue(
            MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(4000, 10_000)))
                .setHeader("ETag", "\"v1\"").setHeader("Content-Range", "bytes 4000-9999/10000"),
        )
        val result = fetcher.fetch(server.url("/f").toString(), file, "\"v1\"") { _, _, _ -> true }
        val request = server.takeRequest()
        assertEquals("bytes=4000-", request.getHeader("Range"))
        assertEquals("\"v1\"", request.getHeader("If-Range"))
        assertArrayEquals(content, file.readBytes())
        assertEquals(10_000L, result.totalBytes)
    }

    @Test
    fun aChangedFileStartsOver() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(4000) { 7 }) }
        // If-Range did not match: the server sends the whole new version with 200.
        server.enqueue(MockResponse().setBody(Buffer().write(content)).setHeader("ETag", "\"v2\""))
        val result = fetcher.fetch(server.url("/f").toString(), file, "\"v1\"") { _, _, _ -> true }
        assertArrayEquals(content, file.readBytes())
        assertEquals("\"v2\"", result.etag)
    }

    @Test
    fun withoutAnETagAPartialFileIsNotTrusted() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(4000) { 7 }) }
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        fetcher.fetch(server.url("/f").toString(), file, null) { _, _, _ -> true }
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun stoppingKeepsWhatWasDownloaded() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(300_000))).setHeader("ETag", "\"v1\""))
        val file = tmp.newFile()
        var seenEtag: String? = null
        assertThrows(ResumableFetcher.StoppedException::class.java) {
            fetcher.fetch(server.url("/f").toString(), file, null) { downloaded, _, etag ->
                seenEtag = etag
                downloaded < 100_000
            }
        }
        assertEquals("\"v1\"", seenEtag)
        assert(file.length() in 100_000..300_000)
    }

    @Test
    fun errorsAreReportedWithTheStatus() {
        server.enqueue(MockResponse().setResponseCode(410))
        val e = assertThrows(ResumableFetcher.HttpStatusException::class.java) {
            fetcher.fetch(server.url("/f").toString(), tmp.newFile(), null) { _, _, _ -> true }
        }
        assertEquals(410, e.status)
    }

    @Test
    fun contentDispositionNames() {
        assertEquals("Amélie.mkv", ResumableFetcher.filename("attachment; filename=\"Am_lie.mkv\"; filename*=UTF-8''Am%C3%A9lie.mkv"))
        assertEquals("plain.mp4", ResumableFetcher.filename("attachment; filename=\"plain.mp4\""))
        assertNull(ResumableFetcher.filename(null))
    }
}
