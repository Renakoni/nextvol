package indi.renakoni.nextvol.data.download

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.room.Room
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressLifecycleTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var db: NextVolDatabase
    private val path = UserDataPath.CompletedDownloadBookList.path
    private val books = mockk<BookRepository> {
        every { getBookInformationFlow(any<String>()) } returns emptyFlow()
    }

    @Before fun openLibrary() {
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java,
            directory.root.resolve("library.db").path).allowMainThreadQueries().build()
    }

    @After fun closeLibrary() { db.close() }

    private fun repository(scope: CoroutineScope, dao: UserDataDao = db.userDataDao(),
        ready: CompletableDeferred<Unit> = CompletableDeferred()): DownloadProgressRepository {
        val downloads = mockk<BookDownloadStore> {
            coEvery { prepare() } coAnswers { ready.complete(Unit); Unit }
            every { observeEntries() } returns MutableStateFlow(emptyList())
        }
        return DownloadProgressRepository(dao, books, downloads, scope)
    }

    private fun item(id: String) = MutableDownloadItem(DownloadType.EPUB_EXPORT, id, emptyFlow())

    private fun observe(repository: DownloadProgressRepository, scope: CoroutineScope, item: DownloadItem): Job {
        val previous = scope.coroutineContext.job.children.toSet()
        repository.addExportItem(item)
        return scope.coroutineContext.job.children.single { it !in previous }
    }

    private suspend fun history() = DownloadProgressRepository.DownItemListUserData(path, db.userDataDao(), books)
        .getOrDefault(emptyList()).map { it.type to it.bookId }

    @Test fun concurrentCompletionsShareTheRoomBoundaryAndSurviveReopen() = runTest {
        db.userDataDao().insert(path, "", "CompletedDownloadItemList", "CACHE|a, EPUB_EXPORT|a")
        val entered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = db.userDataDao()
        val heldDao = object : UserDataDao by dao {
            override suspend fun update(path: String, group: String, type: String, transform: (String?) -> String) {
                dao.update(path, group, type) { value ->
                    val updated = transform(value)
                    entered.complete(Unit)
                    // Hold the real Room transaction after reading its old value.
                    runBlocking { withTimeout(5000) { release.await() } }
                    updated
                }
            }
        }
        val firstScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val secondScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val secondDao = object : UserDataDao by dao {
            override suspend fun update(path: String, group: String, type: String, transform: (String?) -> String) {
                secondEntered.complete(Unit)
                dao.update(path, group, type, transform)
            }
        }
        val firstReady = CompletableDeferred<Unit>()
        val secondReady = CompletableDeferred<Unit>()
        val first = repository(firstScope, heldDao, firstReady)
        val second = repository(secondScope, secondDao, secondReady)
        try {
            firstReady.await()
            secondReady.await()
            val firstObserver = observe(first, firstScope, item("a").apply { progress = 1f })
            entered.await()
            val secondObserver = observe(second, secondScope, item("b").apply { progress = 1f })
            secondEntered.await()
            release.complete(Unit)
            firstObserver.join()
            secondObserver.join()
            assertEquals(listOf(DownloadType.CACHE to "a", DownloadType.EPUB_EXPORT to "a",
                DownloadType.EPUB_EXPORT to "b"), history())
        } finally {
            release.complete(Unit)
            first.close()
            second.close()
        }
        db.close()
        openLibrary()
        val ready = CompletableDeferred<Unit>()
        val restarted = repository(CoroutineScope(Job() + StandardTestDispatcher(testScheduler)), ready = ready)
        try {
            ready.await()
            assertEquals(listOf("a", "b"), restarted.downloadItemIdList.map { it.bookId })
        } finally { restarted.close() }
    }

    @Test fun clearRetiresCompletedObserversButKeepsExportsThatFinishAfterIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dao = db.userDataDao()
        val heldDao = object : UserDataDao by dao {
            override suspend fun update(path: String, group: String, type: String, transform: (String?) -> String) {
                entered.complete(Unit)
                release.await()
                dao.update(path, group, type, transform)
            }
        }
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val repository = repository(scope, heldDao)
        try {
            val written = observe(repository, scope, item("a").apply { progress = 1f })
            entered.await()
            assertFalse(written.isCompleted)
            val late = item("late")
            val lateObserver = observe(repository, scope, late)
            val running = item("running")
            val runningObserver = observe(repository, scope, running)
            runCurrent()
            late.progress = 1f
            Snapshot.sendApplyNotifications()
            // The terminal snapshot is queued but has not reached its observer yet.
            repository.clearCompleted()
            val replacement = item("a")
            val replacementObserver = observe(repository, scope, replacement)
            running.progress = 1f
            replacement.progress = 1f
            Snapshot.sendApplyNotifications()
            runCurrent()
            release.complete(Unit)
            runningObserver.join()
            replacementObserver.join()
            assertTrue(written.isCompleted)
            assertTrue(lateObserver.isCompleted)
            assertEquals(setOf("running", "a"), repository.downloadItemIdList.map { it.bookId }.toSet())
            assertEquals(setOf(DownloadType.EPUB_EXPORT to "running", DownloadType.EPUB_EXPORT to "a"), history().toSet())
        } finally {
            release.complete(Unit)
            repository.close()
        }
    }

    @Test fun terminalRemovalAndReplacementEndOnlyTheirOwnObservation() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val repository = repository(scope)
        try {
            val failed = item("failed")
            val failedObserver = observe(repository, scope, failed)
            val removed = item("removed")
            val removedObserver = observe(repository, scope, removed)
            val old = item("same-book")
            val oldObserver = observe(repository, scope, old)
            runCurrent()
            failed.progress = -1f // Both failure and worker cancellation publish this terminal value.
            Snapshot.sendApplyNotifications()
            failedObserver.join()
            repository.removeExportItem(removed)
            val current = item("same-book")
            val currentObserver = observe(repository, scope, current)
            oldObserver.join()
            removedObserver.join()
            repository.removeExportItem(old)
            assertFalse(currentObserver.isCompleted)
            assertSame(current, repository.downloadItemIdList.single { it.bookId == "same-book" })
            old.progress = 1f
            removed.progress = 1f
            current.progress = 1f
            Snapshot.sendApplyNotifications()
            currentObserver.join()
            assertEquals(listOf(DownloadType.EPUB_EXPORT to "same-book"), history())
        } finally { repository.close() }
    }

    @Test fun initialReadCannotRestoreRemovedOrClearedCards() = runTest {
        for (clear in listOf(false, true)) {
            db.userDataDao().insert(path, "", "CompletedDownloadItemList", "EPUB_EXPORT|old")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dao = db.userDataDao()
            val heldDao = object : UserDataDao by dao {
                override suspend fun get(path: String): String? {
                    val saved = dao.get(path)
                    entered.complete(Unit)
                    release.await()
                    return saved
                }
            }
            val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
            val repository = repository(scope, heldDao)
            try {
                entered.await()
                val old = item("old").apply { progress = 1f }
                repository.addExportItem(old)
                if (clear) repository.clearCompleted() else repository.removeExportItem(old)
                val observer = observe(repository, scope, item("new").apply { progress = 1f })
                runCurrent()
                release.complete(Unit)
                observer.join()
                assertEquals(listOf("new"), repository.downloadItemIdList.map { it.bookId })
                // Removal hides the current card; only clear deletes already saved history.
                assertEquals(if (clear) listOf("new") else listOf("old", "new"), history().map { it.second })
            } finally {
                release.complete(Unit)
                repository.close()
            }
        }
    }

    @Test fun shutdownFinishesPendingDatabaseReadBeforeDatabaseCanClose() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val dao = mockk<UserDataDao>()
        coEvery { dao.get(any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() } finally { finished.complete(Unit) }
        }
        val repository = DownloadProgressRepository(dao, mockk(), mockk(relaxed = true))
        try {
            withTimeout(5000) { started.await() }
            withTimeout(5000) { repository.close() }
            assertTrue(finished.isCompleted)
            assertTrue(repository.downloadItemIdList.isEmpty())
        } finally { repository.close() }
    }
}
