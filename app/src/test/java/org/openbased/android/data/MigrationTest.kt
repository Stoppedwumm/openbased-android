package org.openbased.android.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Downloads made before storage could be chosen survive the update. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1DownloadsAreKept() = runBlocking {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO downloads (mediaId, title, quality, state, downloadedBytes, filePath, createdAt) " +
                    "VALUES ('m1', 'Heat', 'ORIGINAL', 'COMPLETE', 100, '/files/downloads/m1/media.mkv', 1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).close()

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB)
            .allowMainThreadQueries().build()
        val download = room.downloads().get("m1")!!
        assertEquals("/files/downloads/m1/media.mkv", download.filePath)
        assertEquals(DownloadState.COMPLETE, download.state)
        assertNull(download.directory)
        room.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
