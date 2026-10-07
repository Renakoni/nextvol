package indi.renakoni.nextvol.data.download

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.*
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.workerParameters
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Real worker, runtime, Room and image publication with controlled native responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class NativeDownloadPipelineTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = directory.root.resolve("files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            baseContext.getSharedPreferences("native-${directory.root.name}-$name", mode)
    } }
    private val sourceId = Identifier("fixture", "native-download")
    private val book = SourceBookId(sourceId, "123")
    private val registry = WebSourceRegistry()
    private lateinit var db: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var store: BookDownloadStore
    private lateinit var books: BookRepository
    private lateinit var cache: DiskCache
    private lateinit var loader: ImageLoader
    private val progress = mockk<DownloadProgressRepository>(relaxed = true)
    private val chapters = (1..8).map { ChapterInformation(it.toString(), "Chapter $it") }
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private var beforeBody: suspend (String) -> Unit = {}
    private var beforeImage: suspend (String) -> Unit = {}
    private var withImages = false
    private val png by lazy { ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray() }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(),
            db.userReadingDataDao(), BookAliasStore(db))
        store = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
        val work = mockk<androidx.work.WorkManager>(relaxed = true)
        val scheduler = BookDownloadScheduler(store, work, local.aliases)
        val text = TextProcessingRepository(mockk { every { enabled } returns false },
            mockk { every { enabled } returns false }, ContentComponentRegistry())
        books = BookRepository(local, BookshelfRepository(db.bookshelfDao(), scheduler, registry, local.aliases), text, work,
            ChapterRepository(registry, local, text, mockk(), store), BookReadingDataRepository(local), registry, store, mockk(), scheduler)
        cache = DiskCache.Builder().directory(directory.root.resolve("coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
        loader = ImageLoader.Builder(context).diskCache(cache).components {
            add(SourceImageInterceptor(registry, context, store)); add(SourceImageFetcher.Factory())
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        register()
    }

    private fun register(account: Long = 0, revision: String = "1") {
        val api = spyk(Wenku8Api { error("Transport replaced by controlled native responses") })
        every { api.id } returns sourceId
        every { api.onLoad() } just Runs
        coEvery { api.getBookInformation(any()) } coAnswers {
            Ok(BookInformation(firstArg(), "Book", author = "Author", description = "", publishingHouse = "",
                wordCount = WordCount(1), lastUpdated = LocalDateTime.MIN, isComplete = false))
        }
        coEvery { api.getBookVolumes(any()) } coAnswers { Ok(BookVolumes(firstArg(), listOf(Volume("v", "Volume", chapters)))) }
        coEvery { api.getChapterContent(any(), any()) } coAnswers {
            val id = firstArg<String>()
            calls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            beforeBody(id)
            Ok(ChapterContent(id, "Chapter $id", ContentBuilder().simpleText("body $id").apply {
                if (withImages) repeat(2) { image(Uri.parse("https://fixture.invalid/$id/$it.png")) }
            }.build(), chapters.getOrNull(id.toInt() - 2)?.id, chapters.getOrNull(id.toInt())?.id))
        }
        coEvery { api.getImage(any(), any(), any()) } coAnswers { beforeImage(secondArg()); Ok(png) }
        registry.register(api, SourceMetadata(WebDataSourceItem(sourceId, "Fixture", "fixture"),
            setOf(SourceCapability.BookInformation, SourceCapability.Directory, SourceCapability.ChapterContent),
            revision = revision, accountGeneration = account))
    }

    @After fun close() {
        registry.unregister(sourceId)
        loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close()
        context.baseContext.deleteSharedPreferences("native-${directory.root.name}-source_image_cache_keys")
    }

    private suspend fun download(target: SourceBookId = book, selected: List<String>? = null): ListenableWorker.Result {
        val id = UUID.randomUUID()
        store.queueTask(target, store.generation(), id.toString(), chapterIds = selected)
        return CacheBookWork(context, workerParameters(workDataOf("bookId" to target.storageKey,
            "downloadGeneration" to store.generation(), "persistedTask" to true), id), progress, books, store).doWork()
    }

    private suspend fun saved(id: String) = local.getChapterContent(SourceChapterId(book, id).storageKey)
    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }

    @Test fun selectedChaptersPublishOutOfOrderWithFullDirectorySignaturesAndMonotonicProgress() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = Channel<String>(Channel.UNLIMITED)
        beforeBody = { id -> started.send(id); if (id == "2") release.await() }
        val item = slot<DownloadItem>()
        every { progress.addExportItem(capture(item)) } just Runs
        val pending = async { download(selected = listOf("2", "4", "5", "7")) }
        try {
            withTimeout(5000) { repeat(4) { started.receive() } }
            until { saved("7") != null }
            assertNull(saved("2"))
            val partial = item.captured.progress
            assertTrue(partial > 0f && partial < 1f)
            release.complete(Unit)
            assertEquals(ListenableWorker.Result.success(), pending.await())
            assertTrue(item.captured.progress >= partial)
            assertEquals(1f, item.captured.progress)
            assertEquals(setOf("2", "4", "5", "7"), calls.keys)
            val selection = store.selectionState(book, book.bind(BookVolumes(book.remoteId, listOf(Volume("v", "Volume", chapters)))))
            assertEquals(setOf("2", "4", "5", "7"), selection.chapters.filterValues { it.downloaded }.keys
                .map { SourceChapterId.fromStorageKey(it).remoteId }.toSet())
            assertTrue(db.bookDownloadDao().chapters(book.storageKey).all {
                val index = SourceChapterId.fromStorageKey(it.id).remoteId.toInt() - 1
                it.signature == downloadChapterSignature(book.bind(BookVolumes(book.remoteId, listOf(Volume("v", "Volume", chapters)))).volumes.single().chapters, index, "1")
            })
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }

    @Test fun allImagesMustFinishBeforePublishingAndResumeReusesTheBody() = runBlocking {
        withImages = true
        val started = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        beforeImage = { started.send(it); if (it.endsWith("/1.png")) release.await() }
        val pending = async { download(selected = listOf("3")) }
        try {
            withTimeout(5000) { repeat(2) { started.receive() } }
            assertNull(saved("3"))
            pending.cancelAndJoin()
            assertNull(saved("3"))
            val bodyCalls = calls["3"]!!.get()
            release.complete(Unit)
            beforeImage = {}
            assertEquals(ListenableWorker.Result.success(), download(selected = listOf("3")))
            assertEquals(bodyCalls, calls["3"]!!.get())
            for (image in 0..1) assertArrayEquals(png, store.image(SourceImage(book,
                "https://fixture.invalid/3/$image.png", chapterId = SourceChapterId(book, "3").storageKey))!!.readBytes())
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }

    @Test fun crossBookBodyBudgetLeavesRoomForForegroundAndCancellationDrainsIt() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        beforeBody = { id -> if (id != "8") {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { started.send(id); awaitCancellation() } finally { active.decrementAndGet() }
        } }
        val jobs = listOf(book, book.copy(remoteId = "456")).map { async { download(it, listOf("1", "2", "3", "4", "5", "6")) } }
        try {
            withTimeout(5000) { repeat(4) { started.receive() } }
            val runtime = (registry.resolve(sourceId) as SourceResolution.Ready).runtime
            assertTrue(withTimeout(2000) { runtime.getChapterContent("8", book.remoteId) }.isOk)
            assertEquals(4, active.get())
            assertEquals(4, peak.get())
        } finally { jobs.forEach { it.cancel() }; jobs.joinAll() }
        until { active.get() == 0 }
        assertTrue(db.bookDownloadDao().chapters(book.storageKey).isEmpty())
        assertEquals(4, peak.get())
    }

    @Test fun accountOrSourceReplacementCannotPublishPreparedBodies() = runBlocking {
        withImages = true
        for ((account, revision) in listOf(1L to "1", 1L to "2")) {
            val started = CompletableDeferred<Unit>()
            beforeImage = { started.complete(Unit); awaitCancellation() }
            val pending = async { download(selected = listOf("1", "2", "3", "4")) }
            withTimeout(5000) { started.await() }
            registry.unregister(sourceId)
            register(account, revision)
            withTimeout(5000) { pending.join() }
            assertTrue(db.bookDownloadDao().chapters(book.storageKey).isEmpty())
            assertNotEquals(DownloadTaskStatus.Complete.name, store.entry(book)!!.taskStatus)
        }
    }

    @Test fun aFatalImageFailureKeepsItsStageAndCancelsPrefetch() = runBlocking {
        withImages = true
        val active = AtomicInteger()
        beforeBody = { id -> if (id != "1") {
            active.incrementAndGet()
            try { awaitCancellation() } finally { active.decrementAndGet() }
        } }
        beforeImage = { throw java.io.IOException("Fixture image failure") }
        assertTrue(withTimeout(5000) { download() } is ListenableWorker.Result.Failure)
        until { active.get() == 0 }
        val owner = store.entry(book)!!
        assertEquals(DownloadStage.Image.name, owner.taskStage)
        assertEquals(SourceChapterId(book, "1").storageKey, owner.taskChapter)
        assertEquals(mapOf("1" to DownloadFailure.Network), owner.chapterFailures())
        assertTrue(db.bookDownloadDao().chapters(book.storageKey).isEmpty())
        assertTrue(calls.size <= 5)
    }

    @Test fun replacingTheTaskWhileImagesArePendingCannotCommitUnderTheNewOwner() = runBlocking {
        withImages = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeImage = { started.complete(Unit); release.await() }
        val pending = async { download(selected = listOf("1")) }
        try {
            withTimeout(5000) { started.await() }
            val replacement = UUID.randomUUID().toString()
            store.queueTask(book, store.generation(), replacement, chapterIds = listOf("2"))
            release.complete(Unit)
            withTimeout(5000) { pending.join() }
            assertNull(saved("1"))
            assertEquals(replacement, store.entry(book)!!.taskWorkId)
            assertEquals(DownloadTaskStatus.Queued.name, store.entry(book)!!.taskStatus)
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }

    @Test fun replacingTheTaskRejectsLatePrefetchWritesAndFurtherReads() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        beforeBody = { started.send(it); release.await() }
        val pending = async { download() }
        try {
            withTimeout(5000) { repeat(4) { started.receive() } }
            val replacement = UUID.randomUUID().toString()
            store.queueTask(book, store.generation(), replacement, chapterIds = listOf("8"))
            release.complete(Unit)
            withTimeout(5000) { pending.join() }
            assertEquals(4, calls.size)
            assertTrue(db.bookDownloadDao().candidates(book.storageKey).isEmpty())
            assertTrue(db.bookDownloadDao().chapters(book.storageKey).isEmpty())
            assertEquals(replacement, store.entry(book)!!.taskWorkId)
        } finally { release.complete(Unit); pending.cancelAndJoin() }
    }

    @Test fun aSourceCancelledChapterCannotBeSilentlyOmittedFromASuccessfulTask() = runBlocking {
        beforeBody = { if (it == "1") throw CancellationException("Source cancelled this request") }
        assertTrue(withTimeout(5000) { download() } is ListenableWorker.Result.Failure)
        assertNull(saved("1"))
        assertNotEquals(DownloadTaskStatus.Complete.name, store.entry(book)!!.taskStatus)
    }

    @Test fun imagesFromDifferentBooksShareTwoSlotsAndReleaseThemOnCancellation() = runBlocking {
        withImages = true
        val started = Channel<String>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        beforeImage = {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { started.send(it); awaitCancellation() } finally { active.decrementAndGet() }
        }
        val jobs = listOf("123", "456", "789").map { id -> async { download(book.copy(remoteId = id), listOf("1")) } }
        try {
            withTimeout(5000) { repeat(2) { started.receive() } }
            assertEquals(2, active.get())
            assertEquals(2, peak.get())
        } finally { jobs.forEach { it.cancel() }; jobs.joinAll() }
        until { active.get() == 0 }
        assertEquals(2, peak.get())
    }
}
