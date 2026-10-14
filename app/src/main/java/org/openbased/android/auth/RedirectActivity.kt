package org.openbased.android.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.openbased.android.MainActivity

/**
 * Receives `org.openbased.android:/oauth2redirect?code=…&state=…` from the browser and hands it to
 * [MainActivity], which completes the sign-in.
 */
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_SIGN_IN_REDIRECT)
                .setData(intent?.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
