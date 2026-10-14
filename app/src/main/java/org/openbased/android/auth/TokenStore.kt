package org.openbased.android.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The sign-in: which server, and its current tokens. */
data class Session(
    val serverUrl: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch milliseconds after which the access token should be refreshed. */
    val accessTokenExpiresAt: Long,
)

/** A sign-in started in the browser and waiting for the redirect. */
data class PendingLogin(
    val serverUrl: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val verifier: String,
    val state: String,
)

/**
 * Stores the session. Writes must be durable before the caller continues: with rotating refresh tokens, losing
 * the new refresh token (and later sending the old one) makes the server revoke the whole sign-in.
 */
interface TokenStore {
    fun session(): Session?
    fun saveSession(session: Session)
    fun clearSession()
    fun pendingLogin(): PendingLogin?
    fun savePendingLogin(pending: PendingLogin?)
}

/** Keeps the session in EncryptedSharedPreferences, written with commit() so it is on disk when this returns. */
@SuppressLint("ApplySharedPref")
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun session(): Session? {
        val server = prefs.getString("server", null) ?: return null
        val access = prefs.getString("access", null) ?: return null
        return Session(
            serverUrl = server,
            tokenEndpoint = prefs.getString("tokenEndpoint", null) ?: return null,
            revocationEndpoint = prefs.getString("revocationEndpoint", null),
            accessToken = access,
            refreshToken = prefs.getString("refresh", null),
            accessTokenExpiresAt = prefs.getLong("expiresAt", 0),
        )
    }

    override fun saveSession(session: Session) {
        check(
            prefs.edit()
                .putString("server", session.serverUrl)
                .putString("tokenEndpoint", session.tokenEndpoint)
                .putString("revocationEndpoint", session.revocationEndpoint)
                .putString("access", session.accessToken)
                .putString("refresh", session.refreshToken)
                .putLong("expiresAt", session.accessTokenExpiresAt)
                .commit(),
        ) { "Could not save the sign-in" }
    }

    override fun clearSession() {
        prefs.edit().remove("server").remove("tokenEndpoint").remove("revocationEndpoint").remove("access")
            .remove("refresh").remove("expiresAt").commit()
    }

    override fun pendingLogin(): PendingLogin? {
        val verifier = prefs.getString("pendingVerifier", null) ?: return null
        return PendingLogin(
            serverUrl = prefs.getString("pendingServer", null) ?: return null,
            tokenEndpoint = prefs.getString("pendingTokenEndpoint", null) ?: return null,
            revocationEndpoint = prefs.getString("pendingRevocationEndpoint", null),
            verifier = verifier,
            state = prefs.getString("pendingState", null) ?: return null,
        )
    }

    override fun savePendingLogin(pending: PendingLogin?) {
        val edit = prefs.edit()
        if (pending == null) {
            edit.remove("pendingServer").remove("pendingTokenEndpoint").remove("pendingRevocationEndpoint")
                .remove("pendingVerifier").remove("pendingState")
        } else {
            edit.putString("pendingServer", pending.serverUrl)
                .putString("pendingTokenEndpoint", pending.tokenEndpoint)
                .putString("pendingRevocationEndpoint", pending.revocationEndpoint)
                .putString("pendingVerifier", pending.verifier)
                .putString("pendingState", pending.state)
        }
        edit.commit()
    }
}

/** For tests. */
class InMemoryTokenStore : TokenStore {
    @Volatile private var session: Session? = null
    @Volatile private var pending: PendingLogin? = null
    var saves = 0
        private set

    override fun session() = session
    override fun saveSession(session: Session) {
        saves++
        this.session = session
    }
    override fun clearSession() {
        session = null
    }
    override fun pendingLogin() = pending
    override fun savePendingLogin(pending: PendingLogin?) {
        this.pending = pending
    }
}
