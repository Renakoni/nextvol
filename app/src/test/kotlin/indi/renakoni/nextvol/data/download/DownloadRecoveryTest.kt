package indi.renakoni.nextvol.data.download

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import hnovel.content.RuleSourceFixture
import hnovel.network.SourceNetworkMode
import hnovel.network.SourceNetworkRoute
import hnovel.network.SourceRouteProvider
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.*
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.RuleWebBookDataSource
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.net.UnknownHostException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real rule worker, HTTP transport, Coil, Room and reconstructed WorkManager workers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class DownloadRecoveryTest {
    private class Library(val fixture: RuleSourceFixture) : AutoCloseable {
        private val root = Files.createTempDirectory("download-recovery").toFile()
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = root
        }
        val id = Identifier("rules", "recovery")
        val book = SourceBookId(id, fixture.server.url("/book").toString())
        var workId = UUID.randomUUID()
        val registry = WebSourceRegistry(fixture.authority)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        var onRequest: (String) -> Unit = {}
        var failedPath = "/c/2"
        var status = 503
        var retryAfter = "90"
        var withImages = true
        var unsafeChapter = false
        var singleQuotedOptions = false
        var thirdChapter = false
        var now = 1_800_000_000_000L
        lateinit var db: NextVolDatabase
        lateinit var downloads: BookDownloadStore
        lateinit var local: LocalBookDataSource
        private lateinit var books: BookRepository
        private lateinit var loader: ImageLoader
        private val cache = DiskCache.Builder().directory(root.resolve("coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
        private val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

        init {
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!.substringBefore('?')
                    requests += path
                    onRequest(path)
                    if (path == failedPath) return MockResponse().setResponseCode(status).setHeader("Retry-After", retryAfter)
                    val body = when (path) {
                        "/book" -> "<h1>Book</h1><a class='toc' href='/toc'>toc</a>" +
                            if (withImages) "<img src='/cover.png'>" else ""
                        "/toc" -> "<li><a href='/c/1'>One</a></li><a class='next' href='/toc2'>next</a>"
                        "/toc2" -> "<li><a href='/c/2," +
                            (if (singleQuotedOptions) "{'retry':3,'method':'POST','body':'submit'}".replace("'", "&#39;")
                            else "{\"retry\":3${if (unsafeChapter) ",\"method\":\"POST\",\"body\":\"submit\"" else ""}}") + "'>Two</a></li>" +
                            if (thirdChapter) "<li><a href='/c/3'>Three</a></li>" else ""
                        "/c/1" -> "<article><p>first</p></article>"
                        "/c/2" -> "<article><p>second</p>${if (withImages) "<img src='/image.png'>" else ""}</article>"
                        "/c/3" -> "<article><p>third</p></article>"
                        "/image.png", "/cover.png" -> return MockResponse().setBody(Buffer().write(png))
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(body)
                }
            }
            register()
            open()
        }

        fun register(revision: String = "1", account: Long = 0, script: Boolean = false) {
            registry.unregister(id)
            val source = fixture.source { raw -> JsonObject(raw - "coverDecodeJs" + mapOf(
                "ruleBookInfo" to buildJsonObject {
                    put("name", "h1@text"); put("tocUrl", "a.toc@href"); put("coverUrl", "img@src"); put("updateTime", "h1@text")
                },
                "ruleToc" to buildJsonObject {
                    put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href")
                    put("nextTocUrl", "a.next@href")
                },
                "ruleContent" to buildJsonObject { put("content", if (script) "article@html@js:result" else "article@html") },
            )) }
            assertEquals(!script, source.canReplayDownloads)
            registry.register(RuleWebBookDataSource(id, source), SourceMetadata(WebDataSourceItem(id, "Recovery", "test"),
                setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent),
                revision = revision, accountGeneration = account))
        }

        private fun open() {
            db = Room.databaseBuilder(context, NextVolDatabase::class.java, root.resolve("library.db").path)
                .addMigrations(NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27, NextVolDatabase.MIGRATION_27_28, NextVolDatabase.MIGRATION_28_29).allowMainThreadQueries().build()
            val aliases = BookAliasStore(db)
            local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), aliases)
            downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
            val text = TextProcessingRepository(mockk { every { enabled } returns false },
                mockk { every { enabled } returns false }, ContentComponentRegistry())
            val work = mockk<androidx.work.WorkManager>(relaxed = true)
            val scheduler = BookDownloadScheduler(downloads, work, aliases)
            val shelves = BookshelfRepository(db.bookshelfDao(), scheduler, registry, aliases)
            books = BookRepository(local, shelves, text, work, ChapterRepository(registry, local, text, mockk(), downloads),
                BookReadingDataRepository(local), registry, downloads, mockk(), scheduler)
            loader = ImageLoader.Builder(context).diskCache(cache).components {
                add(SourceImageInterceptor(registry, context, downloads)); add(SourceImageFetcher.Factory())
            }.build()
            SingletonImageLoader.setUnsafe(loader)
        }

        suspend fun queue() = downloads.queueTask(book, downloads.generation(), workId.toString())
        suspend fun run() = CacheBookWork(context, workerParameters(workDataOf("bookId" to book.storageKey,
            "downloadGeneration" to 0L, "persistedTask" to true), workId), mockk(relaxed = true), books, downloads)
            .apply { retryPolicy = DownloadRetryPolicy({ now }, { it }) }.doWork()
        suspend fun owner() = checkNotNull(downloads.entry(book))
        fun calls(path: String) = synchronized(requests) { requests.count { it == path } }
        fun reopen() { loader.shutdown(); db.close(); open() }
        override fun close() {
            registry.unregister(id); loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close(); root.deleteRecursively()
        }
    }

    @Test fun abandonedExecutionsShareThreeRecoveriesAcrossHostReconstruction() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            queue()
            for (used in 0..3) {
                downloads.startTask(book, 0, workId.toString(), used, nowMillis = now)
                reopen(); register()
                val result = run()
                if (used < 3) {
                    assertEquals(Result.retry(), result)
                    assertEquals(used + 1, owner().taskRetryCount)
                    assertEquals(DownloadFailure.SystemInterrupted.name, owner().taskError)
                    now = owner().taskNextAttemptAt
                } else {
                    assertTrue(result is Result.Failure)
                    assertEquals(DownloadFailure.RetryExhausted.name, owner().taskError)
                    assertEquals(3, owner().taskRetryCount)
                }
                assertTrue(requests.isEmpty())
            }
        } } }
    }

    @Test fun serialRuleDownloadRecordsTheCurrentChapterBeforeItsRequest() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            failedPath = ""; withImages = false
            val observed = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
            onRequest = { path -> if (path.startsWith("/c/")) {
                val task = runBlocking { owner() }
                observed += Triple(path, task.taskStage, task.taskChapter)
            } }
            queue()
            assertEquals(Result.success(), run())
            assertEquals(listOf("/c/1", "/c/2"), observed.map { it.first })
            assertEquals(listOf(DownloadStage.Body.name, DownloadStage.Body.name), observed.map { it.second })
            for ((path, _, chapter) in observed) {
                assertTrue("The in-flight chapter must already be recorded", chapter.isNotEmpty())
                assertEquals(fixture.server.url(path).toString(), SourceChapterId.fromStorageKey(chapter).remoteId.substringBefore(','))
            }
        } } }
    }

    @Test fun unsafeInterruptedExecutionWaitsForAnExplicitContinue() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            register(script = true); queue()
            downloads.startTask(book, 0, workId.toString(), 0, nowMillis = now)
            reopen(); register(script = true)
            repeat(2) { assertTrue(run() is Result.Failure) }
            assertEquals(DownloadTaskStatus.Interrupted.name, owner().taskStatus)
            assertEquals(DownloadFailure.SystemInterrupted.name, owner().taskError)
            assertEquals(0, owner().taskRetryCount)
            assertTrue(requests.isEmpty())
        } } }
    }

    @Test fun recordedTerminalResultsAreNotReplayedAfterHostReconstruction() = runBlocking {
        for (complete in listOf(true, false)) RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            withImages = false
            failedPath = if (complete) "" else "/c/2"
            status = 404
            queue()
            val first = run()
            assertEquals(complete, first is Result.Success)
            val calls = requests.size
            val recorded = owner()
            reopen(); register()
            assertEquals(complete, run() is Result.Success)
            assertEquals(calls, requests.size)
            assertEquals(recorded.taskStatus, owner().taskStatus)
            assertEquals(recorded.taskRetryCount, owner().taskRetryCount)
        } } }
    }

    @Test fun cooldownAndBudgetSurviveReconstructionAndCommittedChaptersAreNotFetchedAgain() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            queue()
            assertEquals(Result.retry(), run())
            val waiting = owner()
            assertEquals(DownloadTaskStatus.WaitingRetry.name, waiting.taskStatus)
            assertEquals(DownloadStage.Body.name, waiting.taskStage)
            assertEquals(DownloadFailure.RateLimited.name, waiting.taskError)
            assertEquals(1, waiting.taskRetryCount)
            assertEquals(now + 90_000, waiting.taskNextAttemptAt)
            assertEquals(listOf("/book", "/toc", "/toc2", "/c/1", "/c/2"), requests.toList())
            assertEquals(1, db.bookDownloadDao().chapters(book.storageKey).size)
            reopen(); register()
            now = waiting.taskNextAttemptAt - 1
            assertEquals(Result.retry(), run())
            assertEquals(5, requests.size)
            assertEquals(1, owner().taskRetryCount)
            failedPath = ""
            now++
            assertEquals(Result.success(), run())
            assertEquals(1, calls("/c/1")); assertEquals(2, calls("/c/2"))
            assertEquals(1, calls("/image.png")); assertEquals(1, calls("/cover.png"))
            assertEquals(2, db.bookDownloadDao().chapters(book.storageKey).size)
            assertEquals(DownloadTaskStatus.Complete.name, owner().taskStatus)
            assertEquals(0L, owner().taskNextAttemptAt)
        } } }
    }

    @Test fun persistentFailuresShareThreeRecoveriesAcrossPaginationBodyAndImages() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            queue(); retryAfter = "0"
            for ((index, path) in listOf("/toc2", "/c/2", "/image.png").withIndex()) {
                failedPath = path
                assertEquals(path, Result.retry(), run())
                assertEquals(index + 1, owner().taskRetryCount)
                assertEquals(listOf(30_000L, 60_000L, 120_000L)[index], owner().taskNextAttemptAt - now)
                now = owner().taskNextAttemptAt
                reopen()
            }
            assertTrue(run() is Result.Failure)
            assertEquals(3, owner().taskRetryCount)
            assertEquals(DownloadFailure.RetryExhausted.name, owner().taskError)
            assertEquals(4, calls("/book")); assertEquals(4, calls("/toc2"))
            assertEquals(1, calls("/c/1")); assertEquals(2, calls("/c/2")); assertEquals(2, calls("/image.png"))
            assertEquals(0, calls("/cover.png"))
        } } }
    }

    @Test fun savedBodySurvivesRestartAndAccountChangeAndOnlyMissingImagesAreRetried() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            failedPath = "/image.png"; queue()
            assertEquals(Result.retry(), run())
            val candidate = db.bookDownloadDao().candidates(book.storageKey).single()
            val state = downloads.state(book, local.getBookVolumes(book.storageKey), false, contentOnly = true)
            assertEquals(2, state.bodyChapters); assertEquals(1, state.savedChapters)
            assertEquals(1, state.missingImages); assertTrue(state.coverMissing)
            now = owner().taskNextAttemptAt
            reopen(); register(account = 1)
            val requestsBefore = requests.size
            assertTrue(run() is Result.Failure) // Old requests cannot borrow the new login.
            assertEquals(requestsBefore, requests.size)
            assertEquals(candidate, db.bookDownloadDao().candidates(book.storageKey).single())
            workId = UUID.randomUUID(); queue(); failedPath = ""
            assertEquals(Result.success(), run())
            assertEquals(1, calls("/c/1")); assertEquals(1, calls("/c/2"))
            assertEquals(2, calls("/image.png")); assertEquals(1, calls("/cover.png"))
            assertTrue(db.bookDownloadDao().candidates(book.storageKey).isEmpty())
        } } }
    }

    @Test fun independentMissingChapterDoesNotBlockLaterChaptersButStatefulRulesStillStop() = runBlocking {
        for (mode in listOf("static", "script", "post", "single-quoted-post")) {
            RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
                thirdChapter = true; withImages = false; status = 404
                if (mode == "script") register(script = true)
                if (mode == "post") unsafeChapter = true
                if (mode == "single-quoted-post") singleQuotedOptions = true
                queue(); assertTrue(mode, run() is Result.Failure)
                if (singleQuotedOptions) {
                    val chapterRequest = List(fixture.server.requestCount) { fixture.server.takeRequest() }
                        .single { it.path == "/c/2" }
                    assertEquals("POST", chapterRequest.method)
                }
                assertEquals(mode, 1, calls("/c/1"))
                assertEquals(mode, if (mode == "static") 1 else 0, calls("/c/3"))
                assertEquals(mode, if (mode == "static") 2 else 1, db.bookDownloadDao().chapters(book.storageKey).size)
                assertEquals(DownloadStage.Body.name, owner().taskStage)
                assertEquals(0, owner().taskRetryCount)
            } } }
        }
    }

    @Test fun detailsCoverAndImageTransientFailuresAllUseTheTaskBudget() = runBlocking {
        for (path in listOf("/book", "/cover.png", "/image.png")) {
            RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
                failedPath = path; status = 502; queue()
                assertEquals(path, Result.retry(), run())
                assertEquals(1, calls(path))
                assertEquals(1, owner().taskRetryCount)
                assertEquals(DownloadFailure.Network.name, owner().taskError)
                now = owner().taskNextAttemptAt; failedPath = ""
                assertEquals(Result.success(), run())
                assertEquals(2, calls(path))
            } } }
        }
    }

    @Test fun cancellationClearRemovalAndAccountOrRevisionChangesRevokeWaitingWork() = runBlocking {
        for (action in listOf("cancel", "clear", "remove", "account", "revision")) {
            RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
                queue(); assertEquals(Result.retry(), run())
                now = owner().taskNextAttemptAt
                when (action) {
                    "cancel" -> downloads.dismissTask(book)
                    "clear" -> downloads.clearDownloads()
                    "remove" -> registry.unregister(id)
                    "account" -> register(account = 1)
                    "revision" -> register(revision = "2")
                }
                val count = requests.size
                reopen()
                assertTrue(action, run() is Result.Failure)
                assertEquals(action, count, requests.size)
                if (action in setOf("remove", "account", "revision"))
                    assertEquals(DownloadFailure.SourceUnavailable.name, owner().taskError)
            } } }
        }
    }

    @Test fun permanentHttpFailuresUnsafeRequestsAndHugeDeadlinesDoNotReplayTheWorker() = runBlocking {
        for (kind in listOf("404", "post", "script", "overflow", "parse")) {
            RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
                when (kind) {
                    "404" -> status = 404
                    "post" -> unsafeChapter = true
                    "script" -> register(script = true)
                    "overflow" -> retryAfter = "99999999999999999999999"
                    "parse" -> { failedPath = "/toc"; status = 200 }
                }
                queue(); assertTrue(kind, run() is Result.Failure)
                assertEquals(kind, 0, owner().taskRetryCount)
                assertEquals(kind, 1, calls(failedPath))
            } } }
        }
    }

    @Test fun accountReplacementDuringParsingCannotCommitTheOldResultOrFetchTheNextChapter() = runBlocking {
        RuleSourceFixture().use { fixture -> Library(fixture).use { library -> with(library) {
            failedPath = ""
            var changed = false
            fixture.afterRun = {
                if (!changed && calls("/c/1") == 1) {
                    changed = true
                    register(account = 1)
                }
            }
            queue(); assertTrue(run() is Result.Failure)
            assertTrue(changed)
            assertEquals(1, calls("/c/1")); assertEquals(0, calls("/c/2"))
            assertTrue(db.bookDownloadDao().chapters(book.storageKey).isEmpty())
            assertEquals(0, owner().taskRetryCount)
            assertEquals(DownloadFailure.SourceUnavailable.name, owner().taskError)
        } } }
    }

    @Test fun dnsFailureAndRetiredNetworkRoutesConsumeTheSameDurableBudget() = runBlocking {
        val brokenDns = AtomicBoolean(true)
        val lookups = AtomicInteger()
        val dns = Dns { host ->
            lookups.incrementAndGet()
            if (brokenDns.get()) throw UnknownHostException("fixture")
            Dns.SYSTEM.lookup(host)
        }
        var route = SourceNetworkRoute(SourceNetworkMode.SystemDefault, dns)
        RuleSourceFixture(route = SourceRouteProvider { route }).use { fixture -> Library(fixture).use { library -> with(library) {
            failedPath = ""; queue()
            assertEquals(Result.retry(), run())
            assertEquals(1, lookups.get()); assertEquals(0, requests.size)
            assertEquals(DownloadFailure.Network.name, owner().taskError)
            brokenDns.set(false); now = owner().taskNextAttemptAt
            fixture.afterRun = { if (calls("/c/1") == 1) route.invalidate() }
            assertEquals(Result.retry(), run())
            assertEquals(2, owner().taskRetryCount)
            assertEquals(1, calls("/c/1")); assertEquals(0, calls("/c/2"))
            assertEquals(1, db.bookDownloadDao().chapters(book.storageKey).size)
            fixture.afterRun = {}
            route = SourceNetworkRoute(SourceNetworkMode.SystemDefault, dns)
            now = owner().taskNextAttemptAt
            assertEquals(Result.success(), run())
            assertEquals(1, calls("/c/1")); assertEquals(1, calls("/c/2"))
        } } }
    }
}
