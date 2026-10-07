package indi.renakoni.nextvol.data.bookshelf

import android.app.Application
import androidx.room.Room
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import indi.renakoni.nextvol.data.book.BookAliasStore
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadScheduler
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.selectedChapterIds
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class MetadataBookmarkTest {
    private lateinit var db: NextVolDatabase
    private lateinit var downloads: BookDownloadStore
    private lateinit var shelves: BookshelfRepository
    private val registry = WebSourceRegistry()
    private val work = mockk<WorkManager>(relaxed = true)
    private val metadata = SourceBookId(ZLibrarySources.ID, "1/abcdef")
    private val novel = SourceBookId(Identifier("fixture", "novel"), "book")
    private val submitted = mutableListOf<OneTimeWorkRequest>()
    private val info = BookInformation(metadata.storageKey, "Saved metadata", author = "Author", description = "",
        publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java).build()
        val aliases = BookAliasStore(db)
        downloads = BookDownloadStore(RuntimeEnvironment.getApplication(), db, ContentJsonDecoder(ContentComponentRegistry()))
        shelves = BookshelfRepository(db.bookshelfDao(), BookDownloadScheduler(downloads, work, aliases), registry, aliases)
        every { work.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        every { work.getWorkInfoByIdFlow(any()) } returns flowOf(null)
        every { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } answers {
            val request = thirdArg<OneTimeWorkRequest>()
            assertFalse("Download submission must run after the bookshelf transaction", db.inTransaction())
            runBlocking {
                val bookId = request.workSpec.input.getString("bookId")!!
                assertTrue(shelves.getBookshelf(1)!!.allBookIds.contains(bookId))
                assertTrue(db.bookshelfDao().getBookshelfBookMetadata(bookId)!!.bookShelfIds.contains(1))
            }
            submitted += request
            mockk<androidx.work.Operation> { every { result } returns
                com.google.common.util.concurrent.Futures.immediateFuture(androidx.work.Operation.SUCCESS) }
        }
        fun provider(book: SourceBookId) = object : WebBookDataSource by EmptyWebDataSource { override val id = book.sourceId }
        registry.register(provider(metadata), ZLibrarySources.METADATA)
        registry.register(provider(novel), SourceMetadata(WebDataSourceItem(novel.sourceId, "Novel", "fixture"),
            setOf(SourceCapability.Directory, SourceCapability.ChapterContent)))
        shelves.addBookshelf(Bookshelf(id = 1, name = "Automatic cache", autoCache = true))
    }

    @After fun tearDown() {
        registry.unregister(metadata.sourceId)
        registry.unregister(novel.sourceId)
        db.close()
    }

    @Test fun metadataSourcesAndDisabledAutoCacheOnlySaveBookmarks() = runBlocking {
        shelves.addBookIntoBookShelf(1, info)
        shelves.addBookshelf(Bookshelf(id = 2, name = "No automatic cache", autoCache = false))
        shelves.addBookIntoBookShelf(2, info.copy(id = novel.storageKey))
        assertTrue(shelves.getBookshelf(1)!!.allBookIds.contains(metadata.storageKey))
        assertTrue(shelves.getBookshelf(2)!!.allBookIds.contains(novel.storageKey))
        verify(exactly = 0) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test fun mainThreadCallerStillSubmitsAfterSavingMembership() = runBlocking {
        assertEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
        shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
        assertEquals(1, submitted.size)
        assertEquals("Queued", downloads.entry(novel)!!.taskStatus)
        assertEquals(listOf(novel.storageKey), shelves.getBookshelf(1)!!.allBookIds)
    }

    @Test fun existingManualTaskIsReusedWithoutReplacingItsSelection() = runBlocking(Dispatchers.IO) {
        val id = java.util.UUID.randomUUID()
        downloads.queueTask(novel, downloads.generation(), id.toString(), chapterIds = listOf("selected"))
        val active = mockk<androidx.work.WorkInfo> {
            every { this@mockk.id } returns id
            every { state } returns androidx.work.WorkInfo.State.ENQUEUED
        }
        every { work.getWorkInfoByIdFlow(id) } returns flowOf(active)
        shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
        assertEquals(id.toString(), downloads.entry(novel)!!.taskWorkId)
        assertEquals(setOf("selected"), downloads.entry(novel)!!.selectedChapterIds())
        verify(exactly = 0) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test fun newMembershipIsSavedBeforeSubmissionAndConcurrentDuplicatesStayIdle() = runBlocking(Dispatchers.IO) {
        coroutineScope {
            repeat(4) { launch(Dispatchers.Default) { shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey)) } }
        }
        val request = submitted.single()
        assertEquals(request.id.toString(), downloads.entry(novel)!!.taskWorkId)
        assertEquals("Queued", downloads.entry(novel)!!.taskStatus)
        assertTrue(request.workSpec.input.getBoolean("persistedTask", false))
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        downloads.finishTask(BookDownloadStore.Task(novel, downloads.generation(), request.id.toString()))
        shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
        verify(exactly = 1) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
        val newBook = novel.copy(remoteId = "new-book")
        shelves.addBookIntoBookShelf(1, info.copy(id = newBook.storageKey))
        assertEquals(listOf(novel.storageKey, newBook.storageKey), shelves.getBookshelf(1)!!.allBookIds)
        verify(exactly = 2) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }

    @Test fun automaticDownloadDropsOldSelectionAndSubmissionFailureKeepsBookmark() = runBlocking(Dispatchers.IO) {
        val oldWorkId = java.util.UUID.randomUUID().toString()
        downloads.queueTask(novel, downloads.generation(), oldWorkId, chapterIds = listOf("selected"))
        downloads.finishTask(BookDownloadStore.Task(novel, downloads.generation(), oldWorkId), DownloadFailure.Scheduling)
        every { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } returns
            mockk<androidx.work.Operation> { every { result } returns
                com.google.common.util.concurrent.Futures.immediateFailedFuture(IllegalStateException("Cannot schedule")) }
        shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
        assertEquals(listOf(novel.storageKey), shelves.getBookshelf(1)!!.allBookIds)
        val owner = downloads.entry(novel)!!
        assertEquals("Failed", owner.taskStatus)
        assertEquals(DownloadFailure.Scheduling.name, owner.taskError)
        assertNull(owner.selectedChapterIds())
        shelves.addBookIntoBookShelf(1, info.copy(id = novel.storageKey))
        verify(exactly = 1) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
    }
}
