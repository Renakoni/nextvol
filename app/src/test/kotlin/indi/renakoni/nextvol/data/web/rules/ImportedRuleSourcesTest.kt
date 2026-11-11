package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.NetworkGrant
import hnovel.network.StorageArea
import hnovel.network.StorageRequest
import hnovel.network.StorageResult
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ImportedRuleSourcesTest {
    private fun context() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = Files.createTempDirectory("rule-host").toFile()
        override fun getFilesDir(): File = root
    }

    private suspend fun install(service: ImportedRuleSources, raw: JsonObject, origin: String): Identifier {
        val preview = service.importer.preview(raw.toString())
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertNull(service.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
        return service.activate(service.definitions.list().single { Json.parseToJsonElement(it.rawJson) == raw }.reference(), listOf(NetworkGrant(origin, true)))
    }

    @Test fun sourceChangesCannotReactivateQueuedOrWaitingDownloadsButHostRestartCan() = runBlocking {
        for (action in listOf("remove", "disable", "restart", "rotate", "account")) {
            val host = context()
            RuleSourceFixture().use { fixture ->
                val registry = WebSourceRegistry(fixture.authority)
                val accounts = SourceSessionManager(fixture.authority)
                val db = Room.databaseBuilder(host, NextVolDatabase::class.java, File(host.filesDir, "library.db").path)
                    .allowMainThreadQueries().build()
                val downloads = indi.renakoni.nextvol.data.download.BookDownloadStore(host, db, ContentJsonDecoder(ContentComponentRegistry()))
                var service = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner, downloads = downloads)
                try {
                    val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                    val source = install(service, fixture.raw(), fixture.server.url("/").toString())
                    val reference = service.definitions.list().single().reference()
                    val book = SourceBookId(source, fixture.server.url("/book/one").toString())
                    val id = java.util.UUID.randomUUID().toString()
                    val queued = SourceBookId(source, fixture.server.url("/book/queued").toString())
                    val queuedId = java.util.UUID.randomUUID().toString()
                    downloads.queueTask(queued, 0, queuedId)
                    downloads.queueTask(book, 0, id)
                    val task = downloads.startTask(book, 0, id, 0)
                    downloads.deferTaskRetry(task, 0, Long.MAX_VALUE, indi.renakoni.nextvol.data.download.DownloadFailure.Network)
                    when (action) {
                        "remove" -> { service.remove(source); service.activate(reference, grants) }
                        "disable" -> { service.setPreferences(source, enabled = false); service.setPreferences(source, enabled = true) }
                        "rotate" -> { service.rotateAccount(source) }
                        "account" -> {
                            val account = accounts.begin(source)
                            withTimeout(5000) { registry.sources.first { sources -> sources.any {
                                it.metadata.id == source && it.metadata.accountGeneration == account.generation
                            } } }
                        }
                        "restart" -> {
                            service.stop()
                            service = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner, downloads = downloads)
                            service.restore()
                        }
                    }
                    val owner = downloads.entry(book)!!
                    if (action == "restart") {
                        assertEquals(queuedId, downloads.entry(queued)!!.taskWorkId)
                        assertEquals("Queued", downloads.entry(queued)!!.taskStatus)
                        assertEquals(0, downloads.entry(queued)!!.taskRetryCount)
                        assertEquals(id, owner.taskWorkId)
                        assertEquals("WaitingRetry", owner.taskStatus)
                        assertEquals(1, owner.taskRetryCount)
                    } else {
                        assertTrue("$action must revoke the task before its first execution",
                            runCatching { downloads.startTask(queued, 0, queuedId, 0) }.exceptionOrNull() is CancellationException)
                        assertEquals("Cancelled", downloads.entry(queued)!!.taskStatus)
                        assertEquals("", owner.taskWorkId)
                        assertEquals("Cancelled", owner.taskStatus)
                        assertEquals("SourceUnavailable", owner.taskError)
                        assertTrue(runCatching { downloads.startTask(book, 0, id, 1) }.exceptionOrNull() is CancellationException)
                        assertTrue(runCatching { downloads.startTask(book, 0, id, 1, legacy = true) }.exceptionOrNull() is CancellationException)
                        assertTrue(runCatching { downloads.taskStage(task, indi.renakoni.nextvol.data.download.DownloadStage.Body) }
                            .exceptionOrNull() is CancellationException)
                    }
                    assertEquals(0, fixture.server.requestCount)
                } finally { service.stop(); db.close(); host.filesDir.deleteRecursively() }
            }
        }
    }

    @Test fun importedSourcesUseHostRepositoryRoomCacheWorkerAndOfflineReaderContract() = runBlocking {
        val context = context()
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val service = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val db = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
            val downloads = indi.renakoni.nextvol.data.download.BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
            val cache = coil3.disk.DiskCache.Builder().directory(okio.Path.Companion.run {
                File(context.filesDir, "images").path.toPath()
            }).maxSizeBytes(1024 * 1024).build()
            val loader = coil3.ImageLoader.Builder(context).diskCache(cache).components {
                add(indi.renakoni.nextvol.data.image.SourceImageInterceptor(registry, context, downloads))
                add(indi.renakoni.nextvol.data.image.SourceImageFetcher.Factory())
            }.build()
            coil3.SingletonImageLoader.setUnsafe(loader)
            fixture.imageBytes = java.io.ByteArrayOutputStream().also {
                android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray().reversedArray()
            try {
                // A native source can coexist; no rule request consults the browsing selection.
                registry.register(EmptyWebDataSource, SourceMetadata(WebDataSourceItem(EmptyWebDataSource.id, "Native", "fixture"), emptySet()))
                val a = install(service, fixture.raw("A"), fixture.server.url("/").toString())
                val b = install(service, fixture.raw("B"), fixture.server.url("/").toString())
                val local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
                val work = mockk<androidx.work.WorkManager>()
                val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, work, local.aliases)
                val shelves = BookshelfRepository(db.bookshelfDao(), scheduler, registry, local.aliases)
                val components = ContentComponentRegistry()
                val text = TextProcessingRepository(mockk(relaxed = true), mockk(relaxed = true), components)
                val chapters = ChapterRepository(registry, local, text, mockk(), downloads, indi.renakoni.nextvol.data.book.BookDirectoryChecks(registry, local, downloads, org.robolectric.RuntimeEnvironment.getApplication()))
                val books = BookRepository(local, shelves, text, work, chapters, BookReadingDataRepository(local), registry, downloads, mockk(), scheduler)
                shelves.addBookshelf(Bookshelf(id = 1, name = "Both sources"))
                val bound = mutableListOf<SourceBookId>()
                for (sourceId in listOf(a, b)) {
                    val runtime = (registry.resolve(sourceId) as SourceResolution.Ready).runtime
                    val search = runtime.search.search(runtime.search.searchTypes.single(), "same").toList()
                    val remote = (search.first() as SearchResult.MultipleBook).bookId
                    val book = SourceBookId(sourceId, remote)
                    bound += book
                    val info = books.getBookInformationFlow(book).last().get()!!
                    assertEquals("Same title", info.title)
                    shelves.addBookIntoBookShelf(1, info)
                    val worker = CacheBookWork(context, workerParameters(workDataOf("bookId" to book.storageKey)), mockk(relaxed = true), books, downloads)
                    assertEquals(ListenableWorker.Result.success(), worker.doWork())
                    assertTrue(books.getIsBookCached(book.storageKey))
                    val first = local.getBookVolumes(book.storageKey)!!.volumes.single().chapters.first()
                    val content = local.getChapterContent(first.id)!!
                    val data = mutableListOf<Any>()
                    ContentJsonDecoder(components).getDataFromJsonObject(content.content) { data += it }
                    assertEquals(if (sourceId == a) "A first" else "B first", (data[0] as SimpleTextComponentData).text)
                    assertTrue(data[1] is ImageComponentData)
                    assertEquals("after image", (data[2] as SimpleTextComponentData).text)
                    assertEquals(book, SourceChapterId.fromStorageKey(content.nextChapter!!).book)
                    local.updateUserReadingData(book.storageKey) { it.copy(lastReadChapterId = first.id, totalReadTime = if (sourceId == a) 99 else 30) }
                }
                assertEquals(bound.map { it.storageKey }.toSet(), shelves.getBookshelf(1)!!.allBookIds.toSet())
                assertEquals(bound[0].remoteId, bound[1].remoteId)
                val before = fixture.documents.get()
                service.setPreferences(a, enabled = false); service.setPreferences(b, enabled = false)
                assertTrue(registry.resolve(a) is SourceResolution.Missing)
                assertTrue(registry.resolve(b) is SourceResolution.Missing)
                loader.memoryCache?.clear()
                for (book in bound) {
                    val chapter = local.getBookVolumes(book.storageKey)!!.volumes.single().chapters.first()
                    assertEquals(local.getChapterContent(chapter.id), books.getChapterContentFlow(chapter.id, book.storageKey).last().get())
                    val image = loader.execute(coil3.request.ImageRequest.Builder(context)
                        .data(indi.renakoni.nextvol.data.image.SourceImage(book, fixture.server.url("/image.png").toString())).build())
                    assertTrue(image.toString(), image is coil3.request.SuccessResult)
                    assertEquals(coil3.decode.DataSource.DISK, (image as coil3.request.SuccessResult).dataSource)
                }
                assertEquals(before, fixture.documents.get())
                assertEquals(30, local.getUserReadingData(bound[1].storageKey).totalReadTime)
                service.setPreferences(a, enabled = true)
                assertEquals(a, (registry.resolve(a) as SourceResolution.Ready).runtime.id)
                assertEquals(bound.map { it.storageKey }.toSet(), shelves.getBookshelf(1)!!.allBookIds.toSet())
                assertEquals(99, local.getUserReadingData(bound[0].storageKey).totalReadTime)
                service.remove(a); service.remove(b)
                assertTrue(books.getIsBookCached(bound[0].storageKey))
            } finally { service.stop(); registry.unregister(EmptyWebDataSource.id); db.close(); loader.shutdown(); cache.shutdown(); coil3.SingletonImageLoader.reset() }
        }
    }

    @Test fun activationRestoresApprovedSnapshotAndAccountRotationRevokesOldRuntime() = runBlocking {
        val context = context()
        RuleSourceFixture().use { fixture ->
            val epochs = SourceSessionEpochStore.Memory()
            var registry = WebSourceRegistry(fixture.authority)
            var accounts = SourceSessionManager(fixture.authority, epochs)
            var service = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            try {
                val id = install(service, fixture.raw(), fixture.server.url("/").toString())
                val old = (registry.resolve(id) as SourceResolution.Ready).runtime
                val retired = service.loginTarget(id).session
                retired.write(StorageRequest(StorageArea.Cache, "value:token", "synthetic-account-a", ttlMillis = 0))
                retired.write(StorageRequest(StorageArea.Cache, "file:favorites", "[1,2]", ttlMillis = 0))
                retired.write(StorageRequest(StorageArea.Config, "value:theme", "dark"))
                val login = accounts.begin(id)
                withTimeout(5000) { registry.sources.first { sources -> sources.any { it.metadata.id == id && it.metadata.accountGeneration == login.generation } } }
                assertFalse(old.isAvailable)
                val replacement = service.loginTarget(id).session
                assertEquals(StorageResult.Value(null), replacement.read(StorageRequest(StorageArea.Cache, "value:token")))
                assertEquals(StorageResult.Value(null), replacement.read(StorageRequest(StorageArea.Cache, "file:favorites")))
                assertTrue(runCatching { retired.write(StorageRequest(StorageArea.Cache, "value:token", "late")) }.isFailure)
                service.stop()
                registry = WebSourceRegistry(fixture.authority)
                accounts = SourceSessionManager(fixture.authority, epochs)
                service = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                service.restore()
                val restored = (registry.resolve(id) as SourceResolution.Ready).runtime
                assertEquals(login.generation, restored.metadata.accountGeneration)
                val restarted = service.loginTarget(id).session
                assertEquals(StorageResult.Value(null), restarted.read(StorageRequest(StorageArea.Cache, "value:token")))
                assertEquals(StorageResult.Value(null), restarted.read(StorageRequest(StorageArea.Cache, "file:favorites")))
                assertEquals(StorageResult.Value("dark"), restarted.read(StorageRequest(StorageArea.Config, "value:theme")))
                assertEquals("Same title", restored.getBookInformation(fixture.server.url("/book/one").toString()).get()!!.title)
                service.remove(id)
                service.stop()
                service = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                service.restore()
                assertFalse(service.restorationFailed)
            } finally { service.stop() }
        }
    }

    @Test fun corruptActivationSnapshotDoesNotCrashStartupOrBecomeEmptyWritableState() = runBlocking {
        val context = context()
        File(context.filesDir, "rule-sources/active.json").apply { parentFile!!.mkdirs(); writeText("{broken") }
        RuleSourceFixture().use { fixture ->
            val service = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                service.restore()
                assertTrue(service.restorationFailed)
                try { install(service, fixture.raw(), fixture.server.url("/").toString()); fail("Must preserve corrupt snapshot for recovery") }
                catch (_: IllegalStateException) { }
                assertEquals("{broken", File(context.filesDir, "rule-sources/active.json").readText())
                assertEquals(0, fixture.documents.get())
            } finally { service.stop() }
        }
    }

    @Test fun collectionActivationRestoresMoreThan256SourcesAndReportsABindingFailure() = runBlocking {
        val host = context()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val raw = kotlinx.serialization.json.JsonArray((0 until 300).map { index -> JsonObject(fixture.raw() + mapOf(
                    "bookSourceUrl" to kotlinx.serialization.json.JsonPrimitive(fixture.server.url("/source/$index").toString()),
                    "enabled" to kotlinx.serialization.json.JsonPrimitive(index != 0))) })
                val preview = sources.importer.preview(raw.toString(), AUTO_PROFILE)
                val committed = sources.importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
                assertNull(committed.error)
                val grants = committed.items.mapIndexed { index, item -> item.reference!! to listOf(NetworkGrant(
                    if (index == 299) "invalid origin" else fixture.server.url("/").toString(), true)) }.toMap()
                assertEquals(299, sources.activateBatch(grants).size)
                assertEquals(300, sources.installedSources().size)
                assertEquals(298, registry.sources.value.size)
                val snapshot = registry.sources.value
                sources.stop()
                registry = spyk(WebSourceRegistry(fixture.authority), recordPrivateCalls = true)
                sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
                sources.restore()
                assertFalse(sources.restorationFailed)
                assertEquals(300, sources.installedSources().size)
                assertEquals(298, registry.sources.value.size)
                assertEquals(snapshot, registry.sources.value)
                verify(exactly = 1) { registry["publish"]() }
                assertEquals(0, fixture.documents.get())
            } finally { sources.stop(); host.filesDir.deleteRecursively() }
        }
    }

    @Test fun persistedAccountEpochsSurviveManagerRecreationWithoutColonKeyCollisions() {
        val context = context()
        val a = Identifier("fixture:epoch", "x")
        val b = Identifier("fixture", "epoch:x")
        val store = indi.renakoni.nextvol.di.WebDataSourceModule.provideSourceSessionEpochStore(context)
        val manager = SourceSessionManager(hnovel.execution.ExecutionAuthority(), store)
        val initialA = manager.current(a).generation
        val initialB = manager.current(b).generation
        manager.begin(a)
        val next = SourceSessionManager(hnovel.execution.ExecutionAuthority(),
            indi.renakoni.nextvol.di.WebDataSourceModule.provideSourceSessionEpochStore(context))
        assertEquals(initialA + 1, next.current(a).generation)
        assertEquals(initialB, next.current(b).generation)
        assertEquals(initialA + 2, next.begin(a).generation)
    }
}
