package indi.renakoni.nextvol.data.book

import android.app.Application
import androidx.room.Room
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.entity.BookshelfBookMetadataEntity
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
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
class BookAliasStoreTest {
    private lateinit var database: NextVolDatabase
    private lateinit var aliases: BookAliasStore
    private val source = Identifier("rules", "fixture")
    private val first = SourceBookId(source, "https://example.test/novel/1")
    private val second = SourceBookId(source, "https://example.test/novel/2")
    private val series = SourceBookId(source, "https://example.test/series/10")
    private val now = LocalDateTime.of(2026, 9, 24, 0, 0)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java)
            .allowMainThreadQueries().build()
        aliases = BookAliasStore(database)
    }

    @After fun tearDown() { database.close() }

    private fun info(book: SourceBookId) = book.bind(BookInformation(book.remoteId, "Fixture",
        author = "Author", description = "", publishingHouse = "", wordCount = WordCount(1),
        lastUpdated = now, isComplete = false))

    private fun volumes(book: SourceBookId, novels: List<SourceBookId>) = book.bind(BookVolumes(book.remoteId,
        listOf(Volume("default", "", novels.map { ChapterInformation(it.remoteId, it.remoteId.substringAfterLast('/')) }))))

    private suspend fun save(book: SourceBookId, progress: Float, time: LocalDateTime) {
        database.bookInformationDao().insert(info(book))
        database.bookVolumesDao().insertVolume(book.storageKey, volumes(book, listOf(book)))
        val chapter = SourceChapterId(book, book.remoteId)
        database.chapterContentDao().cache(chapter.bind(ChapterContent(book.remoteId, "Saved chapter", JsonObject(emptyMap()))))
        database.userReadingDataDao().insert(UserReadingDataEntity(book.storageKey, time, 30, progress,
            chapter.storageKey, "Saved chapter", mapOf(chapter.storageKey to progress), mapOf(chapter.storageKey to progress)))
    }

    @Test fun shelfEntriesProgressAndCachedChaptersMergeByNovelRatherThanPosition() = runBlocking {
        save(first, 0.3f, now); save(second, 0.8f, now.plusHours(1))
        database.bookInformationDao().insert(info(series))
        database.bookshelfDao().insertBookshelf(BookshelfEntity(1, "Fixture", "", false, false, false,
            listOf(first.storageKey, second.storageKey, series.storageKey), listOf(second.storageKey), listOf(first.storageKey)))
        for (book in listOf(first, second, series)) database.bookshelfDao().insertBookshelfBookMetadata(
            BookshelfBookMetadataEntity(book.storageKey, now, listOf(1)))
        val catalog = volumes(series, listOf(first, second))
        listOf(first, second, first, second).map { book -> async { aliases.merge(book, series, info(series), catalog) } }.awaitAll()

        assertEquals(listOf(series.storageKey), database.bookInformationDao().getAllEntities().map { it.id })
        assertEquals(listOf(series.storageKey), database.bookshelfDao().getBookshelf(1)!!.allBookIds)
        assertEquals(listOf(series.storageKey), database.bookshelfDao().getBookshelf(1)!!.pinnedBookIds)
        assertEquals(series, aliases.resolve(first)); assertEquals(series, aliases.resolve(second))
        val progress = database.userReadingDataDao().getEntity(series.storageKey)!!
        val firstChapter = SourceChapterId(series, first.remoteId).storageKey
        val secondChapter = SourceChapterId(series, second.remoteId).storageKey
        assertEquals(secondChapter, progress.lastReadChapterId)
        assertEquals(0.8f, progress.currentChapterReadingProgressMap[secondChapter])
        assertEquals(0.3f, progress.currentChapterReadingProgressMap[firstChapter])
        assertEquals(60, progress.totalReadTime)
        assertEquals(0.55f, progress.readingProgress, 0.0001f)
        assertEquals(secondChapter, database.chapterContentDao().get(firstChapter)!!.nextChapter)
        assertEquals(firstChapter, database.chapterContentDao().get(secondChapter)!!.prevChapter)
        assertNull(database.userReadingDataDao().getEntity(first.storageKey))
    }

    @Test fun unlistedSinglesAndDirectSeriesHaveOneRecordWithoutCreatingAShelfEntry() = runBlocking {
        aliases.merge(first, series, info(series), volumes(series, listOf(first, second)))
        aliases.merge(second, series, info(series), volumes(series, listOf(first, second)))
        assertEquals(listOf(series.storageKey), database.bookInformationDao().getAllEntities().map { it.id })
        assertTrue(database.bookshelfDao().getAllBookshelfBookMetadataEntities().isEmpty())
        assertEquals(series, aliases.resolve(series))
    }

    @Test fun anotherCanonicalUrlChangeKeepsEarlierAliasesDirectAndPreservesProgress() = runBlocking {
        save(first, 0.7f, now)
        aliases.merge(first, series, info(series), volumes(series, listOf(first, second)))
        aliases.merge(second, series, info(series), volumes(series, listOf(first, second)))
        val renamed = SourceBookId(source, "https://example.test/series/renamed")
        repeat(2) { aliases.merge(series, renamed, info(renamed), volumes(renamed, listOf(first, second))) }
        for (book in listOf(first, second, series, renamed)) assertEquals(renamed, aliases.resolve(book))
        val savedAliases = database.bookAliasDao().all()
        assertTrue(savedAliases.none { row -> savedAliases.any { it.id == row.canonicalId } })
        assertEquals(listOf(renamed.storageKey), database.bookInformationDao().getAllEntities().map { it.id })
        val reading = database.userReadingDataDao().getEntity(renamed.storageKey)!!
        assertEquals(30, reading.totalReadTime)
        assertEquals(SourceChapterId(renamed, first.remoteId).storageKey, reading.lastReadChapterId)
        assertNotNull(database.chapterContentDao().get(reading.lastReadChapterId))
        try {
            aliases.merge(renamed, first, info(first), volumes(first, listOf(first, second)))
            fail("A canonical identity must not become its own alias")
        } catch (_: IllegalStateException) { }
        assertEquals(renamed, aliases.resolve(first))
    }

    @Test fun deletedSavedChapterLeavesTheOriginalBookAndProgressUntouched() = runBlocking {
        save(first, 0.7f, now)
        try {
            aliases.merge(first, series, info(series), volumes(series, listOf(second)))
            fail("An absent chapter cannot be replaced by the first series chapter")
        } catch (_: IllegalStateException) { }
        assertEquals(first, aliases.resolve(first))
        assertNotNull(database.bookInformationDao().get(first.storageKey))
        assertNull(database.bookInformationDao().get(series.storageKey))
        assertEquals(0.7f, database.userReadingDataDao().getEntity(first.storageKey)!!.readingProgress, 0f)
    }

    @Test fun missingSeriesResumeChapterDoesNotDiscardEitherReadingRecord() = runBlocking {
        save(first, 0.3f, now)
        save(series, 0.7f, now.plusDays(1))
        try {
            aliases.merge(first, series, info(series), volumes(series, listOf(first, second)))
            fail("Both saved positions must remain valid")
        } catch (_: IllegalStateException) { }
        assertEquals(first, aliases.resolve(first))
        assertEquals(2, database.userReadingDataDao().getAll().size)
        assertEquals(SourceChapterId(series, series.remoteId).storageKey,
            database.userReadingDataDao().getEntity(series.storageKey)!!.lastReadChapterId)
    }

    @Test fun equalUrlsInAnotherSourceRemainIsolatedAndCrossSourceMigrationIsRejected() = runBlocking {
        val other = SourceBookId(Identifier("rules", "other"), first.remoteId)
        save(other, 0.4f, now)
        aliases.merge(first, series, info(series), volumes(series, listOf(first, second)))
        assertEquals(other, aliases.resolve(other))
        try {
            aliases.merge(other, series, info(series), volumes(series, listOf(first, second)))
            fail("Source IDs are host-owned")
        } catch (_: IllegalArgumentException) { }
        assertNotNull(database.bookInformationDao().get(other.storageKey))
        assertEquals(0.4f, database.userReadingDataDao().getEntity(other.storageKey)!!.readingProgress, 0f)
    }

    @Test fun resetsBeforeMigrationStillRejectQueuedSavesFromTheSeriesRoute() = runBlocking {
        save(first, 0.7f, now)
        val local = LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(),
            database.chapterContentDao(), database.userReadingDataDao(), aliases)
        val reading = BookReadingDataRepository(local)
        val revision = reading.progressRevision()
        val chapter = SourceChapterId(first, first.remoteId).storageKey
        reading.markChaptersUnread(first.storageKey, setOf(chapter), setOf(chapter))
        aliases.merge(first, series, info(series), volumes(series, listOf(first, second)))
        assertFalse(reading.updateChapterProgress(series.storageKey, SourceChapterId(series, first.remoteId).storageKey, revision) { it })
        assertTrue(local.getUserReadingData(series.storageKey).maxChapterReadingProgressMap.isEmpty())
    }

    @Test fun existingRoutesProjectStableChapterIdsAndWriteOnlyTheCanonicalRows() = runBlocking {
        save(first, 0.3f, now); save(second, 0.8f, now.plusHours(1))
        val catalog = volumes(series, listOf(first, second))
        aliases.merge(first, series, info(series), catalog)
        aliases.merge(second, series, info(series), catalog)
        val local = LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(),
            database.chapterContentDao(), database.userReadingDataDao(), aliases)
        val routeChapter = SourceChapterId(first, second.remoteId).storageKey
        val canonicalChapter = SourceChapterId(series, second.remoteId).storageKey
        assertEquals(first.storageKey, local.getBookInformation(first.storageKey)!!.id)
        assertEquals(listOf(first.remoteId, second.remoteId), local.getBookVolumes(first.storageKey)!!
            .volumes.single().chapters.map { BookIdentity.chapter(it.id, first).remoteId })
        assertEquals(routeChapter, local.getUserReadingData(first.storageKey).lastReadChapterId)
        assertEquals(routeChapter, local.getUserReadingDataFlow(first.storageKey).first().lastReadChapterId)
        assertEquals(routeChapter, local.getChapterContent(routeChapter)!!.id)
        assertTrue(local.isChapterContentExists(routeChapter))

        local.updateUserReadingData(first.storageKey) { data ->
            assertEquals(first.storageKey, data.id)
            data.copyWithUpdatedChapterReadingProgress(routeChapter, 0.9f).copy(lastReadChapterId = routeChapter)
        }
        local.updateBookInformation(info(first))
        local.updateBookVolumes(catalog.rebind(series, first))
        local.updateChapterContent(local.getChapterContent(routeChapter)!!)
        assertEquals(listOf(series.storageKey), database.bookInformationDao().getAllEntities().map { it.id })
        assertEquals(listOf(series.storageKey), database.userReadingDataDao().getAll().map { it.id })
        assertEquals(0.9f, database.userReadingDataDao().getEntity(series.storageKey)!!
            .currentChapterReadingProgressMap[canonicalChapter])
        assertNull(database.chapterContentDao().get(routeChapter))
    }
}
