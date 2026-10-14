package org.openbased.android.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DownloadLocationsTest {

    private val internal = DownloadLocation(File("/storage/emulated/0/Android/data/app/files"), false, "Internal", 10)
    private val card = DownloadLocation(File("/storage/1234-ABCD/Android/data/app/files"), true, "SD card", 20)

    @Test
    fun theChosenCardIsUsedWhileItIsInserted() {
        assertEquals(card, DownloadLocations.choose(listOf(internal, card), card.root.absolutePath))
    }

    @Test
    fun internalStorageIsTheDefaultAndTheFallback() {
        assertEquals(internal, DownloadLocations.choose(listOf(internal, card), null))
        // The card was removed.
        assertEquals(internal, DownloadLocations.choose(listOf(internal), card.root.absolutePath))
    }

    @Test
    fun eachDownloadHasItsOwnFolder() {
        assertEquals(
            File("/storage/1234-ABCD/Android/data/app/files/downloads/media_1"),
            DownloadLocations.directory(card, "media_1"),
        )
    }
}
