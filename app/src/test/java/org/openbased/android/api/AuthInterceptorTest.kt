package org.openbased.android.api

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.openbased.android.auth.AuthManager
import org.openbased.android.auth.InMemoryTokenStore
import org.openbased.android.auth.Session

class AuthInterceptorTest {

    private val server = MockWebServer()
    private val other = MockWebServer()
    private val store = InMemoryTokenStore()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/oauth2/token" -> MockResponse()
                    .setBody("""{"access_token":"fresh","refresh_token":"refresh-2","expires_in":300}""")
                else ->
                    if (request.getHeader("Authorization") == "Bearer fresh") MockResponse().setBody("ok")
                    else MockResponse().setResponseCode(401)
            }
        }
        server.start()
        other.enqueue(MockResponse().setBody("elsewhere"))
        other.start()
        val base = server.url("/").toString().trimEnd('/')
        store.saveSession(Session(base, "$base/oauth2/token", null, "stale", "refresh-1", Long.MAX_VALUE))
        val auth = AuthManager(OkHttpClient(), store, Json { ignoreUnknownKeys = true })
        client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(auth)).build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    @Test
    fun a401IsRetriedOnceWithARefreshedToken() {
        val response = client.newCall(Request.Builder().url(server.url("/api/v1/media")).build()).execute()
        assertEquals(200, response.code)
        assertEquals("ok", response.body!!.string())
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().path)
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-2", store.session()!!.refreshToken)
    }

    @Test
    fun otherHostsNeverSeeTheToken() {
        client.newCall(Request.Builder().url(other.url("/poster.jpg")).build()).execute().close()
        assertNull(other.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun originsMatchExactly() {
        fun url(s: String) = s.toHttpUrl()
        assertEquals(true, AuthInterceptor.sameOrigin(url("https://media.example.com/a"), url("https://media.example.com")))
        assertEquals(false, AuthInterceptor.sameOrigin(url("https://media.example.com.evil.com/"), url("https://media.example.com")))
        assertEquals(false, AuthInterceptor.sameOrigin(url("http://bigbox:8080/"), url("http://bigbox:80")))
        assertEquals(false, AuthInterceptor.sameOrigin(url("http://bigbox/"), url("https://bigbox")))
    }
}
