package indi.renakoni.nextvol.data.web

import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.BookDownloadScheduler
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.explore.ExploreRepository
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.LocalDataManager
import indi.renakoni.nextvol.data.local.cbor.AppLocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.work.*
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/** Actual Room, registry, repositories and WorkManager/Workers with synthetic remote providers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class)
class HostMultiSourceIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @get:Rule val directory = TemporaryFolder()
    private lateinit var db: NextVolDatabase
    private lateinit var manager: WebBookDataSourceManager
    private lateinit var local: LocalBookDataSource
    private lateinit var books: BookRepository
    private lateinit var shelves: BookshelfRepository
    private lateinit var stats: StatsRepository
    private lateinit var backup: LocalDataManager
    private lateinit var progress: DownloadProgressRepository
    private lateinit var downloads: BookDownloadStore
    private lateinit var workManager: WorkManager
    private val decoder = ContentJsonDecoder(ContentComponentRegistry())
    private val a = SourceBookId(Identifier("fixture", "a"), "123")
    private val b = SourceBookId(Identifier("fixture", "b"), "123")

    @Before fun create() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                when (workerClassName) {
                    CacheBookWork::class.java.name -> CacheBookWork(appContext, workerParameters, progress, books, downloads)
                    ExportBookToEPUBWork::class.java.name -> ExportBookToEPUBWork(appContext, workerParameters,
                        ExportBookToEpubUseCase(appContext, books, progress, decoder, downloads))
                    CheckUpdateWork::class.java.name -> CheckUpdateWork(appContext, workerParameters, books, shelves)
                    else -> null
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        workManager = WorkManager.getInstance(context)
        openHost()
    }

    private fun openHost() {
        db = Room.databaseBuilder(context, NextVolDatabase::class.java,
            directory.root.resolve("library.db").absolutePath).allowMainThreadQueries().build()
        manager = WebBookDataSourceManager(WebSourceRegistry())
        downloads = BookDownloadStore(context, db, decoder)
        local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
        val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, workManager, local.aliases)
        shelves = BookshelfRepository(db.bookshelfDao(), scheduler, manager.registry, local.aliases)
        // Disable optional display transformations; storage and content decoding use production adapters.
        val text = TextProcessingRepository(mockk { every { enabled } returns false },
            mockk { every { enabled } returns false }, ContentComponentRegistry())
        books = BookRepository(local, shelves, text, workManager, ChapterRepository(manager.registry, local, text, mockk(), downloads),
            BookReadingDataRepository(local), manager.registry, downloads, mockk(), scheduler)
        val coordinator = StatisticsWriteCoordinator()
        stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), books, coordinator)
        backup = LocalDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(), db.bookshelfDao(),
            db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(), db.userReadingDataDao(), db.userDataDao(),
            mockk(relaxed = true), coordinator, stats, downloads)
        progress = DownloadProgressRepository(db.userDataDao(), books, downloads)
    }

    @After fun close() = runBlocking {
        manager.registry.sources.value.forEach { manager.unregisterWebDataSource(it.metadata.id) }
        workManager.cancelAllWork().await()
        progress.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
    }

    private fun register(book: SourceBookId) = FixtureSource(book.sourceId).also {
        manager.registerWebDataSource(it, WebDataSourceItem(it.id, it.id.id, "fixture"))
    }

    private suspend fun awaitWork(request: OneTimeWorkRequest): WorkInfo {
        workManager.enqueue(request).await()
        return withTimeout(30_000) { workManager.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished } }
    }

    private suspend fun allowNetwork(book: SourceBookId) {
        val queued = withTimeout(30_000) {
            workManager.getWorkInfosForUniqueWorkFlow(CacheBookWork.ofId(book.storageKey))
                .first { work -> work.any { !it.state.isFinished } }.first { !it.state.isFinished }
        }
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(indi.renakoni.nextvol.data.download.DownloadTaskStatus.Queued.name, downloads.entry(book)!!.taskStatus)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(queued.id)
    }

    private suspend fun assertLocalLibrary() {
        assertEquals(setOf(a.storageKey, b.storageKey), shelves.getBookshelf(1)!!.allBookIds.toSet())
        for ((book, fraction) in listOf(a to 0.25f, b to 0.75f)) {
            val chapter = SourceChapterId(book, "1")
            assertEquals("Same title", local.getBookInformation(book.storageKey)!!.title)
            assertEquals(chapter.storageKey, local.getBookVolumes(book.storageKey)!!.volumes.single().chapters.first().id)
            assertTrue(local.getChapterContent(chapter.storageKey)!!.content.toString().contains("${book.sourceId.id}:body:1"))
            val reading = local.getUserReadingData(book.storageKey)
            assertEquals(chapter.storageKey, reading.lastReadChapterId)
            assertEquals(fraction, reading.currentChapterReadingProgressMap[chapter.storageKey])
        }
        assertEquals(3, stats.getTotalReadingSummary().totalMinutes)
        assertEquals(2, stats.getTotalReadingSummary().totalReadCount)
        val records = db.bookRecordDao().getAllBookRecords()
        assertEquals(mapOf(a.storageKey to 60, b.storageKey to 120), records.associate { it.bookId to it.seconds })
        assertTrue(records.all { it.reads == 1 })
    }

    @Test fun sameNumberBooksBrowseSearchCacheExportReopenRestoreAndSurviveSourceRemoval() = runBlocking {
        val sources = listOf(register(a), register(b))
        assertTrue(sources.all { it.loads.get() == 0 })
        val explore = ExploreRepository(manager.registry)
        shelves.addBookshelf(Bookshelf(id = 1, name = "Mixed", systemUpdateReminder = true))
        for ((book, fraction) in listOf(a to 0.25f, b to 0.75f)) {
            val runtime = (manager.registry.resolve(book.sourceId) as SourceResolution.Ready).runtime
            val discovery = requireNotNull(runtime.discovery)
            assertEquals(book, discovery.feed().get()!!.single().books.single().id)
            val category = discovery.categories().get()!!.single()
            assertEquals(book, discovery.open(category.target).loadMore().get()!!.books.single().id)
            val search = explore.open(book.sourceId).get()!!
            assertEquals(book.storageKey, (search.search(search.types.single(), "Same title").single() as SearchResult.MultipleBook).bookId)
            shelves.addBookIntoBookShelf(1, books.getBookInformationFlow(book).last().get()!!)
            books.updateUserReadingData(book.storageKey) {
                it.copyWithUpdatedChapterReadingProgress("1", fraction).copy(lastReadChapterId = "1")
            }
            stats.updateReadingStatistics(ReadingStatsUpdate(book.storageKey,
                secondDelta = if (book == a) 60 else 120, readEventDelta = 1, localTime = LocalTime.NOON))
        }
        val cacheA = async {
            val work = books.cacheBook(a.storageKey)
            allowNetwork(a)
            withTimeout(30_000) { work.filterNotNull().first { it.state.isFinished } }
        }
        val cacheB = async {
            val work = books.cacheBook(b.storageKey)
            allowNetwork(b)
            withTimeout(30_000) { work.filterNotNull().first { it.state.isFinished } }
        }
        val completed = listOf(cacheA.await(), cacheB.await())
        assertTrue(completed.all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(2, completed.map { it.id }.distinct().size)
        assertNotEquals(CacheBookWork.ofId(a.storageKey), CacheBookWork.ofId(b.storageKey))
        assertLocalLibrary()
        for (book in listOf(a, b)) {
            val request = OneTimeWorkRequestBuilder<ExportBookToEPUBWork>().setInputData(workDataOf(
                "bookId" to book.storageKey, "exportType" to "BOOK")).build()
            val exportedWork = awaitWork(request)
            assertEquals("${exportedWork.outputData}: ${org.robolectric.shadows.ShadowLog.getLogsForTag("ExportEPUB")}",
                WorkInfo.State.SUCCEEDED, exportedWork.state)
            ZipFile(indi.renakoni.nextvol.data.work.EpubShareFiles.files(context, exportedWork.id).single()).use { zip ->
                val text = zip.entries().asSequence().filter { it.name.endsWith(".xhtml") }
                    .joinToString { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertTrue(text.contains("${book.sourceId.id}:body:1"))
                assertFalse(text.contains("${if (book == a) b.sourceId.id else a.sourceId.id}:body:1"))
            }
        }
        val userData = UserDataRepository(db.userDataDao())
        userData.stringListUserData(UserDataPath.ReadingBooks.path).set(listOf(a.storageKey, b.storageKey))
        val exported = Cbor.decodeFromByteArray<AppLocalData>(Cbor.encodeToByteArray(backup.exportAppLocalData().get()!!))
        manager.registry.sources.value.forEach { manager.unregisterWebDataSource(it.metadata.id) }
        progress.close()
        db.close()
        openHost() // Disk Room survives a new registry/repository graph; no global source is restored.
        assertLocalLibrary()
        backup.cleanDatabaseWithoutGlobalUserData()
        assertNull(local.getBookInformation(a.storageKey))
        assertTrue(backup.importAppLocalData(exported).isOk)
        assertLocalLibrary()
        assertEquals(listOf(a.storageKey, b.storageKey), UserDataRepository(db.userDataDao())
            .stringListUserData(UserDataPath.ReadingBooks.path).get())

        val remaining = register(b).apply { updated = updated.plusDays(1) }
        // A stays readable from its cache, but an uncached A request must not visit B.
        val cached = books.getChapterContentFlow(SourceChapterId(a, "1").storageKey, a.storageKey).last().get()!!
        assertTrue(cached.content.toString().contains("a:body:1"))
        val missing = books.getChapterContentFlow(SourceChapterId(a, "missing").storageKey, a.storageKey).last().getError()!!
        assertEquals(WebRequestErrorKind.SourceUnavailable, missing.kind)
        assertEquals(0, remaining.chapters.get())
        val update = awaitWork(OneTimeWorkRequestBuilder<CheckUpdateWork>().build())
        assertEquals(WorkInfo.State.SUCCEEDED, update.state)
        assertEquals(1, update.outputData.getInt("failedCount", -1))
        assertEquals(1, update.outputData.getInt("updatedCount", -1))
        assertEquals(listOf(b.storageKey), shelves.getBookshelf(1)!!.updatedBookIds)
        assertLocalLibrary()
    }

    @Test fun removingTheOwningSourceDuringRefreshReturnsUnavailableWithoutCancellingTheCaller() = runBlocking {
        val source = register(a)
        val other = register(b)
        val started = CompletableDeferred<Unit>()
        source.beforeInformation = { started.complete(Unit); awaitCancellation() }
        val request = async { books.refreshBookInformation(a) }
        started.await()
        manager.unregisterWebDataSource(a.sourceId)
        val failure = withTimeout(3000) { request.await() }.getError()!!
        assertEquals(WebRequestErrorKind.SourceUnavailable, failure.kind)
        assertTrue(isActive)
        assertEquals(0, other.loads.get())
        assertEquals(0, other.information.get())
    }

    @Test fun cachedInformationSurvivesRemovalDuringRefresh() = runBlocking {
        val source = register(a)
        val cached = books.getBookInformationFlow(a).last().get()!!
        val started = CompletableDeferred<Unit>()
        source.beforeInformation = { started.complete(Unit); awaitCancellation() }
        val request = async { books.getBookInformationFlow(a).toList() }
        started.await()
        manager.unregisterWebDataSource(a.sourceId)
        assertEquals(listOf(Ok(cached)), withTimeout(3000) { request.await() })
        assertEquals(cached, local.getBookInformation(a.storageKey))
    }

    @Test fun cancellingTheCallerDoesNotBecomeAnUnavailableResultOrRetireTheSource() = runBlocking {
        val source = register(a)
        val started = CompletableDeferred<Unit>()
        source.beforeInformation = { started.complete(Unit); awaitCancellation() }
        val request = async { books.refreshBookInformation(a) }
        started.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(manager.registry.resolve(a.sourceId) is SourceResolution.Ready)
        assertTrue(isActive)
    }

    @Test fun manualSubmissionAcknowledgesTheQueueThenFetchesAndSavesRealChapters() = runBlocking {
        val source = register(a)
        assertEquals(SourceStatus.Registered, manager.registry.sources.value.single().status)
        val first = books.submitDownload(a.storageKey) as DownloadSubmission.Accepted
        assertFalse(first.existing)
        assertEquals(DownloadTaskStatus.Queued, first.task.status)
        assertEquals(0, source.chapters.get())
        val duplicate = books.submitDownload(a.storageKey) as DownloadSubmission.Accepted
        assertTrue(duplicate.existing)
        assertEquals(first.workId, duplicate.workId)
        allowNetwork(a)
        val finished = withTimeout(30_000) {
            workManager.getWorkInfoByIdFlow(first.workId).filterNotNull().first { it.state.isFinished }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(2, source.chapters.get())
        assertNotNull(local.getChapterContent(BookIdentity.chapter("1", a).storageKey))
        assertNotNull(local.getChapterContent(BookIdentity.chapter("2", a).storageKey))
        assertEquals(2, books.downloadStatusFlow(a.storageKey).first().content.savedChapters)
    }

    @Test fun selectedDownloadFetchesOnlyItsChaptersAndDoesNotClaimTheWholeBook() = runBlocking {
        val source = register(a)
        val result = books.submitDownload(a.storageKey, chapterIds = listOf("2")) as DownloadSubmission.Accepted
        allowNetwork(a)
        val finished = withTimeout(30_000) {
            workManager.getWorkInfoByIdFlow(result.workId).filterNotNull().first { it.state.isFinished }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(1, source.chapters.get())
        assertNull(local.getChapterContent(BookIdentity.chapter("1", a).storageKey))
        assertNotNull(local.getChapterContent(BookIdentity.chapter("2", a).storageKey))
        val status = books.downloadStatusFlow(a.storageKey).first()
        assertEquals(DownloadTaskStatus.Complete, status.task.status)
        assertEquals(1, status.content.taskSavedChapters)
        assertEquals(1, status.content.taskTotalChapters)
        assertEquals(2, status.content.totalChapters)
        assertEquals(indi.renakoni.nextvol.data.download.BookDownloadPhase.Partial, status.content.phase)
        assertFalse(books.getIsBookCached(a.storageKey))
    }

    @Test fun automaticBookshelfDownloadAfterCancelledSelectionUsesTheWholeBook() = runBlocking {
        val source = register(a)
        val selected = books.submitDownload(a.storageKey, chapterIds = listOf("2")) as DownloadSubmission.Accepted
        books.dismissDownload(a.storageKey)
        shelves.addBookshelf(Bookshelf(id = 1, name = "Automatic", autoCache = true))
        shelves.addBookIntoBookShelf(1, books.getBookInformationFlow(a).last().get()!!)
        allowNetwork(a)
        val owner = downloads.entry(a)!!
        assertNotEquals(selected.workId.toString(), owner.taskWorkId)
        assertEquals("", owner.taskChapterIds)
        val finished = withTimeout(30_000) {
            workManager.getWorkInfoByIdFlow(java.util.UUID.fromString(owner.taskWorkId))
                .filterNotNull().first { it.state.isFinished }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertEquals(2, source.chapters.get())
        assertNotNull(local.getChapterContent(BookIdentity.chapter("1", a).storageKey))
        assertNotNull(local.getChapterContent(BookIdentity.chapter("2", a).storageKey))
    }

    @Test fun differentSelectionCannotReplaceAnActiveTaskAndResumeKeepsItsScope() = runBlocking {
        register(a)
        val first = books.submitDownload(a.storageKey, chapterIds = listOf("2")) as DownloadSubmission.Accepted
        val different = books.submitDownload(a.storageKey, chapterIds = listOf("1")) as DownloadSubmission.Accepted
        assertEquals(first.workId, different.workId)
        assertFalse(different.selectionMatches)
        books.dismissDownload(a.storageKey)
        val resumed = books.submitDownload(a.storageKey) as DownloadSubmission.Accepted
        assertFalse(resumed.existing)
        allowNetwork(a)
        val finished = withTimeout(30_000) {
            workManager.getWorkInfoByIdFlow(resumed.workId).filterNotNull().first { it.state.isFinished }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
        assertNull(local.getChapterContent(BookIdentity.chapter("1", a).storageKey))
        assertNotNull(local.getChapterContent(BookIdentity.chapter("2", a).storageKey))
    }

    @Test fun missingSelectedChapterFailsWithoutDownloadingUnselectedChapters() = runBlocking {
        val source = register(a)
        val result = books.submitDownload(a.storageKey, chapterIds = listOf("no-longer-present")) as DownloadSubmission.Accepted
        allowNetwork(a)
        val finished = withTimeout(30_000) {
            workManager.getWorkInfoByIdFlow(result.workId).filterNotNull().first { it.state.isFinished }
        }
        assertEquals(WorkInfo.State.FAILED, finished.state)
        assertEquals(0, source.chapters.get())
        assertEquals(DownloadFailure.SelectionUnavailable.name, downloads.entry(a)?.taskError)
    }

    @Test fun manualSubmissionRejectsAnUnavailableSourceWithoutCreatingATask() = runBlocking {
        assertEquals(DownloadSubmission.Rejected(DownloadFailure.SourceUnavailable), books.submitDownload(a.storageKey))
        assertTrue(downloads.entries().isEmpty())
    }

    @Test fun manualSubmissionDuringBackoffReturnsTheExistingDeadlineWithoutRestarting() = runBlocking {
        register(a)
        val first = books.submitDownload(a.storageKey) as DownloadSubmission.Accepted
        val deadline = System.currentTimeMillis() + 60_000
        downloads.deferTaskRetry(BookDownloadStore.Task(a, downloads.generation(), first.workId.toString()),
            0, deadline, DownloadFailure.Network)
        val repeated = books.submitDownload(a.storageKey) as DownloadSubmission.Accepted
        assertTrue(repeated.existing)
        assertEquals(first.workId, repeated.workId)
        assertEquals(DownloadTaskStatus.WaitingRetry, repeated.task.status)
        assertEquals(deadline, repeated.task.nextAttemptAt)
    }

    @Test fun failedSubmissionDoesNotLeaveAPersistedTaskPermanentlyQueued() = runBlocking {
        val failing = mockk<WorkManager>()
        every { failing.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        every { failing.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } throws IllegalStateException("submission failed")
        val scheduler = BookDownloadScheduler(downloads, failing, local.aliases)
        assertEquals(DownloadSubmission.Rejected(DownloadFailure.Scheduling), scheduler.submit(a))
        val task = checkNotNull(downloads.entry(a))
        assertEquals(DownloadTaskStatus.Failed.name, task.taskStatus)
        assertEquals(DownloadFailure.Scheduling.name, task.taskError)
    }

    @Test fun eagerDuplicateSubmissionsKeepOnePersistentTaskAndOneExecutor() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.beforeInformation = { entered.complete(Unit); release.await() }
        val first = books.cacheBook(a.storageKey) // Starts even before a collector attaches.
        try {
            allowNetwork(a)
            withTimeout(30_000) { entered.await() }
            val second = books.cacheBook(a.storageKey)
            val one = withTimeout(30_000) { first.filterNotNull().first() }
            val two = withTimeout(30_000) { second.filterNotNull().first() }
            assertEquals(one.id, two.id)
            assertEquals(one.id.toString(), downloads.entry(a)!!.taskWorkId)
            assertEquals(1, downloads.entries().size)
            assertEquals(1, source.information.get())
            release.complete(Unit)
            val finished = withTimeout(30_000) { first.filterNotNull().first { it.state.isFinished } }
            assertEquals(WorkInfo.State.SUCCEEDED, finished.state)
            assertEquals(indi.renakoni.nextvol.data.download.BookDownloadPhase.Complete,
                books.downloadStatusFlow(a.storageKey).first().content.phase)
        } finally { release.complete(Unit) }
    }

    @Test fun cancellingAPersistedTaskRevokesItAndExplicitRetryCreatesANewExecutor() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.beforeInformation = { entered.complete(Unit); release.await() }
        val first = books.cacheBook(a.storageKey)
        try {
            allowNetwork(a)
            withTimeout(30_000) { entered.await() }
            val old = downloads.entry(a)!!
            books.dismissDownload(a.storageKey)
            assertEquals(WorkInfo.State.CANCELLED, withTimeout(30_000) { first.filterNotNull().first { it.state.isFinished } }.state)
            assertTrue(downloads.entry(a)!!.taskHidden)
            source.beforeInformation = null
            val retry = books.cacheBook(a.storageKey)
            val queued = withTimeout(30_000) { retry.filterNotNull().first() }
            assertNotEquals(old.taskWorkId, queued.id.toString())
            allowNetwork(a)
            assertFalse(downloads.entry(a)!!.taskHidden)
            assertTrue(runCatching { downloads.finishTask(BookDownloadStore.Task(a, downloads.generation(), old.taskWorkId)) }
                .exceptionOrNull() is CancellationException)
            // A coalesced source request can outlive one caller. Only the new task may commit it.
            release.complete(Unit)
            val retried = withTimeout(30_000) { retry.filterNotNull().first { it.state.isFinished } }
            assertEquals(WorkInfo.State.SUCCEEDED, retried.state)
            assertEquals(queued.id.toString(), downloads.entry(a)!!.taskWorkId)
            assertEquals(indi.renakoni.nextvol.data.download.BookDownloadPhase.Complete,
                books.downloadStatusFlow(a.storageKey).first().content.phase)
        } finally { release.complete(Unit) }
    }

    private class FixtureSource(override val id: Identifier) : WebBookDataSource by EmptyWebDataSource {
        val loads = AtomicInteger()
        val information = AtomicInteger()
        val chapters = AtomicInteger()
        var updated: LocalDateTime = LocalDateTime.of(2026, 9, 10, 0, 0)
        var beforeInformation: (suspend () -> Unit)? = null
        override val cache = null
        override fun onLoad() { loads.incrementAndGet() }
        override suspend fun getBookInformation(id: String): com.github.michaelbull.result.Result<BookInformation, io.nightfish.lightnovelreader.api.error.WebRequestError> {
            information.incrementAndGet()
            beforeInformation?.invoke()
            return Ok(BookInformation(id, "Same title", author = "Same author", description = "", publishingHouse = "",
                wordCount = WordCount(2), lastUpdated = updated, isComplete = false))
        }
        override suspend fun getBookVolumes(id: String) = Ok(BookVolumes(id,
            listOf(Volume("volume", "Volume", listOf(ChapterInformation("1", "One"), ChapterInformation("2", "Two"))))))
        override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, io.nightfish.lightnovelreader.api.error.WebRequestError> {
            chapters.incrementAndGet()
            return Ok(ChapterContent(chapterId, "Chapter", ContentBuilder().simpleText("${id.id}:body:$chapterId").build()))
        }
        override val searchProvider = object : SearchProvider {
            override val searchTypes = listOf(SearchType("keyword", LocalString("Keyword"), LocalString("Search")))
            override fun search(searchType: SearchType, keyword: String) = flowOf(SearchResult.MultipleBook("123"))
        }
        override val discoveryProvider = object : DiscoveryProvider {
            override val hasFeed = true
            override val hasCategories = true
            private val book = DiscoveryBook("123", "Same title", "Same author")
            override suspend fun feed() = Ok(listOf(DiscoverySection("all", "All", listOf(book))))
            override suspend fun categories() = Ok(listOf(DiscoveryCategory("all", "All", "all")))
            override suspend fun page(request: DiscoveryRequest) = Ok(DiscoveryPage(listOf(book)))
        }
    }
}
