package org.openbased.android

import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.openbased.android.api.AuthInterceptor
import org.openbased.android.api.OpenBasedApi
import org.openbased.android.auth.AuthManager
import org.openbased.android.auth.EncryptedTokenStore
import org.openbased.android.data.AppDatabase
import org.openbased.android.data.CatalogRepository
import org.openbased.android.data.CatalogSyncWorker
import org.openbased.android.data.ProgressRepository
import org.openbased.android.data.ProgressSyncWorker
import org.openbased.android.download.DownloadRepository
import org.openbased.android.download.Settings
import java.util.concurrent.TimeUnit

/** Creates and holds the app's services. */
class AppContainer(private val context: Context) {

    /** For work that must outlive a screen, such as saving progress when the player closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Without the token, for discovery and the token endpoint. */
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val auth = AuthManager(baseHttp, EncryptedTokenStore(context), json)

    /** Adds the access token for the signed-in server; used for the API, images, streams and downloads. */
    val http: OkHttpClient = baseHttp.newBuilder().addInterceptor(AuthInterceptor(auth)).build()

    val api = OpenBasedApi(http, json) { auth.serverUrl }
    val db = AppDatabase.create(context)
    val settings = Settings(context)
    val catalog = CatalogRepository(db, api)
    val progress = ProgressRepository(db, api, onQueued = { ProgressSyncWorker.enqueue(context) })
    val downloads = DownloadRepository(context, db, api, json, settings)

    val imageLoader: ImageLoader = ImageLoader.Builder(context).okHttpClient(http).crossfade(true).build()

    fun onSignedIn() {
        CatalogSyncWorker.syncNow(context)
        CatalogSyncWorker.schedule(context)
    }

    /** Signs out and removes everything that belonged to the sign-in. */
    suspend fun signOut() {
        auth.signOut()
        downloads.clear()
        progress.clear()
        catalog.clear()
    }
}
