package indi.renakoni.nextvol.data.local

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceChapterId
import android.app.Application
import androidx.room.Room
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Exercises generated Room DAO methods against SQLite, including concurrent repository callers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class UserReadingDataTransactionTest {
    private lateinit var database: NextVolDatabase
    private lateinit var source: LocalBookDataSource

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), NextVolDatabase::class.java,
        ).build()
        source = LocalBookDataSource(
            database.bookInformationDao(), database.bookVolumesDao(),
            database.chapterContentDao(), database.userReadingDataDao(),
            indi.renakoni.nextvol.data.book.BookAliasStore(database),
        )
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun concurrentProgressAndTimeUpdatesKeepAllChanges() = runBlocking {
        source.updateUserReadingData("book") { it.copy(totalReadTime = 60) }
        coroutineScope {
            (0 until 20).flatMap { index ->
                listOf(
                    launch(Dispatchers.IO) {
                        source.updateUserReadingData("book") {
                            it.copyWithUpdatedChapterReadingProgress("chapter-$index", 0.8f)
                                .copy(lastReadChapterId = "chapter-$index", lastReadChapterTitle = "Chapter $index")
                        }
                    },
                    launch(Dispatchers.IO) {
                        source.updateUserReadingData("book") { it.copy(totalReadTime = it.totalReadTime + 30) }
                    },
                )
            }.joinAll()
        }
        val saved = source.getUserReadingData("book")
        assertEquals(660, saved.totalReadTime)
        val expected = (0 until 20).associate { BookIdentity.chapter("chapter-$it", BookIdentity.book("book")).storageKey to 0.8f }
        assertEquals(expected, saved.currentChapterReadingProgressMap)
        assertEquals(expected, saved.maxChapterReadingProgressMap)
        assertEquals("Chapter ${SourceChapterId.fromStorageKey(saved.lastReadChapterId!!).remoteId.substringAfter('-')}", saved.lastReadChapterTitle)
    }

    @Test
    fun failedOrCancelledTransformationsLeaveDataAndTransactionQueueUsable() = runBlocking {
        source.updateUserReadingData("book") { it.copy(totalReadTime = 10) }
        for (failure in listOf(IllegalStateException("failed update"), CancellationException("cancelled update"))) {
            try {
                source.updateUserReadingData("book") { throw failure }
                throw AssertionError("Expected transformation failure")
            } catch (actual: Exception) {
                assertEquals(failure::class, actual::class)
                assertEquals(failure.message, actual.message)
            }
            assertEquals(10, source.getUserReadingData("book").totalReadTime)
        }
        source.updateUserReadingData("book") { it.copy(totalReadTime = it.totalReadTime + 20) }
        assertEquals(30, source.getUserReadingData("book").totalReadTime)
    }

    @Test
    fun absentRecordsAndSeparateBooksKeepTheirOwnMetadata() = runBlocking {
        val readAt = LocalDateTime.of(2026, 9, 8, 10, 0)
        withContext(Dispatchers.IO) {
            listOf("first", "second").mapIndexed { index, id ->
                launch {
                    source.updateUserReadingData(id) {
                        assertEquals(BookIdentity.bookKey(id), it.id)
                        assertTrue(it.currentChapterReadingProgressMap.isEmpty())
                        it.copyWithUpdatedChapterReadingProgress("$id-chapter", 0.5f)
                            .copy(totalReadTime = index + 1, lastReadTime = readAt, lastReadChapterTitle = id)
                    }
                }
            }.joinAll()
        }
        for ((index, id) in listOf("first", "second").withIndex()) {
            val saved = source.getUserReadingData(id)
            assertEquals(index + 1, saved.totalReadTime)
            assertEquals(mapOf(BookIdentity.chapter("$id-chapter", BookIdentity.book(id)).storageKey to 0.5f), saved.currentChapterReadingProgressMap)
            assertEquals(readAt, saved.lastReadTime)
            assertEquals(id, saved.lastReadChapterTitle)
        }
    }

}
