package indi.renakoni.nextvol.tts

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.content.ComponentDataRegistry
import indi.renakoni.nextvol.data.download.BookDownloadScheduler
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SpeechAnalysisBookSourceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val local = mockk<LocalBookDataSource>()
    private val imported = mockk<LocalBookStore>()
    private val books = mockk<BookRepository>()
    private val work = mockk<WorkManager>()
    private val scheduler = mockk<BookDownloadScheduler>()
    private val components = mockk<ComponentDataRegistry> {
        every { serializeMap } returns mapOf("lightnovelreader:simple_text" to SimpleTextComponentData.jsonSerializer)
    }
    private val source = SpeechAnalysisBookSource(context, local, imported, components, books, work, scheduler)
    private val book = BookIdentity.book("analysis-book")
    private val ids = (1..3).map { BookIdentity.chapter(it.toString(), book).storageKey }
    private val volumes = BookVolumes(book.storageKey,
        listOf(Volume("volume", "Volume", ids.map { ChapterInformation(it, "Chapter") })))

    private fun body(id: String, text: String) = ChapterContent(id, "Chapter",
        Json.parseToJsonElement("""{"components":[{"id":"simple_text","data":{"text":"$text"}}]}""").jsonObject)

    private fun cache() {
        coEvery { local.getBookVolumes(book.storageKey) } returns volumes
        coEvery { local.getBookInformation(book.storageKey) } returns null
    }

    @Test fun cachedInputKeepsDirectoryOrderSkipsMissingChaptersAndDeletesItsTemporaryFile() = runBlocking {
        cache()
        coEvery { local.isChapterContentExists(any()) } answers { firstArg<String>() != ids[1] }
        coEvery { local.getChapterContent(ids[0]) } returns body(ids[0], "林逸到来。")
        coEvery { local.getChapterContent(ids[1]) } returns null
        coEvery { local.getChapterContent(ids[2]) } returns body(ids[2], "林逸离开。")
        val before = context.cacheDir.listFiles().orEmpty().toSet()
        val input = source.openCached(book.storageKey)
        assertEquals(2, input.book.cachedChapters)
        assertEquals(3, input.book.totalChapters)
        assertEquals("林逸到来。\n林逸离开。\n", input.reader.readText())
        val created = context.cacheDir.listFiles().orEmpty().toSet() - before
        assertEquals(1, created.size)
        input.reader.close()
        assertTrue(created.none { it.exists() })
        coVerify(exactly = 1) { local.getChapterContent(ids[0]) }
        coVerify(exactly = 0) { books.downloadDirectory(any(), any()) }
        coVerify(exactly = 0) { books.submitDownload(any(), any()) }
    }

    @Test fun importedBookUsesItsOriginalStorageWithoutNetworkOrRoomChapterReads() = runBlocking {
        val importedBook = SourceBookId(LocalBookStore.SOURCE, "original")
        val chapter = BookIdentity.chapter("0", importedBook)
        coEvery { imported.readVolumes(importedBook) } returns Ok(BookVolumes(importedBook.storageKey,
            listOf(Volume("volume", "Volume", listOf(ChapterInformation(chapter.storageKey, "Chapter"))))))
        coEvery { imported.readChapter(chapter) } returns Ok(body(chapter.storageKey, "本地正文。"))
        coEvery { local.getBookInformation(importedBook.storageKey) } returns null
        coEvery { imported.readInformation(importedBook) } returns Ok(mockk<BookInformation> { every { title } returns "本地书名" })
        val input = source.openCached(importedBook.storageKey)
        assertTrue(input.book.local)
        assertEquals("本地书名", input.book.title)
        assertEquals(1, input.book.cachedChapters)
        input.reader.use { assertEquals("本地正文。\n", it.readText()) }
        source.download(importedBook.storageKey) { fail("A local book has no download progress") }
        coVerify(exactly = 0) { local.getChapterContent(any()) }
        coVerify(exactly = 0) { books.submitDownload(any(), any()) }
    }

    @Test fun verificationReplacementKeepsFollowingTheDownloadUntilTheBookIsComplete() = runBlocking {
        cache()
        var complete = false
        coEvery { local.isChapterContentExists(any()) } answers { complete }
        coEvery { books.downloadDirectory(book, any()) } returns Ok(volumes)
        val original = UUID.randomUUID()
        val resumed = UUID.randomUUID()
        coEvery { books.submitDownload(book.storageKey, ids) } returnsMany listOf(
            DownloadSubmission.Accepted(original, DownloadTaskState(DownloadTaskStatus.Running)),
            DownloadSubmission.Accepted(resumed, DownloadTaskState(DownloadTaskStatus.Queued), existing = true),
        )
        every { books.downloadStatusFlow(book.storageKey) } answers {
            flow { emit(BookDownloadStatus(task = DownloadTaskState(
                if (complete) DownloadTaskStatus.Complete else DownloadTaskStatus.Queued))) }
        }
        every { work.getWorkInfoByIdFlow(original) } returns
            flowOf(mockk<WorkInfo> { every { state } returns WorkInfo.State.FAILED })
        every { work.getWorkInfoByIdFlow(resumed) } returns flow {
            complete = true
            emit(mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED })
        }
        assertEquals(3, source.download(book.storageKey) {}.cachedChapters)
        coVerify(exactly = 2) { books.submitDownload(book.storageKey, ids) }
        coVerify(exactly = 0) { scheduler.dismiss(any(), any()) }
    }

    @Test fun fullDownloadWaitsForExistingSelectionThenExplicitlyFillsTheWholeDirectory() = runBlocking {
        cache()
        val cached = mutableSetOf(ids[0])
        coEvery { local.isChapterContentExists(any()) } answers { firstArg<String>() in cached }
        coEvery { books.downloadDirectory(book, any()) } returns Ok(volumes)
        val first = UUID.randomUUID()
        val full = UUID.randomUUID()
        coEvery { books.submitDownload(book.storageKey, ids) } returnsMany listOf(
            DownloadSubmission.Accepted(first, DownloadTaskState(DownloadTaskStatus.Running), existing = true, selectionMatches = false),
            DownloadSubmission.Accepted(full, DownloadTaskState(DownloadTaskStatus.Queued)),
        )
        every { books.downloadStatusFlow(book.storageKey) } returns
            flowOf(BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Complete)))
        every { work.getWorkInfoByIdFlow(first) } returns flow {
            cached += ids[1]
            emit(mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED })
        }
        every { work.getWorkInfoByIdFlow(full) } returns flow {
            cached += ids[2]
            emit(mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED })
        }
        assertEquals(3, source.download(book.storageKey) {}.cachedChapters)
        coVerify(exactly = 2) { books.submitDownload(book.storageKey, ids) }
        coVerify(exactly = 0) { scheduler.dismiss(any(), any()) }
        source.download(book.storageKey) { fail("A fully cached book needs no download") }
        coVerify(exactly = 2) { books.submitDownload(book.storageKey, ids) }
    }

    @Test fun cancellationStopsAnalysisContinuationAndOnlyCancelsOwnedDownloads() = runBlocking {
        cache()
        coEvery { local.isChapterContentExists(any()) } returns false
        coEvery { books.downloadDirectory(book, any()) } returns Ok(volumes)
        coEvery { scheduler.dismiss(any(), any()) } returns Unit
        every { books.downloadStatusFlow(book.storageKey) } returns
            flowOf(BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Running)))
        val owned = UUID.randomUUID()
        for (existing in listOf(true, false)) {
            val id = if (existing) UUID.randomUUID() else owned
            val waiting = CompletableDeferred<Unit>()
            coEvery { books.submitDownload(book.storageKey, ids) } returns
                DownloadSubmission.Accepted(id, DownloadTaskState(DownloadTaskStatus.Running), existing = existing)
            every { work.getWorkInfoByIdFlow(id) } returns flow {
                waiting.complete(Unit)
                awaitCancellation()
            }
            var continued = false
            val analysis = launch { source.download(book.storageKey) {}; continued = true }
            withTimeout(5000) { waiting.await() }
            analysis.cancelAndJoin()
            assertFalse(continued)
        }
        coVerify(exactly = 1) { scheduler.dismiss(book, owned.toString()) }
        coVerify(exactly = 1) { scheduler.dismiss(any(), any()) }
        verify(exactly = 2) { work.getWorkInfoByIdFlow(any()) }
    }
}
