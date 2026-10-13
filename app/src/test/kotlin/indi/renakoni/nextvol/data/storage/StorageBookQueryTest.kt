package indi.renakoni.nextvol.data.storage

import android.app.Application
import androidx.room.Room
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.local.room.entity.VolumeEntity
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class StorageBookQueryTest {
    private lateinit var database: NextVolDatabase
    private lateinit var data: UserDataRepository
    private lateinit var query: StorageBookQuery

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication<Application>()
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        data = UserDataRepository(database.userDataDao())
        query = StorageBookQuery(database, StorageUsageRepository(context, database, data), LocalBookStore(context, database))
    }

    @After fun close() { database.close() }

    @Test fun cachedCandidatesKeepDirectoryEntryCountsAndFilterOnlyZeroTotalBytes() = runBlocking {
        assertNull(query.getCachedBooks())
        assertTrue(query.refreshBooks().isEmpty())
        val readAt = LocalDateTime.of(2026, 10, 13, 12, 0)
        database.bookVolumesDao().insertVolumeEntities(
            VolumeEntity("cached", "v1", "First", listOf("c1", "c1", ""), 0),
            VolumeEntity("cached", "v2", "Second", listOf("c1", "c2"), 1),
        )
        for (id in listOf("reading-only", "outside-snapshot")) {
            database.userReadingDataDao().insert(UserReadingDataEntity(id, readAt, 9, 0.5f, "c1", "Chapter", emptyMap(), emptyMap()))
        }
        val snapshot = StorageUsageSnapshot(schemaVersion = 1, books = listOf(
            BookStorageUsage("cached", 1, 2, 3, 4),
            BookStorageUsage("zero", 0, 0, 0, 0),
            BookStorageUsage("reading-only", 0, 0, 0, 0),
        ))
        data.stringUserData(UserDataPath.Settings.Data.StorageUsageSnapshot.path).set(Json.encodeToString(snapshot))
        val listed = query.getCachedBooks()!!
        assertEquals(listOf("cached", "reading-only"), listed.map { it.id })
        assertEquals(10L, listed[0].size)
        assertEquals(5, listed[0].chapterCount)
        assertEquals(2, listed[0].volumeCount)
        assertNull(listed[0].lastReadTime)
        assertEquals(readAt, listed[1].lastReadTime)
        assertEquals(database.storageStatsDao().getUserReadingBytes().single { it.id == "reading-only" }.bytes, listed[1].size)
        assertEquals(0, listed[1].chapterCount)
    }
}
