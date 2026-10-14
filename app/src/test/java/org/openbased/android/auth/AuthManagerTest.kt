package org.openbased.android.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AuthManagerTest {

    private val server = MockWebServer()
    private val store = InMemoryTokenStore()
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_000_000L
    private val refreshes = AtomicInteger()
    private val issued = mutableListOf<String>()
    @Volatile private var refreshResponse: () -> MockResponse = { rotate() }

    private lateinit var auth: AuthManager
    private lateinit var base: String

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body.clone().readUtf8()
                return when {
                    request.path == "/.well-known/openid-configuration" -> MockResponse().setBody(
                        """{"issuer":"$base","authorization_endpoint":"$base/oauth2/authorize",
                           "token_endpoint":"$base/oauth2/token","revocation_endpoint":"$base/oauth2/revoke"}""",
                    )
                    request.path == "/oauth2/token" && "grant_type=authorization_code" in form ->
                        if ("code_verifier=" in form && "code=the-code" in form) tokens("access-0", "refresh-0")
                        else MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    request.path == "/oauth2/token" && "grant_type=refresh_token" in form -> {
                        refreshes.incrementAndGet()
                        // Reuse detection: only the latest refresh token is accepted.
                        val sent = form.substringAfter("refresh_token=").substringBefore("&")
                        if (sent != (issued.lastOrNull() ?: "refresh-0")) {
                            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                        } else {
                            Thread.sleep(50)
                            refreshResponse()
                        }
                    }
                    request.path == "/oauth2/revoke" -> MockResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        auth = AuthManager(OkHttpClient(), store, json) { now }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tokens(access: String, refresh: String) =
        MockResponse().setBody("""{"access_token":"$access","refresh_token":"$refresh","expires_in":300}""")

    @Synchronized
    private fun rotate(): MockResponse {
        val n = issued.size + 1
        issued += "refresh-$n"
        return tokens("access-$n", "refresh-$n")
    }

    private fun signIn() = runBlocking {
        val url = auth.beginLogin(base).toHttpUrl()
        auth.completeLogin("org.openbased.android:/oauth2redirect?code=the-code&state=${url.queryParameter("state")}")
    }

    @Test
    fun loginUrlUsesPkceAndTheAndroidClient() = runBlocking {
        val url = auth.beginLogin(base).toHttpUrl()
        assertEquals("/oauth2/authorize", url.encodedPath)
        assertEquals("openbased-android", url.queryParameter("client_id"))
        assertEquals("org.openbased.android:/oauth2redirect", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(Pkce.challenge(store.pendingLogin()!!.verifier), url.queryParameter("code_challenge"))
        assertTrue("media.download" in url.queryParameter("scope")!!)
    }

    @Test
    fun completingTheLoginStoresTheSession() {
        signIn()
        val session = store.session()!!
        assertEquals("access-0", session.accessToken)
        assertEquals("refresh-0", session.refreshToken)
        assertEquals(base, session.serverUrl)
        assertNull(store.pendingLogin())
        assertTrue(auth.signedIn.value)
    }

    @Test
    fun aRedirectWithAnotherStateIsRejected() = runBlocking {
        auth.beginLogin(base)
        val e = assertThrows(SignInException::class.java) {
            runBlocking { auth.completeLogin("org.openbased.android:/oauth2redirect?code=the-code&state=forged") }
        }
        assertTrue(e.message!!.contains("did not match"))
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun cancelledSignInIsReported() = runBlocking {
        auth.beginLogin(base)
        val e = assertThrows(SignInException::class.java) {
            runBlocking { auth.completeLogin("org.openbased.android:/oauth2redirect?error=access_denied&state=x") }
        }
        assertEquals("Sign-in was cancelled.", e.message)
    }

    @Test
    fun expiredTokensAreRefreshedOnce() = runBlocking {
        signIn()
        assertEquals("access-0", auth.accessToken())
        now += 300_000
        assertEquals("access-1", auth.accessToken())
        assertEquals("refresh-1", store.session()!!.refreshToken)
        assertEquals("access-1", auth.accessToken())
        assertEquals(1, refreshes.get())
    }

    @Test
    fun concurrentRefreshesOfTheSameTokenRefreshOnlyOnce() = runBlocking {
        signIn()
        // Ten requests got 401 with the same token at the same time.
        val results = (1..10).map { async(kotlinx.coroutines.Dispatchers.IO) { auth.refresh("access-0") } }.awaitAll()
        assertEquals(List(10) { "access-1" }, results)
        assertEquals(1, refreshes.get())
        assertTrue(auth.signedIn.value)
    }

    @Test
    fun aRejectedRefreshTokenEndsTheSession() = runBlocking {
        signIn()
        refreshResponse = { MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""") }
        assertNull(auth.refresh("access-0"))
        assertNull(store.session())
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun networkFailuresKeepTheSession() {
        signIn()
        refreshResponse = { MockResponse().setResponseCode(503) }
        assertThrows(IOException::class.java) { runBlocking { auth.refresh("access-0") } }
        assertEquals("refresh-0", store.session()!!.refreshToken)
        assertTrue(auth.signedIn.value)
    }

    @Test
    fun signingOutRevokesTheRefreshToken() = runBlocking {
        signIn()
        auth.signOut()
        val revoke = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) }
            .first { it.path == "/oauth2/revoke" }
        val body = revoke.body.readUtf8()
        assertTrue(body, "token=refresh-0" in body && "client_id=openbased-android" in body)
        assertNull(store.session())
        assertFalse(auth.signedIn.value)
    }

    @Test
    fun serverAddressesAreNormalized() {
        assertEquals("http://bigbox:8080", AuthManager.normalizeServerUrl(" bigbox:8080/ "))
        assertEquals("https://media.example.com", AuthManager.normalizeServerUrl("https://media.example.com"))
        assertThrows(SignInException::class.java) { AuthManager.normalizeServerUrl("bad host name") }
    }
}
