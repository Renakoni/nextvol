package indi.renakoni.nextvol.data.book

import android.app.Application
import android.net.Uri
import androidx.concurrent.futures.ResolvableFuture
import androidx.room.Room
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.impl.WorkContinuationImpl
import androidx.work.impl.WorkDatabase
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.EnqueueRunnable
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import indi.renakoni.nextvol.ui.book.detail.DetailViewModel
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryOperationsTest {
    private val fixture = BookRepositoryFixture()

    @Test fun repeatedContinueActionsKeepTheWaitingExecutorAndDoNotResetItsBudget() = runTest {
        val book = BookIdentity.book("book")
        val id = java.util.UUID.randomUUID()
        val owner = indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(book.storageKey,
            taskWorkId = id.toString(), taskStatus = "WaitingRetry", taskRetryCount = 2, taskNextAttemptAt = Long.MAX_VALUE)
        coEvery { fixture.downloads.entry(book) } returns owner
        val waiting = mockk<WorkInfo> {
            every { this@mockk.id } returns id
            every { state } returns WorkInfo.State.ENQUEUED
        }
        every { fixture.workManager.getWorkInfoByIdFlow(id) } returns flowOf(waiting)
        val repository = fixture.repository()
        val first = async { repository.cacheBook(book.storageKey).first() }
        val second = async { repository.cacheBook(book.storageKey).first() }
        assertSame(waiting, first.await()); assertSame(waiting, second.await())
        coVerify(exactly = 0) { fixture.downloads.queueTask(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test fun explicitContinueReplacesARevokedCooldownInsteadOfReusingItsExecutor() = runTest {
        val book = BookIdentity.book("book")
        val name = CacheBookWork.ofId(book.storageKey)
        val oldId = java.util.UUID.randomUUID()
        coEvery { fixture.downloads.entry(book) } returns indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(
            book.storageKey, taskStatus = "Cancelled", taskError = "SourceUnavailable", taskRetryCount = 1)
        val waiting = mockk<WorkInfo> {
            every { id } returns oldId
            every { state } returns WorkInfo.State.ENQUEUED
        }
        every { fixture.workManager.getWorkInfosForUniqueWorkFlow(name) } returns flowOf(listOf(waiting))
        every { fixture.workManager.getWorkInfoByIdFlow(any()) } returns flowOf(null)
        val submitted = slot<OneTimeWorkRequest>()
        val completion = ResolvableFuture.create<Operation.State.SUCCESS>().apply { set(Operation.SUCCESS) }
        val operation = mockk<Operation> { every { result } returns completion }
        every { fixture.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, capture(submitted)) } returns operation

        fixture.repository().cacheBook(book.storageKey).first()

        verify(exactly = 1) { fixture.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
        assertTrue(submitted.captured.id != oldId)
        coVerify(exactly = 1) { fixture.downloads.queueTask(book, 0L, submitted.captured.id.toString(), resumePrevious = false) }
    }

    @Test fun staleNotificationCannotCancelAReplacementTask() = runTest {
        val book = BookIdentity.book("book")
        val current = java.util.UUID.randomUUID().toString()
        coEvery { fixture.downloads.entry(book) } returns indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(
            book.storageKey, taskWorkId = current, taskStatus = "Running")
        fixture.scheduler.dismiss(book, java.util.UUID.randomUUID().toString())
        coVerify(exactly = 0) { fixture.downloads.dismissTask(any(), any()) }
        verify(exactly = 0) { fixture.workManager.cancelWorkById(any()) }
        verify(exactly = 0) { fixture.workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun singleKeepWorkRetainsActiveIdentityAndReplacesTerminalRowsAcrossClockChanges() {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WorkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var time = 2_000L
        val config = Configuration.Builder().setClock(object : Clock {
            override fun currentTimeMillis() = time
        }).build()
        val manager = mockk<WorkManagerImpl> {
            every { workDatabase } returns database
            every { configuration } returns config
            every { schedulers } returns emptyList()
        }
        fun enqueue(request: OneTimeWorkRequest) {
            EnqueueRunnable.addToDatabase(WorkContinuationImpl(manager, CacheBookWork.ofId(BookIdentity.bookKey("book")), ExistingWorkPolicy.KEEP, listOf(request)))
        }
        fun request() = androidx.work.OneTimeWorkRequestBuilder<CacheBookWork>().build()
        try {
            val old = request()
            enqueue(old)
            val ignored = request()
            enqueue(ignored)
            assertEquals(listOf(old.id.toString()), database.workSpecDao().getWorkSpecIdAndStatesForName(CacheBookWork.ofId(BookIdentity.bookKey("book"))).map { it.id })
            assertNull(database.workSpecDao().getWorkSpec(ignored.id.toString()))

            database.workSpecDao().setState(WorkInfo.State.SUCCEEDED, old.id.toString())
            time = 1_000L
            val replacement = request()
            enqueue(replacement)
            assertEquals(listOf(replacement.id.toString()), database.workSpecDao().getWorkSpecIdAndStatesForName(CacheBookWork.ofId(BookIdentity.bookKey("book"))).map { it.id })
            assertNull(database.workSpecDao().getWorkSpec(old.id.toString()))
        } finally {
            database.close()
        }
    }

    @Test
    fun cacheWorkPersistsBeforeEnqueueAndObservesTheSubmittedIdentity() = runTest {
        val book = BookIdentity.book("book")
        val submitted = slot<OneTimeWorkRequest>()
        val persisted = CompletableDeferred<Unit>()
        val persistCompletion = CompletableDeferred<Unit>()
        val enqueued = CompletableDeferred<Unit>()
        val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation> { every { result } returns completion }
        coEvery { fixture.downloads.entry(book) } returns null
        coEvery { fixture.downloads.queueTask(book, 0L, any(), resumePrevious = false) } coAnswers {
            persisted.complete(Unit)
            persistCompletion.await()
        }
        every { fixture.workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(book.storageKey)) } returns flowOf(emptyList())
        every { fixture.workManager.enqueueUniqueWork(CacheBookWork.ofId(book.storageKey), ExistingWorkPolicy.KEEP, capture(submitted)) } answers {
            enqueued.complete(Unit)
            operation
        }
        try {
            val observed = fixture.repository().cacheBook("book")
            persisted.await() // Submission starts even without collecting its result.
            verify(exactly = 0) { fixture.workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
            persistCompletion.complete(Unit)
            enqueued.await()
            val work = submitted.captured
            assertEquals(CacheBookWork::class.java.name, work.workSpec.workerClassName)
            assertEquals(androidx.work.NetworkType.CONNECTED, work.workSpec.constraints.requiredNetworkType)
            assertEquals(mapOf("bookId" to book.storageKey, "downloadGeneration" to 0L, "persistedTask" to true), work.workSpec.input.keyValueMap)
            assertTrue(CacheBookWork.generationTag(0) in work.tags)
            coVerify(exactly = 1) { fixture.downloads.queueTask(book, 0L, work.id.toString(), resumePrevious = false) }

            val current = mockk<WorkInfo> { every { state } returns WorkInfo.State.RUNNING }
            every { fixture.workManager.getWorkInfoByIdFlow(work.id) } returns flowOf(current)
            val first = async { observed.first() }
            runCurrent()
            assertFalse(first.isCompleted)
            verify(exactly = 0) { fixture.workManager.getWorkInfoByIdFlow(any()) }
            completion.set(Operation.SUCCESS)
            assertSame(current, first.await())
            verify(exactly = 1) { fixture.workManager.getWorkInfoByIdFlow(work.id) }
        } finally {
            persistCompletion.cancel()
            completion.cancel(false)
        }
    }

    @Test
    fun cacheAndExportWaitForEnqueueBeforeReadingTerminalRecords() = runTest {
        for ((source, export) in listOf("a" to false, "b" to false, "a" to true, "b" to true)) {
            val book = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", source), "same")
            val env = BookRepositoryFixture()
            val name = if (export) ExportBookToEPUBWork.ofId(book.storageKey) else CacheBookWork.ofId(book.storageKey)
            val completion = ResolvableFuture.create<Operation.State.SUCCESS>()
            val operation = mockk<Operation> { every { result } returns completion }
            val submitted = slot<OneTimeWorkRequest>()
            coEvery { env.downloads.entry(book) } returns null
            every { env.workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, capture(submitted)) } returns operation
            fun completedWork() = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.SUCCEEDED
            }
            val old = completedWork()
            val current = completedWork()
            val infos = MutableStateFlow(listOf(old))
            every { env.workManager.getWorkInfosForUniqueWorkFlow(name) } returns infos
            every { env.workManager.getWorkInfoByIdFlow(any()) } returns flowOf(current)
            val observed = if (export) {
                DetailViewModel(env.repository(), mockk(), mockk(), env.workManager, mockk())
                    // This test starts at an enabled export action; capability gating has its own tests.
                    .apply { (uiState as indi.renakoni.nextvol.ui.book.detail.MutableDetailUiState).readingAvailable = true }
                    .exportToEpub(book.storageKey, "Title")
            } else env.repository().cacheBook(book.storageKey)
            val first = async { observed.first() }
            runCurrent()
            assertFalse(first.isCompleted)
            // Cache submission checks for an active executor, but must not emit the old terminal row.
            verify(exactly = if (export) 0 else 1) { env.workManager.getWorkInfosForUniqueWorkFlow(name) }
            verify(exactly = 0) { env.workManager.getWorkInfoByIdFlow(any()) }
            assertEquals(if (export) androidx.work.NetworkType.NOT_REQUIRED else androidx.work.NetworkType.CONNECTED,
                submitted.captured.workSpec.constraints.requiredNetworkType)

            infos.value = listOf(current)
            completion.set(Operation.SUCCESS)
            runCurrent()
            assertSame(current, first.await())
            if (!export) verify(exactly = 1) { env.workManager.getWorkInfoByIdFlow(submitted.captured.id) }
        }
    }

    @Test
    fun volumeCoverCallbackReceivesOnlyItsSourcesRemoteIds() = runTest {
        val repository = fixture.repository()
        for (source in listOf("a", "b")) {
            val book = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", source), "same")
            val runtime = mockk<indi.renakoni.nextvol.data.web.SourceRuntime>()
            coEvery { fixture.registry.resolve(book.sourceId) } returns indi.renakoni.nextvol.data.web.SourceResolution.Ready(runtime)
            val remoteVolume = Volume("volume", "Same title", listOf(ChapterInformation("chapter", "Chapter")))
            val remoteContent = io.nightfish.lightnovelreader.api.book.ChapterContent("chapter", "Chapter",
                kotlinx.serialization.json.JsonObject(emptyMap()), nextChapter = "next")
            val volume = book.bind(BookVolumes(book.remoteId, listOf(remoteVolume))).volumes.single()
            val chapter = SourceChapterId(book, "chapter").bind(remoteContent)
            val context = RuntimeEnvironment.getApplication()
            val cover = Uri.parse("https://fixture.invalid/$source.jpg")
            coEvery { runtime.volumeCover("same", remoteVolume, mutableMapOf("chapter" to remoteContent), context) } returns cover
            assertEquals(com.github.michaelbull.result.Ok(cover), repository.volumeCover(book, volume, mapOf(chapter.id to chapter), context))
            coVerify(exactly = 1) { runtime.volumeCover("same", remoteVolume, mutableMapOf("chapter" to remoteContent), context) }
        }
    }

    @Test
    fun tagsUseTheBookSourceAndReturnDataForHostNavigation() = runTest {
        val bookA = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "a"), "same")
        val bookB = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "b"), "same")
        val a = mockk<indi.renakoni.nextvol.data.web.SourceRuntime>()
        val b = mockk<indi.renakoni.nextvol.data.web.SourceRuntime>()
        every { a.bookTagPage("tag") } returns "page-a"
        every { b.bookTagPage("tag") } returns "page-b"
        coEvery { fixture.registry.resolve(bookA.sourceId) } returns indi.renakoni.nextvol.data.web.SourceResolution.Ready(a)
        coEvery { fixture.registry.resolve(bookB.sourceId) } returns indi.renakoni.nextvol.data.web.SourceResolution.Ready(b)
        val repository = fixture.repository()
        assertEquals(com.github.michaelbull.result.Ok(indi.renakoni.nextvol.data.web.SourceDiscoveryTarget(bookA.sourceId, "page-a")), repository.bookTagPage(bookA, "tag"))
        assertEquals(com.github.michaelbull.result.Ok(indi.renakoni.nextvol.data.web.SourceDiscoveryTarget(bookB.sourceId, "page-b")), repository.bookTagPage(bookB, "tag"))
        assertEquals(com.github.michaelbull.result.Ok(indi.renakoni.nextvol.data.web.SourceDiscoveryTarget(bookA.sourceId, "page-a")), repository.bookTagPage(bookA, "tag"))
        coEvery { fixture.registry.resolve(bookA.sourceId) } returns indi.renakoni.nextvol.data.web.SourceResolution.Missing(bookA.sourceId)
        assertTrue(repository.bookTagPage(bookA, "tag").isErr)
        verify(exactly = 2) { a.bookTagPage("tag") }
        verify(exactly = 1) { b.bookTagPage("tag") }
    }

    @Test
    fun cacheStatusKeepsMissingEmptyAndPartiallyCachedVolumeSemantics() = runTest {
        val repository = fixture.repository()
        coEvery { fixture.local.getBookVolumes("book") } returns null
        assertFalse(repository.getIsBookCached("book"))
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes("book", emptyList())
        assertFalse(repository.getIsBookCached("book"))
        val volume = Volume("volume", "title", emptyList())
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes("book", listOf(volume))
        assertTrue(repository.getIsBookCached("book"))
        coEvery { fixture.local.getBookVolumes("book") } returns BookVolumes(
            "book", listOf(volume.copy(chapters = listOf(ChapterInformation("first", "1"), ChapterInformation("second", "2")))),
        )
        coEvery { fixture.local.isChapterContentExists("first") } returns true
        coEvery { fixture.local.isChapterContentExists("second") } returns false
        assertFalse(repository.getIsBookCached("book"))
        coEvery { fixture.local.isChapterContentExists("second") } returns true
        assertTrue(repository.getIsBookCached("book"))
    }

    @Test
    fun readingUpdatesUseTheStoredValueAndRetainTheLocalObservationFlow() = runTest {
        val observed = MutableStateFlow(UserReadingData("book", totalReadTime = 10))
        coEvery { fixture.local.getUserReadingData("book") } answers { observed.value }
        coEvery { fixture.local.getAllUserReadingData() } answers { listOf(observed.value) }
        every { fixture.local.getUserReadingDataFlow("book") } returns observed
        coEvery { fixture.local.updateUserReadingData("book", any()) } answers {
            observed.value = secondArg<(UserReadingData) -> UserReadingData>()(observed.value)
        }
        val repository = fixture.repository()
        var transformations = 0
        assertSame(observed, repository.getUserReadingDataFlow("book"))
        repository.updateUserReadingData("book") {
            transformations++
            it.copy(totalReadTime = it.totalReadTime + 5, lastReadChapterId = "chapter")
        }
        val expected = UserReadingData("book", totalReadTime = 15, lastReadChapterId = "chapter")
        assertEquals(expected, repository.getUserReadingData("book"))
        assertEquals(listOf(expected), repository.getAllUserReadingData())
        assertEquals(expected, observed.value)
        assertEquals(1, transformations)
        coVerify(exactly = 1) { fixture.local.updateUserReadingData("book", any()) }
    }
}
