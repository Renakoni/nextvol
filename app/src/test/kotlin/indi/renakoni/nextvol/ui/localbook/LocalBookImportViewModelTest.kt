package indi.renakoni.nextvol.ui.localbook

import android.app.Application
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.localbook.LocalBookBlock
import indi.renakoni.nextvol.data.localbook.LocalBookChapter
import indi.renakoni.nextvol.data.localbook.LocalBookDraft
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import indi.renakoni.nextvol.data.localbook.LocalBookImportException
import indi.renakoni.nextvol.data.localbook.LocalBookImportReason
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.localbook.ParsedLocalBook
import indi.renakoni.nextvol.data.localbook.TxtBookParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBookImportViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
    } }
    private lateinit var database: NextVolDatabase
    private lateinit var books: LocalBookStore
    private val models = ViewModelStore()
    private val jobs = mutableListOf<kotlinx.coroutines.Job>()

    @Before fun setUp() = runBlocking {
        // These tests use real Room/IO and runBlocking, including the preview debounce.
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        database.bookshelfDao().createBookshelf(BookshelfEntity(7, "Shelf", BookshelfSortType.Default.key,
            autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(), pinnedBookIds = emptyList(), updatedBookIds = emptyList()))
        books = LocalBookStore(context, database)
    }

    private fun model(store: LocalBookStore = books) =
        LocalBookImportViewModel(context, store, mockk(relaxed = true)).also {
            models.put(jobs.size.toString(), it)
            jobs += it.viewModelScope.coroutineContext.job
        }

    private fun file(charset: java.nio.charset.Charset = Charsets.UTF_8) = temporary.newFile("book.txt").apply {
        writeBytes("第一章 开始\n完整正文甲\n第二章 结束\n完整正文乙".toByteArray(charset))
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    @After fun close() = runBlocking {
        models.clear()
        withTimeout(10_000) { jobs.forEach { it.join() } }
        await { File(context.filesDir, "local-books").listFiles().orEmpty().none { it.name.startsWith("pending-") } }
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun anInvalidRuleImmediatelyDisablesImportAndOnlyTheRepairedPreviewCanBeConfirmed() = runBlocking {
        val model = model()
        model.open(file().toUri())
        await { !model.state.busy }
        assertTrue(model.state.error?.details, model.state.canImport)
        assertEquals(2, model.state.preview!!.chapters.size)
        model.changeRule("(")
        assertFalse(model.state.canImport)
        model.confirm()
        await { !model.state.busy }
        assertNotNull(model.state.error)
        assertEquals(LocalBookImportReason.InvalidRule, model.state.error!!.reason)
        assertTrue(database.importedBookDao().allIds().isEmpty())
        model.changeRule("")
        await { !model.state.busy }
        assertEquals(1, model.state.preview!!.chapters.size)
        model.changeTitle("Chosen title")
        model.confirm()
        model.confirm()
        val shelf = withTimeout(10_000) { model.imported.first() }
        assertEquals("Local Books", database.bookshelfDao().getBookshelf(shelf)!!.name)
        assertFalse(model.state.visible)
        assertEquals(1, database.importedBookDao().allIds().size)
        val id = database.importedBookDao().allIds().single()
        assertEquals("Chosen title", database.bookInformationDao().get(id)!!.title)
    }

    @Test fun externallyTypedOpaqueFileUsesTheResolvedNameAndWaitsForConfirmation() = runBlocking {
        val original = temporary.newFile("12345").apply { writeText("Chapter 1\nNovel contents") }
        val model = model()
        model.open(original.toUri(), "Shared novel.txt", LocalBookFormat.TXT)
        await { !model.state.busy }
        assertTrue(model.state.error?.details, model.state.canImport)
        assertEquals("Shared novel.txt", model.state.fileName)
        assertEquals("Local Books", model.state.shelfName)
        assertEquals(listOf(7), database.bookshelfDao().getAllBookshelfIds())
        assertTrue(database.bookshelfDao().getBookshelf(7)!!.allBookIds.isEmpty())
        model.confirm()
        await { !model.state.visible }
        val localShelf = database.bookshelfDao().getAllBookshelves().single { it.name == "Local Books" }
        assertEquals(1, localShelf.allBookIds.size)
        assertTrue(database.bookshelfDao().getBookshelf(7)!!.allBookIds.isEmpty())
        assertTrue(original.exists())
    }

    @Test fun externalImportsCreateOneLocalShelfAndAppendWithoutChangingOtherShelves() = runBlocking {
        database.bookshelfDao().createBookshelf(BookshelfEntity(0, "Other shelf", BookshelfSortType.Default.key,
            autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(), pinnedBookIds = emptyList(), updatedBookIds = emptyList()))
        val original = file()
        val model = model()
        repeat(2) {
            model.open(original.toUri())
            await { !model.state.busy }
            assertTrue(model.state.canImport)
            model.confirm()
            await { !model.state.visible }
        }
        val shelves = database.bookshelfDao().getAllBookshelves()
        assertEquals(3, shelves.size)
        val localShelf = shelves.single { it.name == "Local Books" }
        assertEquals(2, localShelf.allBookIds.distinct().size)
        assertTrue(shelves.filter { it.id == 0 || it.id == 7 }.all { it.allBookIds.isEmpty() })
        assertEquals(localShelf.id, model.imported.first())
    }

    @Test fun externalImportReusesAnExistingNamedShelfAndPreservesItsSettings() = runBlocking {
        val shelf = BookshelfEntity(42, "Local Books", BookshelfSortType.Default.key, sortReversed = true,
            autoCache = true, systemUpdateReminder = false, allBookIds = emptyList(), pinnedBookIds = emptyList(), updatedBookIds = emptyList())
        database.bookshelfDao().createBookshelf(shelf)
        val model = model()
        model.open(file().toUri())
        await { !model.state.busy }
        model.confirm()
        await { !model.state.visible }
        val updated = database.bookshelfDao().getBookshelf(42)!!
        assertEquals(shelf, updated.copy(allBookIds = emptyList()))
        assertEquals(1, updated.allBookIds.size)
        assertEquals(2, database.bookshelfDao().getAllBookshelfIds().size)
        assertEquals(42, model.imported.first())
    }

    @Test fun cancellingExternalImportCreatesNoShelfAndImportStillWorksWithNoExistingShelves() = runBlocking {
        database.bookshelfDao().deleteBookshelf(7)
        val model = model()
        val original = file()
        model.open(original.toUri())
        await { !model.state.busy }
        model.dismiss()
        assertTrue(database.bookshelfDao().getAllBookshelfIds().isEmpty())
        model.open(original.toUri())
        await { !model.state.busy }
        model.confirm()
        await { !model.state.visible }
        assertEquals("Local Books", database.bookshelfDao().getAllBookshelves().single().name)
    }

    @Test fun aFailedAutomaticDecodeCanBeCorrectedBeforeImport() = runBlocking {
        val model = model()
        model.open(file(Charsets.UTF_16LE).toUri())
        await { !model.state.busy }
        assertNotNull(model.state.error)
        model.changeEncoding("UTF-16LE")
        await { !model.state.busy }
        assertTrue(model.state.error?.details, model.state.canImport)
        assertEquals("book", model.state.title)
        assertEquals("UTF-16LE", model.state.preview!!.encoding)
    }

    @Test fun cancellingThePreviewLeavesTheShelfAndOriginalFileUntouched() = runBlocking {
        val model = model()
        val file = file()
        model.open(file.toUri())
        await { !model.state.busy }
        model.dismiss()
        await { File(context.filesDir, "local-books").listFiles().orEmpty().isEmpty() }
        assertFalse(model.state.visible)
        assertTrue(database.bookshelfDao().getBookshelf(7)!!.allBookIds.isEmpty())
        assertTrue(file.isFile)
    }

    @Test fun emptyAndOversizedFilesHaveStableReasonsBeforePreview() = runBlocking {
        val model = model()
        model.open(temporary.newFile("empty.txt").toUri())
        await { !model.state.busy }
        assertEquals(LocalBookImportReason.EmptyFile, model.state.error!!.reason)
        val large = temporary.newFile("large.txt")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        model.open(large.toUri())
        await { !model.state.busy }
        assertEquals(LocalBookImportReason.FileTooLarge, model.state.error!!.reason)
        assertTrue(database.importedBookDao().allIds().isEmpty())
    }

    @Test fun finalPublicationFailureKeepsAnAccurateReasonAndTheDialog() = runBlocking {
        val store = spyk(books)
        coEvery { store.publish(any(), any(), any(), any(), any()) } throws
            LocalBookImportException(LocalBookImportReason.Storage, "Cannot publish the imported files.")
        val model = model(store)
        model.open(file().toUri())
        await { model.state.canImport }
        model.confirm()
        await { !model.state.importing }
        assertEquals(LocalBookImportReason.Storage, model.state.error!!.reason)
        assertTrue(model.state.visible)
        model.dismiss()
    }

    @Test fun unknownFailureIsDiagnosticOnlyAndReselectingCanRetry() = runBlocking {
        val store = mockk<LocalBookStore>(relaxed = true)
        coEvery { store.stage(any()) } throws IllegalStateException("private raw diagnostic")
        val model = model(store)
        val source = file()
        model.open(source.toUri())
        await { !model.state.busy }
        assertEquals(LocalBookImportReason.Unknown, model.state.error!!.reason)
        assertTrue(model.state.error!!.details.contains("private raw diagnostic"))
        val staged = LocalBookDraft(SourceBookId(LocalBookStore.SOURCE, "retry"), "book.txt", LocalBookFormat.TXT, temporary.newFolder())
        coEvery { store.stage(any()) } returns staged
        coEvery { store.preview(any(), any(), any()) } returns ParsedLocalBook("Retry", listOf(
            LocalBookChapter("Chapter", blocks = listOf(LocalBookBlock.Text("Text")))))
        model.open(source.toUri())
        await { model.state.canImport }
        assertNull(model.state.error)
    }

    @Test fun aSlowSupersededPreviewCannotReplaceTheLatestResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val staged = LocalBookDraft(SourceBookId(LocalBookStore.SOURCE, "draft"), "book.txt", LocalBookFormat.TXT, temporary.newFolder())
        fun parsed(title: String) = ParsedLocalBook(title, listOf(LocalBookChapter(title, blocks = listOf(LocalBookBlock.Text(title)))))
        val store = mockk<LocalBookStore>(relaxed = true)
        coEvery { store.stage(any()) } returns staged
        coEvery { store.preview(any(), any(), any()) } coAnswers {
            when (val rule = thirdArg<String>()) {
                "old" -> withContext(NonCancellable) { started.complete(Unit); release.await(); finished.complete(Unit); parsed("Old") }
                TxtBookParser.DEFAULT_RULE -> parsed("Initial")
                else -> parsed(rule)
            }
        }
        val model = model(store)
        model.open(file().toUri())
        await { !model.state.busy }
        try {
            model.changeRule("old")
            withTimeout(10_000) { started.await() }
            model.changeRule("Latest")
            await { !model.state.busy }
            assertEquals("Latest", model.state.preview!!.title)
            release.complete(Unit)
            withTimeout(10_000) { finished.await() }
            delay(50)
            assertEquals("Latest", model.state.preview!!.title)
            assertTrue(model.state.canImport)
        } finally {
            release.complete(Unit)
        }
    }

    @Test fun clearingTheScreenWaitsForConfirmedPublicationBeforeDiscardingTheDraft() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staged = LocalBookDraft(SourceBookId(LocalBookStore.SOURCE, "publishing"), "book.txt", LocalBookFormat.TXT, temporary.newFolder())
        val parsed = ParsedLocalBook("Book", listOf(LocalBookChapter("Chapter", blocks = listOf(LocalBookBlock.Text("Body")))))
        val store = mockk<LocalBookStore>(relaxed = true)
        coEvery { store.stage(any()) } returns staged
        coEvery { store.preview(any(), any(), any()) } returns parsed
        coEvery { store.publish(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                staged.book to 7
            }
        }
        val model = model(store)
        try {
            model.open(file().toUri())
            await { model.state.canImport }
            model.confirm()
            withTimeout(10_000) { started.await() }
            models.clear()
            delay(50)
            coVerify(exactly = 0) { store.discard(any()) }
        } finally {
            release.complete(Unit)
        }
        withTimeout(10_000) { jobs.forEach { it.join() } }
        coVerify(exactly = 1) { store.publish(staged, parsed, "Book", null, "Local Books") }
    }
}
