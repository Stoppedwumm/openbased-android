package org.openbased.android

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class OpenBasedApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Coil loads artwork through the authenticated client. */
    override fun newImageLoader(): ImageLoader = container.imageLoader
}
