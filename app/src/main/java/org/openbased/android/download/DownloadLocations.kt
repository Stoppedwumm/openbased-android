package org.openbased.android.download

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A place downloads can go: the app's own folder on internal storage or on an SD (TF) card. */
data class DownloadLocation(
    /** The app's folder on that storage; downloads go into `downloads/` below it. */
    val root: File,
    val removable: Boolean,
    val label: String,
    val freeBytes: Long,
)

object DownloadLocations {

    /**
     * Internal storage first, then each SD card. The folders are private to the app and need no storage
     * permission; Android deletes them when the app is uninstalled.
     */
    fun all(context: Context): List<DownloadLocation> {
        val storage = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .mapIndexed { index, dir ->
                val removable = runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(index > 0)
                val name = runCatching { storage.getStorageVolume(dir)?.getDescription(context) }.getOrNull()
                DownloadLocation(dir, removable, name ?: if (removable) "SD card" else "Internal storage", dir.usableSpace)
            }
            .ifEmpty { listOf(DownloadLocation(context.filesDir, false, "Internal storage", context.filesDir.usableSpace)) }
    }

    /**
     * The location for new downloads: the one chosen in settings while it is available (an SD card can be
     * removed), otherwise internal storage.
     */
    fun choose(locations: List<DownloadLocation>, chosenRoot: String?): DownloadLocation =
        locations.firstOrNull { it.root.absolutePath == chosenRoot }
            ?: locations.firstOrNull { !it.removable }
            ?: locations.first()

    /** The folder for one download in [location]. */
    fun directory(location: DownloadLocation, mediaId: String): File = File(File(location.root, "downloads"), mediaId)
}
