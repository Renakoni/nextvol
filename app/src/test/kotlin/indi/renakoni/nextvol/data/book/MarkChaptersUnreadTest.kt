package indi.renakoni.nextvol.data.book

import android.app.Application
import androidx.room.Room
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class MarkChaptersUnreadTest {
    private lateinit var database: NextVolDatabase
    private lateinit var repository: BookReadingDataRepository
    private val book = BookIdentity.book("book")
    private val ids = (0..3).map { BookIdentity.chapter("chapter-$it", book).storageKey }
    private val catalog = ids.toSet()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java).build()
        repository = BookReadingDataRepository(LocalBookDataSource(
            database.bookInformationDao(), database.bookVolumesDao(), database.chapterContentDao(), database.userReadingDataDao(),
            BookAliasStore(database),
        ))
        runBlocking {
            repository.updateUserReadingData(book.storageKey) {
                UserReadingData(book.storageKey, LocalDateTime.of(2026, 9, 19, 12, 0), 1234, .825f,
                    ids[2], "Current chapter", ids.associateWith { .4f },
                    mapOf(ids[0] to 1f, ids[1] to 1f, ids[2] to .6f, ids[3] to .7f))
            }
        }
    }

    @After fun tearDown() { database.close() }

    @Test fun oneOrSeveralHistoricalChaptersKeepTheExactResumePositionAndMetadata() = runBlocking {
        for (selection in listOf(setOf(ids[0]), setOf(ids[1], ids[3]))) {
            val before = repository.getUserReadingData(book.storageKey)
            repository.markChaptersUnread(book.storageKey, selection, catalog)
            val after = repository.getUserReadingData(book.storageKey)
            assertEquals(before.copy(
                currentChapterReadingProgressMap = before.currentChapterReadingProgressMap - selection,
                maxChapterReadingProgressMap = before.maxChapterReadingProgressMap - selection,
                readingProgress = after.readingProgress,
            ), after)
            assertEquals(.4f, after.currentChapterReadingProgressMap[ids[2]])
        }
        assertEquals(.15f, repository.getUserReadingData(book.storageKey).readingProgress, .00001f)
    }

    @Test fun selectingTheCurrentChapterOrAllChaptersKeepsThatChapterAndRestartsItAtZero() = runBlocking {
        repository.markChaptersUnread(book.storageKey, setOf(ids[2]), catalog)
        val afterCurrent = repository.getUserReadingData(book.storageKey)
        assertEquals(ids[2], afterCurrent.lastReadChapterId)
        assertEquals("Current chapter", afterCurrent.lastReadChapterTitle)
        assertEquals(0f, afterCurrent.currentChapterReadingProgressMap[ids[2]] ?: 0f)
        assertEquals(.675f, afterCurrent.readingProgress, .00001f)
        repository.markChaptersUnread(book.storageKey, catalog, catalog)
        val afterAll = repository.getUserReadingData(book.storageKey)
        assertEquals(ids[2], afterAll.lastReadChapterId)
        assertEquals("Current chapter", afterAll.lastReadChapterTitle)
        assertEquals(1234, afterAll.totalReadTime)
        assertTrue(afterAll.currentChapterReadingProgressMap.isEmpty())
        assertTrue(afterAll.maxChapterReadingProgressMap.isEmpty())
        assertEquals(0f, afterAll.readingProgress)
    }

    @Test fun totalsIgnoreRemovedCatalogEntriesAndClampExistingProgress() = runBlocking {
        val orphan = BookIdentity.chapter("removed", book).storageKey
        repository.updateUserReadingData(book.storageKey) {
            it.copy(maxChapterReadingProgressMap = mapOf(ids[0] to 1f, ids[1] to 2f, ids[2] to -.2f, orphan to 1f))
        }
        repository.markChaptersUnread(book.storageKey, setOf(ids[0]), catalog)
        assertEquals(.25f, repository.getUserReadingData(book.storageKey).readingProgress)
    }

    @Test fun emptyOrUnknownSelectionDoesNotWriteOrInvalidateTheReader() = runBlocking {
        val before = repository.getUserReadingData(book.storageKey)
        val revision = repository.progressRevision()
        repository.markChaptersUnread(book.storageKey, emptySet(), catalog)
        repository.markChaptersUnread(book.storageKey, setOf("unknown"), catalog)
        assertEquals(before, repository.getUserReadingData(book.storageKey))
        assertEquals(revision, repository.progressRevision())
    }

    @Test fun delayedOldSavesCannotRestoreClearedChaptersButUnselectedSavesAndNewReadersStillWork() = runBlocking {
        val revision = repository.progressRevision()
        val gate = CompletableDeferred<Unit>()
        val oldWrite = async(Dispatchers.IO) {
            gate.await()
            repository.updateChapterProgress(book.storageKey, ids[0], revision) {
                it.copyWithUpdatedChapterReadingProgress(ids[0], 1f)
            }
        }
        repository.markChaptersUnread(book.storageKey, setOf(ids[0]), catalog)
        gate.complete(Unit)
        assertFalse(oldWrite.await())
        assertFalse(repository.getUserReadingData(book.storageKey).maxChapterReadingProgressMap.containsKey(ids[0]))
        assertTrue(repository.updateChapterProgress(book.storageKey, ids[2], revision) {
            it.copyWithUpdatedChapterReadingProgress(ids[2], .8f)
        })
        assertEquals(.8f, repository.getUserReadingData(book.storageKey).currentChapterReadingProgressMap[ids[2]])
        assertTrue(repository.updateChapterProgress(book.storageKey, ids[0], repository.progressRevision()) {
            it.copyWithUpdatedChapterReadingProgress(ids[0], .1f)
        })
        assertEquals(.1f, repository.getUserReadingData(book.storageKey).maxChapterReadingProgressMap[ids[0]])
    }

    @Test fun failedResetLeavesRevisionAndOtherSourcesUntouched() = runBlocking {
        val other = book.copy(sourceId = io.nightfish.lightnovelreader.api.identifier.Identifier("other", "source"))
        val otherChapter = SourceChapterId(other, "chapter-0").storageKey
        repository.updateUserReadingData(other.storageKey) { it.copyWithUpdatedChapterReadingProgress(otherChapter, .9f) }
        val before = repository.getUserReadingData(book.storageKey)
        try {
            repository.markChaptersUnread(book.storageKey, setOf(otherChapter), catalog)
            fail("A chapter from another source must not be accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.getUserReadingData(book.storageKey))
        assertEquals(0L, repository.progressRevision())
        repository.markChaptersUnread(book.storageKey, catalog, catalog)
        assertTrue(repository.updateChapterProgress(other.storageKey, otherChapter, 0L) {
            it.copyWithUpdatedChapterReadingProgress(otherChapter, 1f)
        })
        assertEquals(1f, repository.getUserReadingData(other.storageKey).maxChapterReadingProgressMap[otherChapter])
    }

    @Test fun cancellationAfterPersistenceStillInvalidatesQueuedReaderWrites() = runBlocking {
        val source = mockk<LocalBookDataSource>()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinated = BookReadingDataRepository(source)
        coEvery { source.updateUserReadingData(any(), any()) } coAnswers {
            committed.complete(Unit)
            release.await()
        }
        val reset = launch { coordinated.markChaptersUnread(book.storageKey, setOf(ids[0]), catalog) }
        committed.await()
        reset.cancel()
        release.complete(Unit)
        reset.join()
        assertEquals(1L, coordinated.progressRevision())
        assertFalse(coordinated.updateChapterProgress(book.storageKey, ids[0], 0L) { it })
    }
}
