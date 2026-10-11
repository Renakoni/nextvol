package indi.renakoni.nextvol.data.book

import androidx.work.WorkManager
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.proxy.ProxyWebBookDataSource
import io.mockk.coEvery
import io.mockk.every
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.BookDownloadScheduler
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.mockk.mockk

internal class BookRepositoryFixture {
    private val aliases = mockk<BookAliasStore> {
        every { observe(any()) } answers { kotlinx.coroutines.flow.flowOf(firstArg()) }
        coEvery { resolve(any()) } coAnswers { firstArg() }
        coEvery { withResolved<Any?>(any(), any()) } coAnswers { secondArg<suspend (SourceBookId) -> Any?>().invoke(firstArg()) }
    }
    val local = mockk<LocalBookDataSource> {
        every { aliases } returns this@BookRepositoryFixture.aliases
        coEvery { getReusableChapterContent(any(), any()) } returns null
        coEvery { updateChapterContent(any(), any()) } coAnswers { updateChapterContent(firstArg()) }
    }
    val remote = mockk<ProxyWebBookDataSource>()
    var activeRemote = remote
    private val runtime = mockk<SourceRuntime> {
        coEvery { execute<Any?>(any()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        coEvery { canonicalBookId(any()) } coAnswers { firstArg() }
        coEvery { getBookInformation(any(), any(), any()) } coAnswers { activeRemote.getBookInformation(firstArg(), secondArg()) }
        coEvery { getBookVolumes(any(), any(), any()) } coAnswers { activeRemote.getBookVolumes(firstArg(), secondArg()) }
        coEvery { getChapterContent(any(), any(), any(), any()) } coAnswers { activeRemote.getChapterContent(firstArg(), secondArg(), thirdArg()) }
    }
    val registry = mockk<WebSourceRegistry> {
        every { sources } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        coEvery { resolve(BookIdentity.book("book").sourceId) } returns SourceResolution.Ready(runtime)
    }
    val text = mockk<TextProcessingRepository>()
    val workManager by lazy { mockk<WorkManager>() }
    val bookshelves by lazy { mockk<BookshelfRepository>() }

    val downloads = mockk<BookDownloadStore>(relaxed = true)
    val localBooks = mockk<indi.renakoni.nextvol.data.localbook.LocalBookStore>()
    val scheduler by lazy { BookDownloadScheduler(downloads, workManager, aliases) }

    fun chapterRepository() = ChapterRepository(registry, local, text, localBooks, downloads)

    fun repository() = BookRepository(
        local, bookshelves, text, workManager,
        chapterRepository(), BookReadingDataRepository(local), registry, downloads, localBooks, scheduler,
    )
}
