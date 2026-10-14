package org.openbased.android.api

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.openbased.android.auth.AuthManager

/**
 * Adds the access token to requests for the signed-in server, and on a 401 refreshes it once and retries. Requests
 * to other hosts are left alone, so the token never leaves the server it belongs to.
 */
class AuthInterceptor(private val auth: AuthManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = auth.serverUrl?.toHttpUrlOrNull()
        if (server == null || !sameOrigin(request.url, server) || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val token = runBlocking { auth.accessToken() } ?: return chain.proceed(request)
        val response = chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        if (response.code != 401) {
            return response
        }
        val fresh = runBlocking { auth.refresh(token) } ?: return response
        response.close()
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $fresh").build())
    }

    companion object {
        /** Scheme, host and port must all match; a prefix check would also match `media.example.com.evil.com`. */
        fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
