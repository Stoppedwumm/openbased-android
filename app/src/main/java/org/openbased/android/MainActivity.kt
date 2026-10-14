package org.openbased.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.openbased.android.auth.SignInException
import org.openbased.android.data.CatalogSyncWorker
import org.openbased.android.data.ProgressSyncWorker
import org.openbased.android.ui.AppNavigation
import org.openbased.android.ui.OpenBasedTheme
import org.openbased.android.ui.SignInScreen

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as OpenBasedApp).container }
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var lastServer by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        lastServer = getPreferences(MODE_PRIVATE).getString("server", null)
        setContent {
            OpenBasedTheme {
                val signedIn by container.auth.signedIn.collectAsStateWithLifecycle()
                if (signedIn) {
                    AppNavigation(onSignOut = ::signOut, onNeedNotifications = ::askForNotifications)
                } else {
                    SignInScreen(busy, error, lastServer, ::signIn)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (container.auth.signedIn.value) {
            CatalogSyncWorker.syncNow(this)
            ProgressSyncWorker.enqueue(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun signIn(server: String) {
        busy = true
        error = null
        lifecycleScope.launch {
            try {
                val url = container.auth.beginLogin(server)
                lastServer = server
                getPreferences(MODE_PRIVATE).edit().putString("server", server).apply()
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this@MainActivity, Uri.parse(url))
            } catch (e: SignInException) {
                error = e.message
            } catch (e: Exception) {
                error = "Sign-in failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun handleRedirect(intent: Intent?) {
        if (intent?.action != ACTION_SIGN_IN_REDIRECT) return
        val data = intent.data?.toString() ?: return
        intent.action = null
        busy = true
        lifecycleScope.launch {
            try {
                container.auth.completeLogin(data)
                error = null
                container.onSignedIn()
            } catch (e: SignInException) {
                error = e.message
            } catch (e: Exception) {
                error = "Sign-in failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun signOut() {
        lifecycleScope.launch { container.signOut() }
    }

    /** Download progress is shown as a notification, which needs permission on Android 13+. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        const val ACTION_SIGN_IN_REDIRECT = "org.openbased.android.SIGN_IN_REDIRECT"
    }
}
