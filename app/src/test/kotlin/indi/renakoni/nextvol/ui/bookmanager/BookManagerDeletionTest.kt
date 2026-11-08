package indi.renakoni.nextvol.ui.bookmanager

import android.app.Application
import android.content.ContextWrapper
import androidx.concurrent.futures.ResolvableFuture
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.work.Operation
import androidx.work.WorkManager
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.storage.StorageBookCleanup
import indi.renakoni.nextvol.data.storage.StorageBookQuery
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookManagerDeletionTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getDataDir() = directory.root
        override fun getFilesDir() = File(directory.root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory.root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(directory.root, name)
    } }
    private lateinit var database: NextVolDatabase
    private lateinit var imports: LocalBookStore
    private lateinit var imported: SourceBookId
    private val remote = SourceBookId(Identifier("fixture", "online"), "book")
    private val work = mockk<WorkManager>()
    private val downloads = mockk<BookDownloadStore>(relaxed = true)
    private val progress = mockk<DownloadProgressRepository>(relaxed = true)
    private val query = mockk<StorageBookQuery>()
    private lateinit var model: BookManagerViewModel
    private val state get() = model.localBookManagerUiState
    private val cleared = CopyOnWriteArrayList<Int>()
    private val failures = CopyOnWriteArrayList<Unit>()
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun prepare(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        imports = LocalBookStore(context, database)
        val file = directory.newFile("book.txt").apply { writeText("第一章 开始\n正文") }
        val draft = imports.stage(file.toUri())
        imported = imports.publish(draft, imports.preview(draft), "Import", null).first
        database.bookInformationDao().insert(database.bookInformationDao().get(imported.storageKey)!!.copy(id = remote.storageKey))
        val usage = StorageUsageRepository(context, database, UserDataRepository(database.userDataDao()))
        val realQuery = StorageBookQuery(database, usage, imports)
        coEvery { query.getCachedBooks() } returns null
        coEvery { query.refreshBooks() } coAnswers { realQuery.refreshBooks() }
        completeCancellations()
        val books = mockk<BookRepository>()
        every { books.getBookInformationFlow(any<String>()) } returns emptyFlow()
        model = BookManagerViewModel(books, progress, work, query,
            StorageBookCleanup(database, usage, work, downloads, progress, imports))
        awaitOperations()
        collectors.launch { model.clearedItemsFlow.collect { cleared += it } }
        collectors.launch { model.deleteFailures.collect { failures += it } }
    }

    private fun completeCancellations() {
        val completed = ResolvableFuture.create<Operation.State.SUCCESS>().apply { set(Operation.SUCCESS) }
        every { work.cancelUniqueWork(any()) } returns mockk { every { result } returns completed }
    }

    private suspend fun awaitOperations() = withTimeout(5000) {
        model.viewModelScope.coroutineContext.job.children.toList().joinAll()
    }

    private fun assertReleased() {
        assertFalse(state.isDeleting)
        assertFalse(state.isLoading)
    }

    @After fun close() = runBlocking {
        if (::model.isInitialized) model.viewModelScope.coroutineContext.job.cancelAndJoin()
        collectors.cancel()
        if (::database.isInitialized) database.close()
        Dispatchers.resetMain()
    }

    @Test fun cancellationFailureRefreshesPartialDeletionAndRetriesOnlyTheRemainingSelection() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancellation = ResolvableFuture.create<Operation.State.SUCCESS>()
        every { work.cancelUniqueWork(CacheBookWork.ofId(remote.storageKey)) } answers {
            entered.complete(Unit)
            mockk { every { result } returns cancellation }
        }
        model.selectAllLocalBooks()
        state.deleteSelected()
        withTimeout(5000) { entered.await() }
        assertTrue(state.isDeleting)
        assertFalse(imports.contains(imported))
        cancellation.setException(IOException("cannot cancel cache work"))
        awaitOperations()

        assertReleased()
        assertEquals(1, failures.size)
        assertTrue(cleared.isEmpty())
        assertEquals(listOf(remote.storageKey), state.bookList.map { it.id })
        assertEquals(setOf(remote.storageKey), state.selectedIds)
        assertTrue(state.isSelecting)
        assertNotNull(database.bookInformationDao().get(remote.storageKey))
        verify(exactly = 0) { work.cancelUniqueWork(ExportBookToEPUBWork.ofId(remote.storageKey)) }
        coVerify(exactly = 0) { downloads.removeBooks(any()) }

        completeCancellations()
        state.deleteSelected()
        awaitOperations()
        assertReleased()
        assertEquals(listOf(1), cleared)
        assertEquals(1, failures.size)
        assertTrue(state.bookList.isEmpty())
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isSelecting)
        assertNull(database.bookInformationDao().get(remote.storageKey))
    }

    @Test fun downloadStoreAndRefreshFailuresStillReleaseBusyStateAndPreserveRetrySelection() = runBlocking {
        coEvery { downloads.removeBooks(any()) } throws IOException("cannot remove download files")
        coEvery { query.refreshBooks() } throws IOException("cannot read storage")
        model.enterLocalBookSelection(remote.storageKey)
        state.deleteSelected()
        awaitOperations()

        assertReleased()
        assertEquals(1, failures.size)
        assertTrue(cleared.isEmpty())
        assertEquals(setOf(remote.storageKey), state.selectedIds)
        assertTrue(state.isSelecting)
        assertNotNull(database.bookInformationDao().get(remote.storageKey))
        verify(exactly = 1) {
            work.cancelUniqueWork(CacheBookWork.ofId(remote.storageKey))
            work.cancelUniqueWork(ExportBookToEPUBWork.ofId(remote.storageKey))
        }
        verify(exactly = 0) { progress.clearCachedItems(any()) }
    }

    @Test fun coroutineCancellationRefreshesPartialDeletionWithoutFailureOrSuccessFeedback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancellation = ResolvableFuture.create<Operation.State.SUCCESS>()
        every { work.cancelUniqueWork(CacheBookWork.ofId(remote.storageKey)) } answers {
            entered.complete(Unit)
            mockk { every { result } returns cancellation }
        }
        model.selectAllLocalBooks()
        state.deleteSelected()
        withTimeout(5000) { entered.await() }
        val operation = model.viewModelScope.coroutineContext.job.children.single()
        assertTrue(state.isDeleting)
        withTimeout(5000) { operation.cancelAndJoin() }

        assertTrue(operation.isCancelled)
        assertReleased()
        assertTrue(failures.isEmpty())
        assertTrue(cleared.isEmpty())
        assertFalse(imports.contains(imported))
        assertEquals(listOf(remote.storageKey), state.bookList.map { it.id })
        assertEquals(setOf(remote.storageKey), state.selectedIds)
        assertTrue(state.isSelecting)
        assertNotNull(database.bookInformationDao().get(remote.storageKey))
        verify(exactly = 0) { work.cancelUniqueWork(ExportBookToEPUBWork.ofId(remote.storageKey)) }
        coVerify(exactly = 0) { downloads.removeBooks(any()) }
    }
}
