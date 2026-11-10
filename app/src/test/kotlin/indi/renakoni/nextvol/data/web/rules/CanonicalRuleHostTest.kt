package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.LocalDateTime

/** Synthetic HTTP, but the production rule worker, adapter, runtime and host persistence path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class CanonicalRuleHostTest {
    private class Library(val fixture: RuleSourceFixture, redirectSeries: Boolean = false) : AutoCloseable {
        private val root = Files.createTempDirectory("canonical-host").toFile()
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = root
        }
        val id = Identifier("rules", "canonical")
        fun book(path: String) = SourceBookId(id, fixture.server.url(path).toString())
        val first = book("/novel/1")
        val second = book("/novel/2")
        val series = book("/series/10")
        val movedSeries = book("/series/20")
        var status = 200
        var includeFirst = true
        val registry = WebSourceRegistry(fixture.authority)
        private fun open() = Room.databaseBuilder(context, NextVolDatabase::class.java, root.resolve("library.db").path)
            .allowMainThreadQueries().build()
        var db = open()
        val aliases = BookAliasStore(db)
        val local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), aliases)
        val downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
        val work = mockk<androidx.work.WorkManager>()
        val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, work, aliases)
        val shelves = BookshelfRepository(db.bookshelfDao(), scheduler, registry, aliases)
        val reading = BookReadingDataRepository(local)
        private val text = TextProcessingRepository(mockk(relaxed = true), mockk(relaxed = true), ContentComponentRegistry())
        val books = BookRepository(local, shelves, text, work, ChapterRepository(registry, local, text, mockk(), downloads),
            reading, registry, downloads, mockk(), scheduler)

        init {
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/series/10" && status != 200) return MockResponse().setResponseCode(status)
                    val body = when (request.path) {
                        "/novel/1" -> "<h1>First</h1><article>first body</article>"
                        "/novel/2" -> "<h1>Second</h1><article>second body</article>"
                        "/series/10" -> "<h1>Series</h1>" +
                            (if (includeFirst) "<li><a href='/novel/1'>First</a></li>" else "") +
                            "<li><a href='/novel/2'>Second</a></li>"
                        "/series/20" -> "<h1>Moved series</h1><li><a href='/novel/1'>First</a></li>" +
                            "<li><a href='/novel/2'>Second</a></li>"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(body)
                }
            }
            val source = fixture.source { raw -> JsonObject(raw + mapOf(
                "ruleBookInfo" to buildJsonObject {
                    put("init", if (redirectSeries)
                        "@js:book.bookUrl = book.bookUrl == '${first.remoteId}' ? '${series.remoteId}' : '${movedSeries.remoteId}'; result"
                        else "@js:book.bookUrl = '${series.remoteId}'; result")
                    put("name", "h1@text"); put("canReName", "true")
                    put("tocUrl", "@js:book.bookUrl"); put("updateTime", "@js:'published'")
                },
                "ruleToc" to buildJsonObject {
                    put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href")
                },
                "ruleContent" to buildJsonObject { put("content", "article@text") },
            )) }
            registry.register(RuleWebBookDataSource(id, source),
                SourceMetadata(WebDataSourceItem(id, "Canonical fixture", "test"), emptySet()))
        }

        fun info(book: SourceBookId) = book.bind(BookInformation(book.remoteId, "Old single", author = "Author",
            description = "", publishingHouse = "", wordCount = WordCount(1),
            lastUpdated = LocalDateTime.of(2026, 9, 24, 0, 0), isComplete = false))
        fun volumes(book: SourceBookId) = book.bind(BookVolumes(book.remoteId, listOf(Volume("default", "",
            listOf(ChapterInformation(book.remoteId, "Saved chapter"))))))
        suspend fun save(book: SourceBookId) {
            local.updateBookInformation(info(book)); local.updateBookVolumes(volumes(book))
            val chapter = SourceChapterId(book, book.remoteId).storageKey
            local.updateUserReadingData(book.storageKey) { it.copyWithUpdatedChapterReadingProgress(chapter, 0.7f)
                .copy(lastReadChapterId = chapter, totalReadTime = 30) }
        }
        fun reopen() { db.close(); db = open() }
        override fun close() { registry.unregister(id); db.close(); root.deleteRecursively() }
    }

    @Test fun directoryAndDetailsConvergeWithoutLosingCachedChaptersOrOldRoutes() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            save(first); save(second)
            shelves.addBookshelf(Bookshelf(id = 1, name = "Shelf"))
            shelves.addBookIntoBookShelf(1, info(first))
            val oldChapter = SourceChapterId(first, first.remoteId)
            val attempt = downloads.begin(first, 0, "old-attempt")
            downloads.target(attempt, volumes(first), "fixture", "")
            val image = SourceImage(first, "https://fixture.invalid/preserved.png")
            val bytes = byteArrayOf(1, 2, 3, 4)
            downloads.saveImage(attempt, image.uri, false, bytes)
            downloads.saveChapter(attempt, oldChapter.bind(ChapterContent(first.remoteId, "First", buildJsonObject { put("body", "saved") })),
                "old-signature", listOf(image.uri))
            assertTrue(books.getBookVolumesFlow(first.storageKey).last().isOk)
            val results = listOf(first, second, series).map { book -> async { books.refreshBookInformation(book) } }.awaitAll()
            assertTrue(results.all { it.isOk })
            assertEquals(listOf(series.storageKey), db.bookInformationDao().getAllEntities().map { it.id })
            assertEquals(series, aliases.resolve(first)); assertEquals(series, aliases.resolve(second))
            shelves.addBookIntoBookShelf(1, info(second))
            assertEquals(listOf(series.storageKey), shelves.getBookshelf(1)!!.allBookIds)
            assertEquals(60, local.getUserReadingData(first.storageKey).totalReadTime)
            val targetChapter = SourceChapterId(series, first.remoteId).storageKey
            assertEquals(SourceChapterId(series, second.remoteId).storageKey, db.chapterContentDao().get(targetChapter)!!.nextChapter)
            assertArrayEquals(bytes, downloads.image(image)!!.readBytes())
            assertEquals(series.storageKey, db.bookDownloadDao().allChapters().single().bookId)
            assertNotEquals(BookDownloadPhase.Complete, books.downloadState(first.storageKey).phase)
            try { downloads.finish(attempt, true); fail("An obsolete attempt must not write") } catch (_: CancellationException) { }
            local.updateBookInformation(info(first)); local.updateBookVolumes(volumes(first))
            assertEquals(2, local.getBookVolumes(series.storageKey)!!.volumes.single().chapters.size)
            assertEquals("Series", local.getBookInformation(first.storageKey)!!.title)
            val beforeReset = reading.progressRevision()
            val catalog = local.getBookVolumes(series.storageKey)!!.volumes.single().chapters.map { it.id }.toSet()
            reading.setChaptersRead(series.storageKey, setOf(targetChapter), catalog, read = false)
            assertFalse(reading.updateChapterProgress(first.storageKey, oldChapter.storageKey, beforeReset) { it })
            shelves.deleteBookFromBookshelf(1, first.storageKey)
            assertTrue(shelves.getBookshelf(1)!!.allBookIds.isEmpty())
            reopen()
            assertEquals(series, BookAliasStore(db).resolve(first))
            assertEquals(60, db.userReadingDataDao().getEntity(series.storageKey)!!.totalReadTime)
            assertNotNull(db.chapterContentDao().get(targetChapter))
        } } }
    }

    @Test fun canonicalDetailsCanProposeAnotherIdentityBeforeHostPersistence() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture, redirectSeries = true).use { library -> with(library) {
            save(first)
            assertTrue(books.refreshBookInformation(first).isOk)
            assertEquals(movedSeries, aliases.resolve(first))
            assertEquals("Moved series", local.getBookInformation(first.storageKey)!!.title)
            assertTrue(books.refreshBookInformation(movedSeries).isOk)
            assertTrue(books.refreshBookInformation(series).isOk)
            assertEquals(listOf(movedSeries.storageKey), db.bookInformationDao().getAllEntities().map { it.id })
            assertEquals(movedSeries, aliases.resolve(series))
            assertEquals(30, local.getUserReadingData(movedSeries.storageKey).totalReadTime)
            assertEquals(SourceChapterId(movedSeries, first.remoteId).storageKey,
                local.getUserReadingData(movedSeries.storageKey).lastReadChapterId)
        } } }
    }

    @Test fun inaccessibleSeriesAndMissingSavedChapterDoNotCommitHostAliases() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            save(first)
            status = 404
            assertTrue(books.refreshBookInformation(first).isErr)
            assertEquals(first, aliases.resolve(first))
            assertEquals(30, local.getUserReadingData(first.storageKey).totalReadTime)
            status = 200; includeFirst = false
            assertTrue(books.refreshBookInformation(first, fresh = true).isErr)
            assertEquals(first, aliases.resolve(first))
            assertEquals("Old single", local.getBookInformation(first.storageKey)!!.title)
            assertNull(db.bookInformationDao().get(series.storageKey))
        } } }
    }
}
