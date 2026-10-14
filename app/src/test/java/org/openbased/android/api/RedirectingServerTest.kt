package org.openbased.android.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openbased.android.auth.AuthManager
import org.openbased.android.auth.InMemoryTokenStore
import org.openbased.android.auth.Session

/**
 * The address typed in the app redirects to the server's own address (like http:// to https:// behind a reverse
 * proxy). HTTP clients drop the Authorization header on such redirects, so the app must call the server directly.
 */
class RedirectingServerTest {

    private val server = MockWebServer()
    private val front = MockWebServer()
    private val store = InMemoryTokenStore()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var auth: AuthManager
    private lateinit var api: OpenBasedApi
    private lateinit var base: String

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/.well-known/openid-configuration" -> MockResponse().setBody(
                    """{"issuer":"$base","authorization_endpoint":"$base/oauth2/authorize",
                       "token_endpoint":"$base/oauth2/token"}""",
                )
                "/oauth2/token" -> MockResponse()
                    .setBody("""{"access_token":"access-0","refresh_token":"refresh-0","expires_in":300}""")
                "/api/v1/libraries" ->
                    if (request.getHeader("Authorization") == "Bearer access-0") {
                        MockResponse().setBody("""{"items":[{"id":"lib1","name":"Movies","type":"MOVIES"}]}""")
                    } else {
                        MockResponse().setResponseCode(401)
                            .setBody("""{"error":"UNAUTHORIZED","message":"Authentication is required."}""")
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        front.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(301).setHeader("Location", base + request.path)
        }
        front.start()
        val http = OkHttpClient()
        auth = AuthManager(http, store, json)
        api = OpenBasedApi(http.newBuilder().addInterceptor(AuthInterceptor(auth)).build(), json) { auth.serverUrl }
    }

    @After
    fun tearDown() {
        server.shutdown()
        front.shutdown()
    }

    private val typed get() = front.url("/").toString().trimEnd('/')

    @Test
    fun signingInWithARedirectingAddressUsesTheServersOwnAddress() = runBlocking {
        val url = auth.beginLogin(typed).toHttpUrl()
        auth.completeLogin("org.openbased.android:/oauth2redirect?code=c&state=${url.queryParameter("state")}")
        assertEquals(base, store.session()!!.serverUrl)
        assertEquals(listOf("Movies"), api.libraries().map { it.name })
    }

    @Test
    fun aSignInStoredWithTheTypedAddressIsExplainedAndRepaired() = runBlocking {
        // As stored by earlier versions of the app.
        store.saveSession(Session(typed, "$base/oauth2/token", null, "access-0", "refresh-0", Long.MAX_VALUE))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.libraries() } }
        assertEquals("REDIRECTED", e.code)
        assertTrue(e.message!!, e.message!!.contains("redirected"))

        auth.updateServerAddress()
        assertEquals(base, store.session()!!.serverUrl)
        assertEquals("refresh-0", store.session()!!.refreshToken)
        assertEquals(listOf("Movies"), api.libraries().map { it.name })
    }
}
