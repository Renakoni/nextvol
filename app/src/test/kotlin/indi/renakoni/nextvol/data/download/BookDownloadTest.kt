package indi.renakoni.nextvol.data.download

import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.*
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.LocalDataManager
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.image.SourceImageProvider
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okio.Path.Companion.toPath
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime

/** Real Room, source response cache, source images and download worker; no external site needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class BookDownloadTest {
    companion object { const val IMAGE = "https://fixture.invalid/illustration.png" }
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = directory.root.resolve("files").apply { mkdirs() }
    } }
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")
    private val registry = WebSourceRegistry()
    private val decoder = ContentJsonDecoder(ContentComponentRegistry())
    private lateinit var db: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var downloads: BookDownloadStore
    private lateinit var books: BookRepository
    private lateinit var workManager: androidx.work.WorkManager
    private lateinit var cache: DiskCache
    private lateinit var loader: ImageLoader
    private val progress = mockk<DownloadProgressRepository>(relaxed = true)
    private val png by lazy { ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray() }

    @Test @Config(sdk = [24, 35])
    fun foregroundRestrictionIsDurableAndDoesNotSpendAnotherRetry() = kotlinx.coroutines.runBlocking {
        val id = java.util.UUID.randomUUID()
        downloads.queueTask(a, 0, id.toString())
        val task = downloads.startTask(a, 0, id.toString(), 0)
        downloads.deferTaskRetry(task, 0, 0, DownloadFailure.Network)
        val repository = mockk<BookRepository>()
        io.mockk.coEvery { repository.canonicalBook(a) } returns a
        every { repository.downloadSource(a) } returns null
        io.mockk.coEvery { repository.canReplayDownload(a) } returns false
        io.mockk.coEvery { repository.canDownloadConcurrently(a) } returns false
        val params = indi.renakoni.nextvol.data.work.workerParameters(
            androidx.work.workDataOf("bookId" to a.storageKey, "persistedTask" to true), id)
        every { params.foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns
            com.google.common.util.concurrent.Futures.immediateFailedFuture(IllegalStateException("Foreground start blocked"))
        val worker = CacheBookWork(context, params, progress, repository, downloads)
        val foreground = worker.getForegroundInfo()
        assertEquals(if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            foreground.foregroundServiceType)
        assertNotNull(foreground.notification.actions.single().actionIntent)
        assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Failure)
        io.mockk.coVerify(exactly = 0) { repository.refreshBookInformation(a, fresh = true) }
        db.close()
        openLibrary()
        val owner = downloads.entry(a)!!
        assertEquals(DownloadTaskStatus.Interrupted.name, owner.taskStatus)
        assertEquals(DownloadFailure.SystemRestricted.name, owner.taskError)
        assertEquals(1, owner.taskRetryCount)
        assertEquals(id.toString(), owner.taskWorkId)
        assertTrue(owner.taskState(androidx.work.WorkInfo.State.FAILED).canResume)
    }

    @Before fun setUp() {
        openLibrary()
        cache = DiskCache.Builder().directory(directory.root.resolve("coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
        openImages()
    }

    private fun openLibrary() {
        db = Room.databaseBuilder(context, NextVolDatabase::class.java, directory.root.resolve("library.db").path)
            .addMigrations(NextVolDatabase.MIGRATION_17_18, NextVolDatabase.MIGRATION_18_19,
                NextVolDatabase.MIGRATION_19_20, NextVolDatabase.MIGRATION_20_21, NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24, NextVolDatabase.MIGRATION_24_25, NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27, NextVolDatabase.MIGRATION_27_28, NextVolDatabase.MIGRATION_28_29).allowMainThreadQueries().build()
        local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
        downloads = BookDownloadStore(context, db, decoder)
        val text = TextProcessingRepository(mockk { every { enabled } returns false },
            mockk { every { enabled } returns false }, ContentComponentRegistry())
        workManager = mockk<androidx.work.WorkManager>(relaxed = true) {
            every { getWorkInfoByIdFlow(any()) } returns flowOf(null)
            every { getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        }
        val scheduler = BookDownloadScheduler(downloads, workManager, local.aliases)
        val shelves = BookshelfRepository(db.bookshelfDao(), scheduler, registry, local.aliases)
        books = BookRepository(local, shelves, text, workManager, ChapterRepository(registry, local, text, mockk(), downloads),
            BookReadingDataRepository(local), registry, downloads, mockk(), scheduler)
    }

    private fun openImages() {
        loader = ImageLoader.Builder(context).diskCache(cache).components {
            add(SourceImageInterceptor(registry, context, downloads))
            add(SourceImageFetcher.Factory())
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun close() = runBlocking {
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close()
    }

    private fun register(book: SourceBookId, revision: String = "1", source: Remote = Remote(book), accountGeneration: Long = 0) = source.also {
        registry.register(it, SourceMetadata(WebDataSourceItem(book.sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent),
            revision = revision, accountGeneration = accountGeneration))
    }

    private suspend fun download(book: SourceBookId = a, generation: Long = downloads.generation()) =
        CacheBookWork(context, workerParameters(workDataOf("bookId" to book.storageKey, "downloadGeneration" to generation)),
            progress, books, downloads).doWork()

    private suspend fun submittedDownload(book: SourceBookId): ListenableWorker.Result {
        val id = java.util.UUID.randomUUID()
        downloads.queueTask(book, downloads.generation(), id.toString())
        return CacheBookWork(context, workerParameters(workDataOf("bookId" to book.storageKey,
            "downloadGeneration" to downloads.generation(), "persistedTask" to true), id), progress, books, downloads).doWork()
    }

    @Test fun httpReuseAndRepeatDownloadsKeepTheDownloadedVersion() = runBlocking {
        hnovel.content.RuleSourceFixture().use { fixture ->
            var body = "old body"
            val bodyCalls = java.util.concurrent.atomic.AtomicInteger()
            val imageCalls = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val response = okhttp3.mockwebserver.MockResponse()
                    return when (request.path) {
                        "/book/one" -> response.setBody("<h1>Book</h1><a class='toc' href='/toc/1'>toc</a>")
                        "/toc/1" -> response.setBody("<li><a href='/c/1'>One</a></li>")
                        "/c/1" -> { bodyCalls.incrementAndGet(); response.setBody("<article><p>$body</p><img src='/image.png'></article>") }
                        "/image.png" -> { imageCalls.incrementAndGet(); response.setBody(okio.Buffer().write(png)) }
                        else -> response.setResponseCode(404)
                    }
                }
            }
            val rule = fixture.source(customize = { raw ->
                kotlinx.serialization.json.JsonObject(raw + mapOf(
                    "ruleToc" to kotlinx.serialization.json.buildJsonObject {
                        put("chapterList", kotlinx.serialization.json.JsonPrimitive("li"))
                        put("chapterName", kotlinx.serialization.json.JsonPrimitive("a@text"))
                        put("chapterUrl", kotlinx.serialization.json.JsonPrimitive("a@href"))
                    },
                    "ruleContent" to kotlinx.serialization.json.buildJsonObject {
                        put("content", kotlinx.serialization.json.JsonPrimitive("article@html"))
                    }))
            })
            registry.register(indi.renakoni.nextvol.data.web.rules.RuleWebBookDataSource(a.sourceId, rule),
                SourceMetadata(WebDataSourceItem(a.sourceId, "Fixture", "fixture"),
                    setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent)))
            val book = SourceBookId(a.sourceId, fixture.server.url("/book/one").toString())
            val id = SourceChapterId(book, fixture.server.url("/c/1").toString()).storageKey
            val image = SourceImage(book, fixture.server.url("/image.png").toString(), chapterId = id)
            books.getBookVolumesFlow(book.storageKey).toList()
            books.preloadChapterContent(id, book.storageKey)
            val afterPreload = fixture.server.requestCount
            assertEquals(1, bodyCalls.get())
            assertTrue(books.getChapterContentFlow(id, book.storageKey).toList().last().get()!!.content.toString().contains("old body"))
            assertEquals(afterPreload, fixture.server.requestCount)
            assertEquals(ListenableWorker.Result.success(), submittedDownload(book))
            assertEquals(1, bodyCalls.get()) // Promote validated reading content; only fetch its image.
            assertEquals(1, imageCalls.get())
            body = "new body"
            assertEquals(ListenableWorker.Result.success(), submittedDownload(book))
            assertEquals(1, bodyCalls.get()) // Downloaded chapters are final; a later run fetches nothing.
            assertEquals(1, imageCalls.get())
            assertArrayEquals(png, downloads.image(image)!!.readBytes())
            registry.unregister(book.sourceId)
            val beforeOffline = fixture.server.requestCount
            assertTrue(books.getChapterContentFlow(id, book.storageKey).toList().single().get()!!.content.toString().contains("old body"))
            assertTrue(books.exportChapter(book, id).get()!!.content.toString().contains("old body"))
            assertTrue(export(book = book) is ListenableWorker.Result.Success)
            assertEquals(beforeOffline, fixture.server.requestCount)
        }
    }

    @Test fun readingCacheSurvivesAccountChangeButRevalidatesUnknownAndChangedRules() = runBlocking {
        val source = register(a)
        val id = SourceChapterId(a, "1").storageKey
        books.preloadChapterContent(id, a.storageKey)
        assertEquals(mapOf("1" to 1), source.chapterCalls)
        registry.unregister(a.sourceId)
        register(a, source = source, accountGeneration = 1)
        books.getChapterContentFlow(id, a.storageKey).toList()
        books.preloadChapterContent(id, a.storageKey)
        assertEquals(mapOf("1" to 1), source.chapterCalls)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source, accountGeneration = 1)
        books.getChapterContentFlow(id, a.storageKey).toList()
        assertEquals(mapOf("1" to 2), source.chapterCalls)
        assertEquals("2", db.chapterContentDao().get(id)!!.sourceRevision)
        local.updateChapterContent(local.getChapterContent(id)!!) // Legacy/unverified cache.
        assertNull(local.getReusableChapterContent(id, "2"))
        books.getChapterContentFlow(id, a.storageKey).toList()
        assertEquals(mapOf("1" to 3), source.chapterCalls)
    }

    @Test fun room27UpgradeKeepsDownloadsAndDoesNotInventTrustedReadingCache() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val cached = SourceChapterId(a, "cached").storageKey
        db.chapterContentDao().cache(ChapterContent(cached, "Cached", ContentBuilder().simpleText("saved").build()), "1")
        db.openHelper.writableDatabase.apply {
            restorePre28ChapterSchema(this::execSQL)
            execSQL("ALTER TABLE book_download RENAME TO refresh_download")
            execSQL("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL, taskWorkId TEXT NOT NULL DEFAULT '', taskStatus TEXT NOT NULL DEFAULT 'None', taskStage TEXT NOT NULL DEFAULT 'Unknown', taskChapter TEXT NOT NULL DEFAULT '', taskError TEXT NOT NULL DEFAULT '', taskRunAttempt INTEGER NOT NULL DEFAULT 0, taskHidden INTEGER NOT NULL DEFAULT 0, taskRetryCount INTEGER NOT NULL DEFAULT 0, taskNextAttemptAt INTEGER NOT NULL DEFAULT 0, taskSourceRevision TEXT NOT NULL DEFAULT '', taskAccountGeneration INTEGER NOT NULL DEFAULT -1)")
            execSQL("INSERT INTO book_download SELECT bookId, revision, directoryHash, phase, generation, attempt, coverUri, taskWorkId, taskStatus, taskStage, taskChapter, taskError, taskRunAttempt, taskHidden, taskRetryCount, taskNextAttemptAt, taskSourceRevision, taskAccountGeneration FROM refresh_download")
            execSQL("DROP TABLE refresh_download")
            version = 27
        }
        db.close(); openLibrary()
        assertEquals("", db.chapterContentDao().get(cached)!!.sourceRevision)
        assertNotNull(local.getChapterContent(cached))
        assertNull(local.getReusableChapterContent(cached, "1"))
        assertEquals("", downloads.entry(a)!!.taskRefreshId)
        assertEquals("", downloads.entry(a)!!.taskChapterIds)
        assertEquals("{}", downloads.entry(a)!!.taskChapterFailures)
        books.getChapterContentFlow(SourceChapterId(a, "1").storageKey, a.storageKey).toList()
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
    }

    private suspend fun state(book: SourceBookId = a) = books.downloadState(book.storageKey)
    private suspend fun chapter(book: SourceBookId, id: String) = local.getChapterContent(SourceChapterId(book, id).storageKey)

    private fun captureQueuedDownloads(): List<androidx.work.OneTimeWorkRequest> {
        val requests = mutableListOf<androidx.work.OneTimeWorkRequest>()
        val completion = androidx.concurrent.futures.ResolvableFuture.create<androidx.work.Operation.State.SUCCESS>()
            .apply { set(androidx.work.Operation.SUCCESS) }
        every { workManager.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) } answers {
            requests += thirdArg<androidx.work.OneTimeWorkRequest>()
            mockk<androidx.work.Operation> { every { result } returns completion }
        }
        return requests
    }

    @Test fun verificationWaitSurvivesReconstructionAndResumesOnlyMissingChapters() = runBlocking {
        val source = Remote(a)
        var coordinator = indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator(registry)
        val owner = indi.renakoni.nextvol.data.web.rules.VerificationOwner(a.sourceId, "1", 0)
        var verified = false
        val verification = mockk<hnovel.content.SourceVerification> {
            every { kind } returns hnovel.network.BrowserChallengeKind.Login
            every { certificate } returns null
            every { origin } returns "https://fixture.invalid/"
            coEvery { complete() } coAnswers { verified = true }
        }
        val challenge = hnovel.content.SourceContentException(hnovel.content.ContentError.BrowserRequired,
            "chapter", verification = verification)
        registry.register(object : WebBookDataSource by source {
            override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, WebRequestError> = try {
                coordinator.execute(owner, "Fixture") {
                    if (chapterId == "2" && !verified) throw challenge
                    source.getChapterContent(chapterId, bookId)
                }
            } catch (error: hnovel.content.SourceContentException) {
                Err(WebRequestError("Verification", "Required", error,
                    kind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired))
            }
        }, SourceMetadata(WebDataSourceItem(a.sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent), revision = "1"))
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(DownloadTaskStatus.WaitingVerification.name, downloads.entry(a)!!.taskStatus)
        assertNotNull(chapter(a, "1"))
        db.close(); openLibrary()
        coordinator = indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator(registry)
        assertTrue(coordinator.prompts.value.isEmpty())
        assertEquals(DownloadTaskStatus.WaitingVerification, books.downloadStatusFlow(a.storageKey).first().task.status)
        val queued = captureQueuedDownloads()
        books.cacheBook(a.storageKey).first() // The persistent card recreates the challenge after process reconstruction.
        suspend fun runQueued() = queued.last().let { request -> CacheBookWork(context,
            workerParameters(request.workSpec.input, request.id), progress, books, downloads).doWork() }
        assertTrue(runQueued() is ListenableWorker.Result.Failure)
        val prompt = coordinator.prompts.value.single().id
        coordinator.verifyBackground(prompt)
        coordinator.verifyBackground(prompt)
        assertEquals(2, queued.size)
        assertEquals(DownloadTaskStatus.Queued.name, downloads.entry(a)!!.taskStatus)
        assertEquals(ListenableWorker.Result.success(), runQueued())
        assertEquals(1, source.chapterCalls["1"])
        assertEquals(DownloadTaskStatus.Complete.name, downloads.entry(a)!!.taskStatus)
    }

    @Test fun imageVerificationResumesTheWaitingDownload() = runBlocking {
        val source = Remote(a).apply { withImages = true }
        val coordinator = indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator(registry)
        val owner = indi.renakoni.nextvol.data.web.rules.VerificationOwner(a.sourceId, "1", 0)
        var verified = false
        val verification = mockk<hnovel.content.SourceVerification> {
            every { kind } returns hnovel.network.BrowserChallengeKind.Login
            every { certificate } returns null
            every { origin } returns "https://fixture.invalid/"
            coEvery { complete() } coAnswers { verified = true }
        }
        val challenge = hnovel.content.SourceContentException(hnovel.content.ContentError.BrowserRequired,
            "image", verification = verification)
        registry.register(object : WebBookDataSource by source, SourceImageProvider {
            override suspend fun getImage(bookId: String, url: String, cover: Boolean): com.github.michaelbull.result.Result<ByteArray, WebRequestError> = try {
                coordinator.execute(owner, "Fixture") {
                    if (!verified) throw challenge
                    source.getImage(bookId, url, cover)
                }
            } catch (error: hnovel.content.SourceContentException) {
                Err(WebRequestError("Verification", "Required", error,
                    kind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired))
            }
        }, SourceMetadata(WebDataSourceItem(a.sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent), revision = "1"))
        val queued = captureQueuedDownloads()
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(DownloadTaskStatus.WaitingVerification.name, downloads.entry(a)!!.taskStatus)
        coordinator.verifyBackground(coordinator.prompts.value.single().id)
        assertTrue(verified)
        assertEquals(1, queued.size)
        val request = queued.single()
        assertEquals(ListenableWorker.Result.success(), CacheBookWork(context,
            workerParameters(request.workSpec.input, request.id), progress, books, downloads).doWork())
        assertEquals(DownloadTaskStatus.Complete.name, downloads.entry(a)!!.taskStatus)
    }

    @Test fun verificationSnapshotsIgnoreCancelledReplacedAndRetiredTasksWithoutDeletingContent() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val other = SourceBookId(a.sourceId, "other")
        suspend fun waiting(book: SourceBookId): String {
            val id = java.util.UUID.randomUUID().toString()
            downloads.queueTask(book, downloads.generation(), id)
            val task = downloads.startTask(book, downloads.generation(), id, 0)
            downloads.bindTaskSource(task, "1", 0)
            downloads.finishTask(task, DownloadFailure.Verification)
            return id
        }
        val id = waiting(a)
        waiting(other)
        val resume = checkNotNull(books.prepareDownloadVerification(a, id, "1", 0))
        downloads.dismissTask(a)
        val newer = waiting(other)
        val queued = captureQueuedDownloads()
        resume()
        assertTrue(queued.isEmpty())
        assertEquals(DownloadTaskStatus.Cancelled.name, downloads.entry(a)!!.taskStatus)
        assertEquals(newer, downloads.entry(other)!!.taskWorkId)
        assertNotNull(chapter(a, "1"))
        val afterAccountChange = checkNotNull(books.prepareDownloadVerification(other, newer, "1", 0))
        registry.unregister(a.sourceId)
        registry.register(Remote(a), SourceMetadata(WebDataSourceItem(a.sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent),
            revision = "1", accountGeneration = 1))
        afterAccountChange()
        assertTrue(queued.isEmpty())
        assertNotNull(chapter(a, "1"))
        downloads.clearDownloads()
        assertFalse(downloads.queueVerifiedTask(indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(
            other.storageKey, taskWorkId = newer, taskStatus = DownloadTaskStatus.WaitingVerification.name), "stale-result"))
    }

    @Test fun downloadsSurviveReadingCacheClearSourceRemovalAndHostReconstruction() = runBlocking {
        for (book in listOf(a, b)) {
            register(book).withImages = true
            assertEquals(ListenableWorker.Result.success(), download(book))
        }
        val online = SourceBookId(a.sourceId, "online-only")
        local.updateChapterContent(SourceChapterId(online, "1").bind(ChapterContent("1", "Online", ContentBuilder().simpleText("temporary").build())))
        local.updateUserReadingData(a.storageKey) { it.copy(totalReadTime = 42) }
        downloads.clearReadingCache()
        assertNull(chapter(online, "1"))
        assertNotNull(chapter(a, "1")); assertNotNull(chapter(b, "1"))
        assertEquals(42, local.getUserReadingData(a.storageKey).totalReadTime)
        registry.unregister(a.sourceId); registry.unregister(b.sourceId)
        loader.shutdown(); db.close()
        openLibrary(); openImages()
        for (book in listOf(a, b)) {
            assertEquals(BookDownloadPhase.Complete, state(book).phase)
            val result = loader.execute(ImageRequest.Builder(context).data(SourceImage(book, IMAGE)).build())
            assertTrue("Downloaded image must decode after ordinary disk cache is emptied", result is SuccessResult)
            assertArrayEquals(png, downloads.image(SourceImage(book, IMAGE))!!.readBytes())
        }
        assertNotEquals(downloads.image(SourceImage(a, IMAGE)), downloads.image(SourceImage(b, IMAGE)))
    }

    @Test fun changedCatalogAndRetryFetchOnlyChaptersThatAreNotDownloaded() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.chapters = source.chapters.map { if (it.id == "2") it.copy(title = "Revised") else it } +
            ChapterInformation("4", "New") + ChapterInformation("5", "Newer")
        source.failedChapter = "5"
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        source.failedChapter = null
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1, "5" to 2), source.chapterCalls)
        assertEquals("Chapter 2", chapter(a, "2")!!.title)
        assertEquals(BookDownloadState(BookDownloadPhase.Complete, 5, 5), state())
        assertEquals(3, source.directoryCalls)
    }

    @Test fun remoteFailureAndEmptyDirectoryDoNotMasqueradeAsSuccessfulUpdates() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.directoryFailed = true
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        assertNotNull(chapter(a, "1"))
        source.directoryFailed = false
        source.chapters = emptyList()
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(3, local.getBookVolumes(a.storageKey)!!.volumes.single().chapters.size)
    }

    @Test fun failedIdentityPreflightRetainsOfflineDataButMarksTheUpdateFailed() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        source.informationFailed = true
        assertTrue(download() is ListenableWorker.Result.Failure)
        assertEquals(BookDownloadPhase.Failed, state().phase)
        assertNotNull(chapter(a, "1"))
        assertEquals(3, local.getBookVolumes(a.storageKey)!!.volumes.single().chapters.size)
    }

    @Test fun missingImagesAreRepairedWithoutRefetchingUnchangedBodies() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertEquals(ListenableWorker.Result.success(), download())
        val image = SourceImage(a, IMAGE)
        assertTrue(downloads.image(image)!!.delete())
        cache.clear()
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun laterDownloadsAndReadingRefreshKeepTheDownloadedBody() = runBlocking {
        val source = register(a).apply { withImages = true; extraImage = true }
        assertEquals(ListenableWorker.Result.success(), download())
        val before = chapter(a, "1")!!
        val imageCalls = source.imageCalls
        source.chapters = source.chapters.mapIndexed { index, chapter -> if (index == 0) chapter.copy(title = "Changed") else chapter }
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(imageCalls, source.imageCalls)
        assertEquals(before, chapter(a, "1"))
        local.updateChapterContent(before.copy(title = "Incomplete reading refresh"))
        assertEquals(before, chapter(a, "1"))
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE,
            chapterId = SourceChapterId(a, "1").storageKey))!!.readBytes())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun clearingDownloadsRevokesLateResultsAndPreUpgradeQueuedWork() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val old = async { download(generation = 0) }
        withTimeout(5000) { entered.await() }
        val online = SourceChapterId(b, "read")
        local.updateChapterContent(online.bind(ChapterContent("read", "Online", ContentBuilder().simpleText("keep").build())))
        downloads.clearDownloads()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { old.await() } is ListenableWorker.Result.Failure)
        assertNull(chapter(a, "1")); assertNotNull(chapter(b, "read"))
        assertEquals(BookDownloadPhase.None, state().phase)
        assertTrue(download(generation = 0) is ListenableWorker.Result.Failure)
        source.chapterPause = null
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun downloadedChaptersStayFinalAcrossSourceRevisionsAndNewChapters() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        source.chapters = source.chapters + ChapterInformation("4", "New")
        books.downloadDirectory(a)
        assertEquals(BookDownloadState(BookDownloadPhase.Partial, 3, 4), state())
        assertEquals(ListenableWorker.Result.success(), download())
        // Chapter 3 gained a next chapter; it is still not fetched again.
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1, "4" to 1), source.chapterCalls)
        assertEquals(BookDownloadState(BookDownloadPhase.Complete, 4, 4), state())
        source.volumeId = "replacement"
        source.chapters = listOf(ChapterInformation("4", "Only new chapter"))
        books.downloadDirectory(a)
        assertEquals(1, local.getBookVolumes(a.storageKey)!!.volumes.size)
        assertEquals(BookDownloadState(BookDownloadPhase.Complete, 1, 1), state())
        assertNotNull("Removed remote chapters remain available until download cleanup", chapter(a, "1"))
    }

    @Test fun backupRestoresOwnershipButNeverRunningAttemptsOrMissingImageCompleteness() = runBlocking {
        register(a).withImages = true
        assertEquals(ListenableWorker.Result.success(), download())
        val attempt = downloads.begin(a, downloads.generation(), "active-before-backup")
        val coordinator = StatisticsWriteCoordinator()
        val backup = LocalDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(), db.userReadingDataDao(),
            db.userDataDao(), mockk(relaxed = true), coordinator,
            StatsRepository(db.bookRecordDao(), db.dailyCountDao(), books, coordinator), downloads)
        val saved = backup.exportAppLocalData().get()!!
        val owner = saved.localDataList.single().bookDownloadEntities.single()
        assertEquals("", owner.attempt)
        assertTrue(saved.globalLocalData.userDataEntities.none { it.path.startsWith("hnovel/downloads/") })
        assertTrue(backup.exportCurrentLocalData(localBookCache = false).get()!!.bookDownloadEntities.isEmpty())
        backup.cleanDatabaseWithoutGlobalUserData()
        assertTrue(backup.importAppLocalData(saved).isOk)
        assertNotNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertEquals("", db.bookDownloadDao().get(a.storageKey)!!.attempt)
        try { downloads.finish(attempt, true); fail("Restored backup must not reactivate an old worker") }
        catch (_: CancellationException) { }
        assertEquals(ListenableWorker.Result.success(), download())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun removingOneBooksDownloadKeepsTheOtherSourceAndRejectsItsOldAttempt() = runBlocking {
        for (book in listOf(a, b)) {
            register(book).withImages = true
            assertEquals(ListenableWorker.Result.success(), download(book))
        }
        val old = downloads.begin(a, downloads.generation(), "old")
        downloads.removeBooks(listOf(a))
        assertNull(chapter(a, "1")); assertNull(downloads.image(SourceImage(a, IMAGE)))
        assertEquals(BookDownloadPhase.None, state(a).phase)
        assertEquals(BookDownloadPhase.Complete, state(b).phase)
        assertNotNull(downloads.image(SourceImage(b, IMAGE)))
        try { downloads.finish(old, true); fail("Removed book must reject its old worker") }
        catch (_: CancellationException) { }
        assertEquals(ListenableWorker.Result.success(), download(a))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun orphanCleanupRetainsDownloadedChaptersRemovedFromRemoteCatalog() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        local.updateBookVolumes(a.bind(BookVolumes(a.remoteId, listOf(Volume("new", "New",
            listOf(ChapterInformation("4", "Next")))))))
        local.updateChapterContent(SourceChapterId(b, "orphan").bind(ChapterContent("orphan", "Temporary",
            ContentBuilder().simpleText("temporary").build())))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val models = androidx.lifecycle.ViewModelStore()
        val model = indi.renakoni.nextvol.ui.bookmanager.BookManagerViewModel(
            books, progress, db, mockk(relaxed = true), mockk(relaxed = true), downloads, mockk(relaxed = true))
        models.put("manager", model)
        val job = model.viewModelScope.coroutineContext[Job]
        try {
            model.clearOrphanedDataItems()
            assertNotNull(chapter(a, "1"))
            assertNull(chapter(b, "orphan"))
        } finally {
            models.clear()
            withTimeout(5000) { job?.join() }
            Dispatchers.resetMain()
        }
    }

    @Test fun room17UpgradeKeepsOldDownloadsAndRetriesAnInterruptedImageMigration() = runBlocking {
        val source = register(a).apply { withImages = true }
        local.updateBookVolumes(a.bind(source.directory()))
        local.updateBookInformation(a.bind(source.information()))
        local.updateChapterContent(SourceChapterId(a, "1").bind(source.body("1")))
        val image = SourceImage(a, IMAGE)
        val key = sourceImageCacheKey(image, "1")
        cache.openEditor(key)!!.let { editor ->
            cache.fileSystem.write(editor.metadata) { }
            cache.fileSystem.write(editor.data) { write(png) }
            editor.commit()
        }
        context.getSharedPreferences("source_image_cache_keys", 0).edit()
            .putString(sourceImageCacheKey(image, ""), key).commit()
        db.userDataDao().insert(UserDataPath.CompletedDownloadBookList.path, "fixture", "CompletedDownloadItemList", "CACHE|${a.storageKey}")
        db.openHelper.writableDatabase.apply {
            restorePre28ChapterSchema(this::execSQL)
            execSQL("DROP TABLE downloaded_chapter"); execSQL("DROP TABLE book_download"); execSQL("DROP TABLE local_book_file_manifest"); execSQL("DROP TABLE imported_book")
            execSQL("DROP TABLE bangumi_binding"); execSQL("DROP TABLE bangumi_sync_record"); execSQL("DROP TABLE book_alias"); version = 17
        }
        db.close(); openLibrary()
        assertEquals(29, db.openHelper.writableDatabase.version)
        val blocked = File(context.filesDir, "book-downloads").apply { writeText("not a directory") }
        try { downloads.prepare(); fail("Image copy must fail before ownership is committed") }
        catch (_: java.io.IOException) { }
        assertNull(db.bookDownloadDao().get(a.storageKey))
        assertTrue(blocked.delete())
        downloads.prepare()
        downloads.clearReadingCache()
        assertNotNull(chapter(a, "1"))
        assertArrayEquals(png, downloads.image(image)!!.readBytes())
        // Migrated bytes are downloaded like any other chapter; later runs fetch only the rest.
        assertEquals(BookDownloadState(BookDownloadPhase.Partial, 1, 3), state())
    }

    @Test fun firstRequestFailuresStayVisibleAfterDatabaseReopenAndSourceRemoval() = runBlocking {
        for ((index, stage) in listOf(DownloadStage.Details, DownloadStage.Directory, DownloadStage.Body).withIndex()) {
            val book = SourceBookId(Identifier("fixture", "failure$index"), "same")
            register(book).apply {
                informationFailed = stage == DownloadStage.Details
                directoryFailed = stage == DownloadStage.Directory
                failedChapter = if (stage == DownloadStage.Body) "1" else null
            }
            assertTrue(download(book) is ListenableWorker.Result.Failure)
            registry.unregister(book.sourceId)
            db.close(); openLibrary()
            val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
            try {
                val item = awaitTask(repository, book)
                assertEquals(DownloadTaskStatus.Failed, item.status!!.task.status)
                assertEquals(stage, item.status!!.task.stage)
                assertEquals(DownloadFailure.SourceRequest, item.status!!.task.failure)
                assertEquals(0, item.status!!.content.savedChapters)
                assertEquals(books.downloadStatusFlow(book.storageKey).first(), item.status)
                if (stage == DownloadStage.Details) assertTrue(books.downloadInformationFlow(book.storageKey).first().isErr)
            } finally { repository.close() }
        }
    }

    @Test fun interruptedPartialTaskDoesNotInventAnExecutorAfterReopening() = runBlocking {
        val source = register(a)
        local.updateBookVolumes(a.bind(source.directory()))
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id)
        val task = downloads.startTask(a, downloads.generation(), id, 2)
        val attempt = downloads.begin(a, downloads.generation(), id)
        val volumes = a.bind(source.directory())
        downloads.target(attempt, volumes, "1", "")
        val chapters = volumes.volumes.flatMap { it.chapters }
        downloads.saveChapter(attempt, SourceChapterId(a, "1").bind(source.body("1")),
            downloadChapterSignature(chapters, 0, "1"), emptyList())
        downloads.taskStage(task, DownloadStage.Body, SourceChapterId(a, "2").storageKey)
        db.close(); openLibrary()
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            val state = awaitTask(repository, a).status!!
            assertEquals(DownloadTaskStatus.Interrupted, state.task.status)
            assertEquals(2, state.task.runAttemptCount)
            assertEquals(SourceChapterId(a, "2").storageKey, state.task.chapterId)
            assertEquals(BookDownloadState(BookDownloadPhase.Partial, 1, 3), state.content)
            assertTrue(state.task.canResume)
        } finally { repository.close() }
    }

    @Test fun dismissedTaskRejectsLateStagesAndWritesButKeepsSavedContent() = runBlocking {
        val source = register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id)
        val task = downloads.startTask(a, downloads.generation(), id, 0)
        val attempt = downloads.begin(a, downloads.generation(), id)
        downloads.dismissTask(a)
        assertTrue(runCatching { downloads.taskStage(task, DownloadStage.Body) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { downloads.finish(attempt, true) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { downloads.begin(a, downloads.generation(), id) }.exceptionOrNull() is CancellationException)
        assertNotNull(chapter(a, "1"))
        db.close(); openLibrary()
        assertTrue(downloads.entry(a)!!.taskHidden)
        val nextId = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), nextId)
        assertFalse(downloads.entry(a)!!.taskHidden)
        assertTrue(runCatching { downloads.startTask(a, downloads.generation(), id, 0) }.exceptionOrNull() is CancellationException)
        downloads.startTask(a, downloads.generation(), nextId, 0)
        val current = downloads.begin(a, downloads.generation(), nextId, requireTask = true)
        assertTrue(runCatching { downloads.begin(a, downloads.generation(), id, requireTask = true) }.exceptionOrNull() is CancellationException)
        downloads.finish(current, success = true)
        assertEquals(3, source.chapterCalls.size)
    }

    @Test fun clearingCompletedTaskHistoryDoesNotDeleteContentOrRestoreCards() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            assertEquals(1f, awaitTask(repository, a).progress)
            repository.clearCompleted()
            withTimeout(5000) { while (downloads.entry(a)?.taskHidden != true) delay(10) }
        } finally { repository.close() }
        db.close(); openLibrary()
        assertNotNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertTrue(downloads.entries().none { !it.taskHidden && it.bookId == a.storageKey })
    }

    @Test fun clearingAStaleCompletedViewCannotHideAnAlreadyQueuedRetry() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            val item = awaitTask(repository, a) as MutableDownloadItem
            downloads.queueTask(a, downloads.generation(), java.util.UUID.randomUUID().toString())
            withTimeout(5000) { while (item.status?.task?.status != DownloadTaskStatus.Interrupted) delay(10) }
            item.progress = 1f // Simulate an old completed frame immediately after explicit retry.
            repository.clearCompleted()
            assertTrue(repository.downloadItemIdList.contains(item))
            assertFalse(downloads.entry(a)!!.taskHidden)
        } finally { repository.close() }
    }

    @Test fun identityPromotionTransfersTheTaskWithoutKeepingTheOldAttempt() = runBlocking {
        val source = register(a)
        val canonical = SourceBookId(a.sourceId, "series")
        val id = java.util.UUID.randomUUID().toString()
        downloads.queueTask(a, downloads.generation(), id, chapterIds = listOf("2"))
        val task = downloads.startTask(a, downloads.generation(), id, 0)
        downloads.begin(a, downloads.generation(), id)
        downloads.mergeIdentity(a, canonical, canonical.bind(BookVolumes(canonical.remoteId, source.directory().volumes)), commit = {})
        assertNull(downloads.entry(a))
        assertEquals(id, downloads.entry(canonical)!!.taskWorkId)
        assertEquals(setOf("2"), downloads.entry(canonical)!!.selectedChapterIds())
        assertEquals("", downloads.entry(canonical)!!.attempt)
        assertTrue(runCatching { downloads.taskStage(task, DownloadStage.Body) }.exceptionOrNull() is CancellationException)
        downloads.taskStage(task.copy(book = canonical), DownloadStage.Directory)
    }

    @Test fun identityPromotionKeepsImagesReadableThroughTheOldChapterIdentity() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(download() is ListenableWorker.Result.Success)
        val image = SourceImage(a, IMAGE, chapterId = SourceChapterId(a, "1").storageKey)
        val previous = downloads.image(image)!!
        val canonical = SourceBookId(a.sourceId, "series")
        val volumes = canonical.bind(BookVolumes(canonical.remoteId, source.directory().volumes))
        downloads.mergeIdentity(a, canonical, volumes) {
            local.aliases.merge(a, canonical, canonical.bind(source.information().copy(id = canonical.remoteId)), volumes)
        }
        val migrated = downloads.image(image)!!
        assertNotEquals(previous.path, migrated.path)
        assertArrayEquals(png, migrated.readBytes())
        val selection = books.downloadSelection(a.storageKey, a.bind(source.directory()))
        assertEquals(3, selection.chapters.size)
        assertTrue(selection.chapters.values.all { it.downloaded })
    }

    @Test fun imageVerificationKeepsItsCategoryInsteadOfBecomingANetworkError() = runBlocking {
        register(a).apply {
            withImages = true; imageFailed = true
            imageFailureKind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired
        }
        assertTrue(download() is ListenableWorker.Result.Failure)
        val state = books.downloadStatusFlow(a.storageKey).first()
        assertEquals(DownloadFailure.Verification, state.task.failure)
        assertEquals(DownloadStage.Image, state.task.stage)
        assertEquals(SourceChapterId(a, "1").storageKey, state.task.chapterId)
        assertEquals(0, state.content.savedChapters)
    }

    @Test fun storageFailureIsRecordedWithoutPublishingItsPrivateExceptionText() = runBlocking {
        register(a)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER deny_download BEFORE INSERT ON chapter_content BEGIN SELECT RAISE(FAIL, 'private-token-fixture'); END")
        assertTrue(download() is ListenableWorker.Result.Failure)
        val state = books.downloadStatusFlow(a.storageKey).first()
        assertEquals(DownloadFailure.Storage, state.task.failure)
        assertEquals(DownloadStage.Storage, state.task.stage)
        assertFalse(downloads.entry(a).toString().contains("private-token-fixture"))
        assertNull(chapter(a, "1"))
    }

    @Test fun exportPreparationDoesNotCreateAnUnrequestedCacheTask() = runBlocking {
        register(a)
        assertTrue(export(images = false) is ListenableWorker.Result.Success)
        assertEquals(DownloadTaskStatus.None.name, downloads.entry(a)!!.taskStatus)
        downloads.queueTask(b, downloads.generation(), java.util.UUID.randomUUID().toString())
        val repository = DownloadProgressRepository(db.userDataDao(), books, downloads)
        try {
            awaitTask(repository, b)
            assertFalse(repository.downloadItemIdList.any { it.type == DownloadType.CACHE && it.bookId == a.storageKey })
            assertNotNull(chapter(a, "1"))
        } finally { repository.close() }
    }

    @Test fun restoredTasksNeverAttachToTheBackedUpExecutor() = runBlocking {
        for (status in listOf(DownloadTaskStatus.Running, DownloadTaskStatus.WaitingRetry)) {
            downloads.clearDownloads()
            val owner = indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity(a.storageKey, phase = "updating",
                attempt = "old-attempt", taskWorkId = java.util.UUID.randomUUID().toString(), taskStatus = status.name,
                taskRetryCount = 2, taskNextAttemptAt = Long.MAX_VALUE)
            downloads.restore(listOf(owner), emptyList(), legacy = false)
            val restored = downloads.entry(a)!!
            assertEquals("", restored.attempt)
            assertEquals("", restored.taskWorkId)
            assertEquals(DownloadTaskStatus.Interrupted, restored.taskState(null).status)
            assertEquals(2, restored.taskRetryCount)
            assertEquals(0L, restored.taskNextAttemptAt)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.removeCheckpointSchema() {
        restorePre28ChapterSchema(this::execSQL)
        execSQL("ALTER TABLE downloaded_chapter RENAME TO checkpoint_chapters")
        execSQL("CREATE TABLE downloaded_chapter (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, signature TEXT NOT NULL, images TEXT NOT NULL)")
        execSQL("INSERT INTO downloaded_chapter SELECT id, bookId, signature, images FROM checkpoint_chapters")
        execSQL("DROP TABLE checkpoint_chapters")
        execSQL("CREATE INDEX index_downloaded_chapter_bookId ON downloaded_chapter (bookId)")
        execSQL("DROP TABLE download_chapter_candidate")
    }

    @Test fun room25UpgradePreservesTaskOwnershipAndContentWithAnUnusedRetryBudget() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        val before = downloads.entry(a)!!
        db.openHelper.writableDatabase.apply {
            execSQL("ALTER TABLE book_download RENAME TO old_download")
            execSQL("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL, taskWorkId TEXT NOT NULL DEFAULT '', taskStatus TEXT NOT NULL DEFAULT 'None', taskStage TEXT NOT NULL DEFAULT 'Unknown', taskChapter TEXT NOT NULL DEFAULT '', taskError TEXT NOT NULL DEFAULT '', taskRunAttempt INTEGER NOT NULL DEFAULT 0, taskHidden INTEGER NOT NULL DEFAULT 0)")
            execSQL("INSERT INTO book_download SELECT bookId, revision, directoryHash, phase, generation, attempt, coverUri, taskWorkId, taskStatus, taskStage, taskChapter, taskError, taskRunAttempt, taskHidden FROM old_download")
            execSQL("DROP TABLE old_download")
            removeCheckpointSchema()
            version = 25
        }
        db.close(); openLibrary()
        val owner = downloads.entry(a)!!
        assertEquals(before.taskWorkId, owner.taskWorkId)
        assertEquals(before.taskStatus, owner.taskStatus)
        assertEquals(0, owner.taskRetryCount)
        assertEquals(0L, owner.taskNextAttemptAt)
        assertEquals(-1L, owner.taskAccountGeneration)
        assertTrue(db.bookDownloadDao().chapters(a.storageKey).all { it.resourceVersion.isEmpty() })
        assertNotNull(chapter(a, "1"))
        assertEquals(3, db.bookDownloadDao().chapters(a.storageKey).size)
    }

    @Test fun room24UpgradeKeepsContentAndDoesNotInventAnActiveTask() = runBlocking {
        register(a)
        assertEquals(ListenableWorker.Result.success(), download())
        db.openHelper.writableDatabase.apply {
            execSQL("ALTER TABLE book_download RENAME TO old_download")
            execSQL("CREATE TABLE book_download (bookId TEXT NOT NULL PRIMARY KEY, revision TEXT NOT NULL, directoryHash TEXT NOT NULL, phase TEXT NOT NULL, generation INTEGER NOT NULL, attempt TEXT NOT NULL, coverUri TEXT NOT NULL)")
            execSQL("INSERT INTO book_download SELECT bookId, revision, directoryHash, 'updating', generation, 'old-attempt', coverUri FROM old_download")
            execSQL("DROP TABLE old_download")
            removeCheckpointSchema()
            version = 24
        }
        db.close(); openLibrary()
        val owner = downloads.entry(a)!!
        assertEquals(29, db.openHelper.writableDatabase.version)
        assertEquals("", owner.taskWorkId)
        assertEquals(DownloadTaskStatus.Interrupted, owner.taskState(null).status)
        assertEquals(DownloadStage.Unknown, owner.taskState(null).stage)
        assertNotNull(chapter(a, "1"))
        assertEquals(3, db.bookDownloadDao().chapters(a.storageKey).size)
    }

    private suspend fun awaitTask(repository: DownloadProgressRepository, book: SourceBookId): DownloadItem = withTimeout(5000) {
        while (true) {
            repository.downloadItemIdList.firstOrNull { it.bookId == book.storageKey && it.status != null }?.let { return@withTimeout it }
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }

    private suspend fun export(images: Boolean = true, selected: List<String>? = null,
                               beforeWrite: () -> Unit = {}, book: SourceBookId = a): ListenableWorker.Result {
        io.mockk.mockkObject(indi.renakoni.nextvol.data.work.EpubShareFiles)
        every { indi.renakoni.nextvol.data.work.EpubShareFiles.publish(context, any(), any(), any()) } answers {
            beforeWrite()
            callOriginal()
        }
        try {
            return indi.renakoni.nextvol.data.work.ExportBookToEPUBWork(context,
                workerParameters(workDataOf("bookId" to book.storageKey, "exportType" to if (selected == null) "BOOK" else "VOLUMES",
                    "selectedVolume" to selected?.joinToString(",").orEmpty(), "includeImages" to images,
                    "downloadGeneration" to downloads.generation())),
                ExportBookToEpubUseCase(context, books, progress, decoder, downloads)).doWork()
        } finally {
            io.mockk.unmockkObject(indi.renakoni.nextvol.data.work.EpubShareFiles)
        }
    }

    @Test fun exportCachesBeforePublishingAndReusesOfflineContentAfterReopening() = runBlocking {
        val source = register(a).apply { withImages = true }
        var published = false
        assertTrue(export(beforeWrite = {
            runBlocking { assertEquals(BookDownloadPhase.Complete, state().phase) }
            published = true
        }) is ListenableWorker.Result.Success)
        assertTrue(published)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        downloads.clearReadingCache()
        registry.unregister(a.sourceId)
        loader.shutdown(); db.close(); openLibrary(); openImages()
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(1, source.imageCalls)
    }

    @Test fun exportRetriesReusePreparedChaptersAfterReopening() = runBlocking {
        val source = register(a).apply { withImages = true; failedChapter = "3" }
        assertTrue(export() is ListenableWorker.Result.Failure)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        downloads.clearReadingCache()
        registry.unregister(a.sourceId)
        loader.shutdown(); db.close(); openLibrary(); openImages()
        val retrySource = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(mapOf("3" to 1), retrySource.chapterCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
    }

    @Test fun unversionedLegacyDownloadStillExportsOffline() = runBlocking {
        val source = register(a)
        local.updateBookInformation(a.bind(source.information()))
        local.updateBookVolumes(a.bind(source.directory()))
        source.chapters.forEach { info ->
            local.updateChapterContent(SourceChapterId(a, info.id).bind(source.body(info.id)))
        }
        db.userDataDao().insert(UserDataPath.CompletedDownloadBookList.path, "fixture", "CompletedDownloadItemList", "CACHE|${a.storageKey}")
        downloads.prepare()
        registry.unregister(a.sourceId)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertTrue(source.chapterCalls.isEmpty())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun exportRetainsOriginalBytesWhenTheReadingDiskCacheWasEvicted() = runBlocking {
        val source = register(a).apply { withImages = true }
        val image = loader.execute(ImageRequest.Builder(context).data(SourceImage(a, IMAGE)).build()) as SuccessResult
        assertNotNull(image.memoryCacheKey)
        assertNotNull(loader.memoryCache!![image.memoryCacheKey!!])
        cache.clear()
        assertTrue(export() is ListenableWorker.Result.Success)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(2, source.imageCalls)
    }

    @Test fun selectedExportPinsOnlySelectedChaptersAndTextOnlyDoesNotClaimImagesAreCached() = runBlocking {
        val source = register(a).apply {
            withImages = true
            exportVolumes = listOf(Volume("first", "First", chapters.take(1)), Volume("rest", "Rest", chapters.drop(1)))
        }
        assertTrue(export(images = false, selected = listOf(BookIdentity.volumeKey(a, "rest"))) is ListenableWorker.Result.Success)
        assertEquals(mapOf("2" to 1, "3" to 1), source.chapterCalls)
        assertNull(chapter(a, "1"))
        assertEquals(0, source.imageCalls)
        assertEquals(BookDownloadPhase.Partial, state().phase)
        downloads.clearReadingCache()
        assertNotNull(chapter(a, "2"))
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
        assertEquals(1, source.imageCalls)
    }

    @Test fun failedSharePublicationKeepsCompletedOfflineDownload() = runBlocking {
        register(a).withImages = true
        val result = export(beforeWrite = { throw java.io.IOException("destination full") }) as ListenableWorker.Result.Failure
        assertEquals("share_failed", result.outputData.getString("reason"))
        assertEquals(BookDownloadPhase.Complete, state().phase)
        downloads.clearReadingCache()
        registry.unregister(a.sourceId)
        assertTrue(export() is ListenableWorker.Result.Success)
    }

    @Test fun clearingDownloadsDuringExportRejectsLateChapterWrites() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val work = async { export() }
        withTimeout(5000) { entered.await() }
        downloads.clearDownloads()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { work.await() } is ListenableWorker.Result.Failure)
        assertNull(chapter(a, "1"))
        assertEquals(BookDownloadPhase.None, state().phase)
    }

    @Test fun exportRefreshesDownloadedChaptersAfterSourceRevisionChanges() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 2, "2" to 2, "3" to 2), source.chapterCalls)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun failedRevisionImageRefreshIsRetriedEvenAfterTargetVersionWasWritten() = runBlocking {
        val source = register(a).apply { withImages = true; extraImage = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        val before = chapter(a, "1")
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        source.imageBytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        source.failedImage = "$IMAGE?extra"
        assertTrue(export() is ListenableWorker.Result.Failure)
        assertEquals(before, chapter(a, "1"))
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        val callsAfterFailure = source.imageCalls
        downloads.clearReadingCache()
        cache.clear()
        source.failedImage = null
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(callsAfterFailure + 1, source.imageCalls)
        assertArrayEquals(source.imageBytes, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun textOnlyRevisionUpdateLeavesOldImagesPendingForLaterExport() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(export() is ListenableWorker.Result.Success)
        registry.unregister(a.sourceId)
        register(a, revision = "2", source = source)
        assertTrue(export(images = false) is ListenableWorker.Result.Success)
        assertEquals(1, source.imageCalls)
        assertEquals(BookDownloadPhase.Partial, state().phase)
        assertTrue(export() is ListenableWorker.Result.Success)
        assertEquals(2, source.imageCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }

    @Test fun cacheAndExportOfSameBookDoNotReplaceEachOthersAttempt() = runBlocking {
        val source = register(a)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        source.chapterPause = { entered.complete(Unit); release.await() }
        val caching = async { download() }
        withTimeout(5000) { entered.await() }
        val exporting = async { export() }
        yield()
        release.complete(Unit)
        assertTrue(withTimeout(5000) { caching.await() } is ListenableWorker.Result.Success)
        assertTrue(withTimeout(5000) { exporting.await() } is ListenableWorker.Result.Success)
        assertEquals(mapOf("1" to 1, "2" to 1, "3" to 1), source.chapterCalls)
        assertEquals(BookDownloadPhase.Complete, state().phase)
    }


    @Test fun deviceFullIsIdentifiedByItsTypedCauseWithoutGuessingFromMessages() {
        val full = java.io.IOException(android.database.sqlite.SQLiteFullException())
        assertEquals(DownloadFailure.StorageFull, downloadFailure(WebRequestError("", "", full), DownloadStage.Body))
        val noSpace = java.io.IOException(android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))
        assertEquals(DownloadFailure.StorageFull, downloadFailure(WebRequestError("", "", noSpace), DownloadStage.Storage))
    }

    @Test fun clearingCacheBetweenImageDecodeAndRetentionDefersUntilTheBookFinishes() = runBlocking {
        register(a).withImages = true
        downloads.withBookOperation(a) {
            val attempt = downloads.begin(a, downloads.generation(), "clear-race")
            val image = SourceImage(a, IMAGE, preferDownloaded = false)
            val result = loader.execute(ImageRequest.Builder(context).data(image)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build())
            assertTrue(result is SuccessResult)
            assertFalse(downloads.clearReadingCache())
            downloads.retainImage(attempt, image, (result as SuccessResult).diskCacheKey)
            assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
            downloads.finish(attempt, true)
        }
        assertTrue(downloads.clearReadingCache())
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
    }

    @Test fun cancellingOneOfTwoBookOperationsDoesNotEnableCleanupTooEarly() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch { downloads.withBookOperation(a) { firstEntered.complete(Unit); awaitCancellation() } }
        val second = launch { downloads.withBookOperation(b) { secondEntered.complete(Unit); awaitCancellation() } }
        withTimeout(5000) { firstEntered.await(); secondEntered.await() }
        assertFalse(downloads.clearReadingCache())
        first.cancelAndJoin()
        assertFalse(downloads.clearReadingCache())
        second.cancelAndJoin()
        assertTrue(downloads.clearReadingCache())
    }

    @Test fun storageOverviewAccountsForImagesCandidatesAndChaptersRemovedFromTheRemoteDirectory() = runBlocking {
        val source = register(a).apply { withImages = true }
        assertTrue(download() is ListenableWorker.Result.Success)
        register(b).apply { withImages = true; imageFailed = true }
        assertTrue(download(b) is ListenableWorker.Result.Failure)
        source.chapters = source.chapters.take(1)
        local.updateBookVolumes(a.bind(source.directory()))
        val online = SourceBookId(a.sourceId, "online-only")
        local.updateChapterContent(SourceChapterId(online, "1").bind(
            ChapterContent("1", "Online", ContentBuilder().simpleText("temporary").build())))
        val measuredContext = object : ContextWrapper(context) {
            override fun getDataDir() = directory.root
            override fun getCacheDir() = directory.root.resolve("coil")
            override fun getDatabasePath(name: String) = directory.root.resolve("library.db")
        }
        val imported = context.filesDir.resolve("local-books/fixture.epub").apply { parentFile.mkdirs(); writeText("original") }
        val sourceState = context.filesDir.resolve("source-state").apply { writeText("keep") }
        val data = indi.renakoni.nextvol.data.userdata.UserDataRepository(db.userDataDao())
        val usage = indi.renakoni.nextvol.data.storage.StorageUsageRepository(measuredContext, db, data)
        val before = usage.refreshSnapshot()
        assertEquals(2, before.downloadedBookCount)
        assertEquals(3, before.downloadedChapterCount)
        assertTrue(before.preparingChapterCount > 0)
        assertEquals(png.size.toLong() * 3, before.downloadImageBytes)
        assertEquals(imported.length(), before.importedFileBytes)
        assertTrue(before.readingContentBytes > 0)
        val saved = before.books.single { it.bookId == a.storageKey }
        assertEquals(png.size.toLong() * 3, saved.downloadImageBytes)
        assertEquals(before.downloadedContentBytes, saved.chapterContentBytes)
        assertEquals(before.preparationBytes, before.books.single { it.bookId == b.storageKey }.preparationBytes)
        assertTrue(downloads.clearReadingCache())
        val after = usage.refreshSnapshot()
        assertEquals(0L, after.readingContentBytes)
        assertEquals(0L, after.orphanChapterContentBytes)
        assertEquals(before.downloadBytes, after.downloadBytes)
        assertEquals("original", imported.readText())
        assertEquals("keep", sourceState.readText())
        assertNotNull(chapter(a, "3"))
        assertEquals(after, usage.getCachedSnapshot())
        data.stringUserData(UserDataPath.Settings.Data.StorageUsageSnapshot.path).set("{}")
        assertNull(usage.getCachedSnapshot())
        downloads.removeBooks(listOf(b))
        val removed = usage.refreshSnapshot()
        assertEquals(0L, removed.preparationBytes)
        assertEquals(1, removed.downloadedBookCount)
        assertEquals(3, removed.downloadedChapterCount)
        assertArrayEquals(png, downloads.image(SourceImage(a, IMAGE))!!.readBytes())
        downloads.clearDownloads()
        assertEquals(0L, usage.refreshSnapshot().downloadBytes)
        assertEquals("original", imported.readText())
        assertEquals("keep", sourceState.readText())
    }

    private inner class Remote(private val book: SourceBookId) : WebBookDataSource by EmptyWebDataSource, SourceImageProvider {
        override val id = book.sourceId
        override val cache = Cache(timeout = 60_000)
        var chapters = (1..3).map { ChapterInformation(it.toString(), "Chapter $it") }
        var volumeId = "volume"
        var exportVolumes: List<Volume>? = null
        var withImages = false
        var imageFailed = false
        var extraImage = false
        var failedImage: String? = null
        var imageBytes = png
        var imageFailureKind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.Other
        var directoryFailed = false
        var informationFailed = false
        var failedChapter: String? = null
        var chapterPause: (suspend () -> Unit)? = null
        var directoryCalls = 0
        var imageCalls = 0
        val chapterCalls = mutableMapOf<String, Int>()
        fun directory() = BookVolumes(book.remoteId, exportVolumes ?: listOf(Volume(volumeId, "Volume", chapters)))
        fun information() = BookInformation(book.remoteId, "Book", author = "Author", description = "",
            publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 15, 0, 0), isComplete = false)
        fun body(id: String) = ChapterContent(id, chapters.single { it.id == id }.title,
            ContentBuilder().simpleText("${book.sourceId.id}:$id").apply {
                if (withImages) image(Uri.parse(IMAGE))
                if (withImages && extraImage) image(Uri.parse("$IMAGE?extra"))
            }.build())
        override suspend fun getBookInformation(id: String): com.github.michaelbull.result.Result<BookInformation, WebRequestError> =
            if (informationFailed) Err(WebRequestError("Network", "Unavailable")) else Ok(information())
        override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, WebRequestError> {
            directoryCalls++
            return if (directoryFailed) Err(WebRequestError("Network", "Unavailable")) else Ok(directory())
        }
        override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, WebRequestError> {
            chapterCalls[chapterId] = (chapterCalls[chapterId] ?: 0) + 1
            chapterPause?.invoke()
            return if (failedChapter == chapterId) Err(WebRequestError("Network", "Unavailable")) else Ok(body(chapterId))
        }
        override suspend fun getImage(bookId: String, url: String, cover: Boolean): com.github.michaelbull.result.Result<ByteArray, WebRequestError> {
            imageCalls++
            return if (imageFailed || url == failedImage) Err(WebRequestError("Image", "Unavailable", kind = imageFailureKind)) else Ok(imageBytes)
        }
    }
}
