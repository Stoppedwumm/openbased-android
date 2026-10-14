package org.openbased.android.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Proof Key for Code Exchange (RFC 7636) with the S256 method. */
object Pkce {

    private val random = SecureRandom()

    /** 32 random bytes as base64url: 43 characters, within RFC 7636's 43–128. */
    fun verifier(): String = randomToken(32)

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** For the OAuth2 state parameter. */
    fun state(): String = randomToken(16)

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
