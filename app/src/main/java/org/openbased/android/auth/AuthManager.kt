package org.openbased.android.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openbased.android.api.Discovery
import org.openbased.android.api.OAuthError
import org.openbased.android.api.TokenResponse
import java.io.IOException

/** Sign-in failed for a reason the user should see. */
class SignInException(message: String) : Exception(message)

/**
 * Signs in with Authorization Code + PKCE through a Custom Tab, and keeps the access token fresh.
 *
 * The server rotates refresh tokens and treats a reused one as theft, revoking the whole sign-in. So refreshes
 * are serialized: a caller whose token was already replaced by a concurrent refresh gets the new token instead of
 * refreshing again, and the new refresh token is stored before the new access token is used.
 */
class AuthManager(
    private val http: OkHttpClient,
    private val store: TokenStore,
    private val json: Json,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()
    private val _signedIn = MutableStateFlow(store.session() != null)

    /** False once the sign-in ended: signed out, or the server rejected the refresh token. */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val serverUrl: String? get() = store.session()?.serverUrl

    /** Reads the server's OAuth2 endpoints. */
    suspend fun discover(serverUrl: String): Discovery = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(serverUrl)
        val request = Request.Builder().url("$base/.well-known/openid-configuration").build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SignInException("$base does not look like an OpenBased server (HTTP ${response.code}).")
                }
                json.decodeFromString<Discovery>(response.body!!.string())
            }
        } catch (e: IOException) {
            throw SignInException("Cannot reach $base: ${e.message}")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw SignInException("$base does not look like an OpenBased server.")
        }
    }

    /** Starts a sign-in and returns the URL to open in a Custom Tab. */
    suspend fun beginLogin(serverUrl: String): String {
        val discovery = discover(normalizeServerUrl(serverUrl))
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        // The API is called at the server's own address (its issuer), not the one typed: when the typed address
        // redirects (http to https, another host name), HTTP clients drop the token on the redirect.
        store.savePendingLogin(
            PendingLogin(apiBase(discovery), discovery.tokenEndpoint, discovery.revocationEndpoint, verifier, state),
        )
        return discovery.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    /** Completes a sign-in from the redirect URI the browser opened. */
    suspend fun completeLogin(redirect: String) = withContext(Dispatchers.IO) {
        val url = redirect.replaceFirst("$REDIRECT_SCHEME:", "https://redirect").toHttpUrlOrNull()
            ?: throw SignInException("The sign-in returned an invalid address.")
        val pending = store.pendingLogin() ?: throw SignInException("No sign-in was in progress. Try again.")
        url.queryParameter("error")?.let {
            store.savePendingLogin(null)
            throw SignInException(
                if (it == "access_denied") "Sign-in was cancelled." else "Sign-in failed: $it",
            )
        }
        if (url.queryParameter("state") != pending.state) {
            throw SignInException("The sign-in response did not match the request. Try again.")
        }
        val code = url.queryParameter("code") ?: throw SignInException("The sign-in returned no code.")
        val tokens = tokenRequest(
            pending.tokenEndpoint,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", pending.verifier)
                .build(),
        ) ?: throw SignInException("The server rejected the sign-in. Try again.")
        store.saveSession(
            Session(
                serverUrl = pending.serverUrl,
                tokenEndpoint = pending.tokenEndpoint,
                revocationEndpoint = pending.revocationEndpoint,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenExpiresAt = expiry(tokens),
            ),
        )
        store.savePendingLogin(null)
        _signedIn.value = true
    }

    /**
     * Moves a sign-in made with the typed address (as earlier versions did) to the server's own address, when
     * they differ. Best effort: does nothing when the server cannot be reached.
     */
    suspend fun updateServerAddress() {
        val session = store.session() ?: return
        val discovery = try {
            discover(session.serverUrl)
        } catch (e: SignInException) {
            return
        }
        val base = apiBase(discovery)
        refreshLock.withLock {
            val current = store.session() ?: return
            if (current.serverUrl != base) {
                store.saveSession(current.copy(serverUrl = base))
            }
        }
    }

    /** A current access token, refreshed first if it is about to expire; null when signed out. */
    suspend fun accessToken(): String? {
        val session = store.session() ?: return null
        if (clock() < session.accessTokenExpiresAt) {
            return session.accessToken
        }
        return refresh(session.accessToken)
    }

    /**
     * Replaces [failedToken], which the server rejected or which expired. If another caller already replaced it,
     * returns that token without refreshing again. Returns null when the sign-in has ended. Throws
     * [IOException] when the server cannot be reached; the session is kept for later.
     */
    suspend fun refresh(failedToken: String): String? = refreshLock.withLock {
        val session = store.session() ?: return null
        if (session.accessToken != failedToken && clock() < session.accessTokenExpiresAt) {
            return session.accessToken
        }
        val refreshToken = session.refreshToken ?: return endSession()
        val tokens = withContext(Dispatchers.IO) {
            tokenRequest(
                session.tokenEndpoint,
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", CLIENT_ID)
                    .build(),
            )
        } ?: return endSession()
        // Store the rotated refresh token before anything uses the new access token.
        store.saveSession(
            session.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: refreshToken,
                accessTokenExpiresAt = expiry(tokens),
            ),
        )
        tokens.accessToken
    }

    /** Revokes the sign-in on the server (best effort) and forgets it. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val session = store.session()
            val endpoint = session?.revocationEndpoint
            val token = session?.refreshToken ?: session?.accessToken
            if (endpoint != null && token != null) {
                try {
                    http.newCall(
                        Request.Builder().url(endpoint).post(
                            FormBody.Builder()
                                .add("token", token)
                                .add("token_type_hint", if (session.refreshToken != null) "refresh_token" else "access_token")
                                .add("client_id", CLIENT_ID)
                                .build(),
                        ).build(),
                    ).execute().close()
                } catch (_: IOException) {
                    // Offline: the refresh token expires on the server on its own.
                }
            }
            endSession()
        }
    }

    private fun endSession(): String? {
        store.clearSession()
        _signedIn.value = false
        return null
    }

    /**
     * POSTs to the token endpoint. Returns null when the server rejects the grant (the sign-in is over); throws
     * [IOException] for network failures and unexpected server errors, which may succeed later.
     */
    private fun tokenRequest(endpoint: String, body: FormBody): TokenResponse? {
        http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                return json.decodeFromString<TokenResponse>(text)
            }
            if (response.code == 400 || response.code == 401) {
                val error = runCatching { json.decodeFromString<OAuthError>(text).error }.getOrDefault("")
                if (error in REJECTED) {
                    return null
                }
            }
            throw IOException("Token endpoint returned HTTP ${response.code}")
        }
    }

    private fun expiry(tokens: TokenResponse): Long = clock() + (tokens.expiresIn - EXPIRY_MARGIN_SECONDS) * 1000

    companion object {
        const val CLIENT_ID = "openbased-android"
        const val REDIRECT_SCHEME = "org.openbased.android"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauth2redirect"
        const val SCOPES =
            "openid profile media.read media.stream media.download library.read history.read history.write"
        private const val EXPIRY_MARGIN_SECONDS = 30
        private val REJECTED = setOf("invalid_grant", "invalid_client", "unauthorized_client")

        /** The server's own address, from its discovery document. */
        fun apiBase(discovery: Discovery): String = normalizeServerUrl(discovery.issuer)

        /** `bigbox:8080` → `http://bigbox:8080`; no trailing slash. Plain HTTP when no scheme is given. */
        fun normalizeServerUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: throw SignInException("\"$input\" is not a valid address.")
            return url.toString().trimEnd('/')
        }
    }
}
