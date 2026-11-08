package indi.renakoni.nextvol.data.web

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryProvider
import io.nightfish.lightnovelreader.api.web.discovery.DiscoverySection
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class WebSourceRegistryTest {
    @Test(timeout = 10000)
    fun overlappingDetailRequestsHandOffTheirMatchingInformationAndDirectory() = runBlocking {
        for (informationFirst in listOf(true, false)) {
            val registry = WebSourceRegistry()
            val meta = metadata("handoff")
            val infoEntered = CompletableDeferred<Unit>()
            val infoRelease = CompletableDeferred<Unit>()
            val directoryEntered = CompletableDeferred<Unit>()
            val directoryRelease = CompletableDeferred<Unit>()
            val infoCalls = AtomicInteger()
            val directoryCalls = AtomicInteger()
            val information = io.nightfish.lightnovelreader.api.book.BookInformation("book", "Title",
                author = "", description = "", publishingHouse = "", wordCount = io.nightfish.lightnovelreader.api.book.WordCount(0),
                lastUpdated = java.time.LocalDateTime.MIN, isComplete = false)
            val volumes = BookVolumes("book", listOf(io.nightfish.lightnovelreader.api.book.Volume("v", "",
                listOf(io.nightfish.lightnovelreader.api.book.ChapterInformation("1", "One")))))
            registry.register(object : CountingSource(meta.id, cache = null) {
                override suspend fun getBookInformation(id: String) = run {
                    infoCalls.incrementAndGet(); infoEntered.complete(Unit); infoRelease.await(); Ok(information)
                }
                override suspend fun getBookVolumes(id: String) = run {
                    directoryCalls.incrementAndGet(); directoryEntered.complete(Unit); directoryRelease.await(); Ok(volumes)
                }
            }, meta)
            try {
                val runtime = registry.ready(meta.id)
                val info = async { runtime.getBookInformation("book", refresh = true) }
                infoEntered.await()
                val directory = async { runtime.getBookVolumes("book", refresh = true) }
                directoryEntered.await()
                if (informationFirst) {
                    infoRelease.complete(Unit); assertEquals(information, info.await().get())
                    directoryRelease.complete(Unit); assertEquals(volumes, directory.await().get())
                } else {
                    directoryRelease.complete(Unit); assertEquals(volumes, directory.await().get())
                    infoRelease.complete(Unit); assertEquals(information, info.await().get())
                }
                assertEquals(volumes, runtime.getBookVolumes("book", refresh = true, reuseRecent = true).get())
                kotlinx.coroutines.withContext(DownloadSourceRequest()) {
                    assertEquals(information, runtime.getBookInformation("book", refresh = true).get())
                    assertEquals(volumes, runtime.getBookVolumes("book", refresh = true, reuseRecent = true).get())
                }
                assertEquals(1, infoCalls.get())
                assertEquals(if (informationFirst) 1 else 2, directoryCalls.get())
            } finally {
                infoRelease.complete(Unit); directoryRelease.complete(Unit)
                registry.unregister(meta.id)
            }
        }
    }

    @Test fun removalRevokesOnlyItsNamespaceAndStaleRegistrationsCannotRevokeReplacements() {
        val authority = hnovel.execution.ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val source = metadata("rules")
        val oldRegistration = registry.register(source) { CountingSource(source.id) }
        val old = authority.issue("rules", "legado", "1", "fixture")
        val other = authority.issue("rules", "legado", "1", "another-namespace")
        oldRegistration.unregister()
        assertFalse(authority.accepts(old))
        assertTrue(authority.accepts(other))
        val replacement = registry.register(source) { CountingSource(source.id) }
        val fresh = authority.issue("rules", "legado", "2", "fixture")
        oldRegistration.unregister()
        assertTrue(authority.accepts(fresh))
        registry.unregister(source.id)
        assertFalse(authority.accepts(fresh))
        assertTrue(authority.accepts(other))
        replacement.unregister()
    }

    private fun metadata(name: String, builtIn: Boolean = false) = SourceMetadata(
        WebDataSourceItem(Identifier("fixture", name), "Same display name", "fixture"),
        setOf(SourceCapability.Directory, SourceCapability.ChapterContent), builtIn,
    )

    @Test(timeout = 10000)
    fun replacementRetiresOldRuntimeBeforeObserversSeeTheNewGeneration() = runBlocking {
        val authority = hnovel.execution.ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val before = metadata("replacement").copy(revision = "1", accountGeneration = 1)
        val registration = registry.register(CountingSource(before.id), before)
        val old = registry.ready(before.id)
        val after = before.copy(revision = "2", accountGeneration = 2)
        val ticket = authority.issue(after.id.id, "legado", after.revision, after.id.namespace, after.accountGeneration)
        // Observe inline at publication so the test does not depend on IO-thread timing.
        val oldAvailableAtPublication = async(Dispatchers.Unconfined) {
            registry.sources.first { sources -> sources.any { it.metadata == after } }
            old.isAvailable
        }
        try {
            registry.replace(registration, CountingSource(after.id), after, ticket) {}
            assertFalse(oldAvailableAtPublication.await())
            assertEquals(after, registry.ready(after.id).metadata)
            assertThrows(SourceUnavailableException::class.java) { old.imageHeaders() }
            Unit
        } finally {
            oldAvailableAtPublication.cancelAndJoin()
            registry.unregister(before.id)
        }
    }

    @Test(timeout = 10000)
    fun removalRetiresOldRuntimeBeforeInlineObserversSeeTheSourceDisappear() = runBlocking {
        for (byRegistration in listOf(false, true)) {
            val registry = WebSourceRegistry()
            val meta = metadata("removal")
            val registration = registry.register(CountingSource(meta.id), meta)
            val runtime = registry.ready(meta.id)
            val availableAtPublication = async(Dispatchers.Unconfined) {
                registry.sources.first { it.isEmpty() }
                runtime.isAvailable
            }
            try {
                if (byRegistration) registration.unregister() else registry.unregister(meta.id)
                assertFalse(availableAtPublication.await())
                assertTrue(registry.resolve(meta.id) is SourceResolution.Missing)
            } finally { availableAtPublication.cancelAndJoin(); registry.unregister(meta.id) }
        }
    }

    @Test(timeout = 10000)
    fun failedReplacementPersistenceKeepsTheOldRuntimeAndSnapshotAvailable() = runBlocking {
        val authority = hnovel.execution.ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val before = metadata("replacement").copy(revision = "1")
        val registration = registry.register(CountingSource(before.id), before)
        val old = registry.ready(before.id)
        val snapshot = registry.sources.value
        val after = before.copy(revision = "2")
        val ticket = authority.issue(after.id.id, "legado", after.revision, after.id.namespace, after.accountGeneration)
        val candidate = CountingSource(after.id)
        try {
            assertThrows(IllegalStateException::class.java) {
                registry.replace(registration, candidate, after, ticket) { error("Cannot persist replacement") }
            }
            assertTrue(old.isAvailable)
            assertSame(old, registry.ready(before.id))
            assertEquals(snapshot, registry.sources.value)
        } finally {
            authority.revoke(ticket)
            candidate.close()
            registry.unregister(before.id)
        }
    }

    @Test(timeout = 10000)
    fun registrationIsLazySnapshotsAreStableAndDifferentAdaptersUseTheSameConsumer() = runBlocking {
        val registry = WebSourceRegistry()
        val native = CountingSource(metadata("native").id)
        val ruleAdapter = CountingSource(metadata("rules").id)
        var constructions = 0
        registry.register(metadata("rules")) { constructions++; ruleAdapter }
        val oldSnapshot = registry.sources.value
        registry.register(native, metadata("native", builtIn = true))
        try {
            assertEquals(0, constructions)
            assertEquals(0, native.loads.get())
            assertEquals(0, ruleAdapter.loads.get())
            assertEquals(listOf("rules"), oldSnapshot.map { it.metadata.id.id })
            assertEquals(listOf("native", "rules"), registry.sources.value.map { it.metadata.id.id })
            assertTrue(registry.sources.value.all { it.status == SourceStatus.Registered })
            val first = registry.ready(native.id)
            val same = registry.ready(native.id)
            assertSame(first, same)
            assertEquals(1, native.loads.get())
            assertEquals(0, constructions)
            for (id in listOf(native.id, ruleAdapter.id)) {
                assertEquals(Ok(BookVolumes("same", emptyList())), registry.ready(id).getBookVolumes("same"))
            }
            assertEquals(1, constructions)
            assertEquals(1, ruleAdapter.loads.get())
        } finally {
            registry.unregister(native.id)
            registry.unregister(ruleAdapter.id)
        }
    }

    @Test(timeout = 10000)
    fun duplicateAndMismatchedIdentityAreRejectedWithoutReplacingTheOriginal() = runBlocking {
        val registry = WebSourceRegistry()
        val source = CountingSource(metadata("a").id)
        val capabilities = mutableSetOf(SourceCapability.Directory)
        registry.register(source, metadata("a").copy(capabilities = capabilities))
        capabilities.clear()
        try {
            assertEquals(setOf(SourceCapability.Directory), registry.sources.value.single().metadata.capabilities)
            assertThrows(IllegalArgumentException::class.java) { registry.register(metadata("a")) { error("Must not construct") } }
            assertThrows(IllegalArgumentException::class.java) { registry.register(source, metadata("different")) }
            assertEquals(1, registry.sources.value.size)
            assertEquals(source.id, registry.ready(source.id).id)
        } finally { registry.unregister(source.id) }
    }

    @Test(timeout = 10000)
    fun oneInitializationIsSharedAndFailureDoesNotReloadOrAffectAnotherSource() = runBlocking {
        val registry = WebSourceRegistry()
        val broken = object : CountingSource(metadata("broken").id) {
            override fun onLoad() { super.onLoad(); error("fixture initialization failure") }
        }
        val good = CountingSource(metadata("good").id)
        registry.register(broken, metadata("broken"))
        registry.register(good, metadata("good"))
        try {
            val resolves = List(5) { async { registry.resolve(broken.id) } }
            resolves.forEach { assertTrue(it.await() is SourceResolution.Unavailable) }
            assertEquals(1, broken.loads.get())
            assertEquals(1, broken.closes.get())
            assertTrue(registry.resolve(broken.id) is SourceResolution.Unavailable)
            assertEquals(1, broken.loads.get())
            assertEquals(SourceStatus.Failed, registry.sources.value.first { it.metadata.id == broken.id }.status)
            assertEquals(good.id, registry.ready(good.id).id)
        } finally {
            registry.unregister(broken.id)
            registry.unregister(good.id)
        }
    }

    @Test(timeout = 10000)
    fun sameRemoteIdsAndEvenOneSharedCacheDoNotShareResponses() = runBlocking {
        val registry = WebSourceRegistry()
        val shared = Cache()
        val a = CountingSource(metadata("a").id, shared)
        val b = CountingSource(metadata("b").id, shared)
        registry.register(a, metadata("a"))
        registry.register(b, metadata("b"))
        try {
            val ra = registry.ready(a.id)
            val rb = registry.ready(b.id)
            repeat(2) {
                assertEquals("a", ra.getChapterContent("same", "same").get()!!.title)
                assertEquals("b", rb.getChapterContent("same", "same").get()!!.title)
            }
            assertEquals(1, a.chapters.get())
            assertEquals(1, b.chapters.get())
            assertTrue(shared.cacheMap.isEmpty())
        } finally {
            registry.unregister(a.id)
            registry.unregister(b.id)
        }
    }

    @Test(timeout = 10000)
    fun removalCancelsRunningRequestsInvalidatesCacheAndNeverResolvesTheReplacementForOldHandles() = runBlocking {
        val registry = WebSourceRegistry()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val old = object : CountingSource(metadata("a").id) {
            override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, io.nightfish.lightnovelreader.api.error.WebRequestError> {
                started.complete(Unit)
                try { CompletableDeferred<Unit>().await() } finally { cancelled.complete(Unit) }
                return Ok(BookVolumes(id, emptyList()))
            }
        }
        registry.register(old, metadata("a"))
        val runtime = registry.ready(old.id)
        runtime.getChapterContent("cached", "book")
        val pending = async { runtime.getBookVolumes("same") }
        started.await()
        registry.unregister(old.id)
        try {
            withTimeout(3000) { cancelled.await() }
            pending.cancelAndJoin()
            assertTrue(registry.resolve(old.id) is SourceResolution.Missing)
            assertFalse(runtime.isAvailable)
            assertTrue(registry.sources.value.isEmpty())
            assertThrows(SourceUnavailableException::class.java) { runBlocking { runtime.getChapterContent("cached", "book") } }
            val replacement = CountingSource(old.id)
            registry.register(replacement, metadata("a"))
            assertNotSame(runtime, registry.ready(old.id))
            assertThrows(SourceUnavailableException::class.java) { runtime.imageHeaders() }
            withTimeout(3000) { old.closed.await() }
            assertEquals(1, old.closes.get())
        } finally { registry.unregister(old.id) }
    }

    @Test(timeout = 10000)
    fun cancellingOneCallerDoesNotCancelTheRuntime() = runBlocking {
        val registry = WebSourceRegistry()
        val started = CompletableDeferred<Unit>()
        val source = object : CountingSource(metadata("a").id) {
            override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, io.nightfish.lightnovelreader.api.error.WebRequestError> {
                if (id == "wait") { started.complete(Unit); CompletableDeferred<Unit>().await() }
                return Ok(BookVolumes(id, emptyList()))
            }
        }
        registry.register(source, metadata("a"))
        try {
            val runtime = registry.ready(source.id)
            val pending = async { runtime.getBookVolumes("wait") }
            started.await()
            pending.cancelAndJoin()
            assertTrue(runtime.isAvailable)
            assertEquals(Ok(BookVolumes("next", emptyList())), runtime.getBookVolumes("next"))
            assertEquals(1, source.loads.get())
        } finally { registry.unregister(source.id) }
    }

    @Test(timeout = 10000)
    fun ambiguousChapterPairsAreNotCoalescedAndUnrelatedSourcesHaveIndependentPermits() = runBlocking {
        val registry = WebSourceRegistry()
        val arrivals = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        fun source(name: String) = object : CountingSource(metadata(name).id) {
            override val permits = 1
            override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, io.nightfish.lightnovelreader.api.error.WebRequestError> {
                arrivals.send("$name:$chapterId/$bookId")
                release.await()
                return Ok(ChapterContent(chapterId, bookId, buildJsonObject {}))
            }
        }
        val a = source("a")
        val b = source("b")
        registry.register(a, metadata("a"))
        registry.register(b, metadata("b"))
        try {
            val ra = registry.ready(a.id)
            val rb = registry.ready(b.id)
            val first = async { ra.getChapterContent("ab", "c") }
            assertEquals("a:ab/c", arrivals.receive())
            val second = async(start = CoroutineStart.UNDISPATCHED) { ra.getChapterContent("a", "bc") }
            val independent = async { rb.getChapterContent("ab", "c") }
            assertEquals("b:ab/c", withTimeout(3000) { arrivals.receive() })
            release.complete(Unit)
            assertEquals("c", first.await().get()!!.title)
            assertEquals("bc", second.await().get()!!.title)
            independent.await()
            assertEquals("a:a/bc", arrivals.receive())
        } finally {
            release.complete(Unit)
            registry.unregister(a.id)
            registry.unregister(b.id)
        }
    }

    private suspend fun WebSourceRegistry.ready(id: Identifier) = (resolve(id) as SourceResolution.Ready).runtime

    @Test(timeout = 10000)
    fun removingSourceStopsSearchAndDiscoveryWithoutCancellingTheirConsumer() = runBlocking {
        val registry = WebSourceRegistry()
        val searchResult = SearchResult.MultipleBook("book")
        val search = mockk<SearchProvider>()
        every { search.search(any(), any()) } returns flow {
            emit(searchResult)
            awaitCancellation()
        }
        val pageObserved = CompletableDeferred<Unit>()
        val explore = object : DiscoveryProvider {
            override val hasFeed = true
            override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> {
                pageObserved.complete(Unit)
                awaitCancellation()
            }
        }
        val source = object : CountingSource(metadata("observed").id) {
            override val searchProvider = search
            override val discoveryProvider = explore
        }
        registry.register(source, metadata("observed").copy(capabilities = setOf(SourceCapability.Explore)))
        try {
            val runtime = registry.ready(source.id)
            val oldPage = requireNotNull(runtime.discovery)
            val searchObserved = CompletableDeferred<Unit>()
            val searching = launch {
                runtime.search.search(mockk(), "keyword").collect {
                    assertSame(searchResult, it)
                    searchObserved.complete(Unit)
                }
            }
            val browsing = launch {
                oldPage.feed()
            }
            searchObserved.await()
            pageObserved.await()
            registry.unregister(source.id)
            withTimeout(3000) { searching.join(); browsing.join() }
            assertTrue(searching.isCancelled)
            assertTrue(browsing.isCancelled)
            assertTrue(isActive)
            assertThrows(SourceUnavailableException::class.java) {
                runBlocking { oldPage.feed() }
            }
            Unit
        } finally { registry.unregister(source.id) }
    }

    @Test(timeout = 10000)
    fun removingAnAlreadyConstructedButNeverLoadedSourceStillClosesItsResources() = runBlocking {
        val registry = WebSourceRegistry()
        val source = CountingSource(metadata("unloaded").id)
        registry.register(source, metadata("unloaded"))
        registry.unregister(source.id)
        registry.unregister(source.id)
        withTimeout(3000) { source.closed.await() }
        assertEquals(0, source.loads.get())
        assertEquals(1, source.closes.get())
        assertTrue(registry.resolve(source.id) is SourceResolution.Missing)
    }

    @Test(timeout = 10000)
    fun removingDuringInitializationCannotPublishOverANewRegistration() = runBlocking {
        val registry = WebSourceRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = object : CountingSource(metadata("a").id) {
            override fun onLoad() {
                super.onLoad()
                runBlocking { started.complete(Unit); release.await() }
            }
        }
        registry.register(old, metadata("a"))
        val pending = async { registry.resolve(old.id) }
        try {
            started.await()
            registry.unregister(old.id)
            val replacement = CountingSource(old.id)
            registry.register(replacement, metadata("a"))
            val current = registry.ready(old.id)
            release.complete(Unit)
            assertTrue(pending.await() is SourceResolution.Missing)
            withTimeout(3000) { old.closed.await() }
            assertSame(current, registry.ready(old.id))
            assertEquals(SourceStatus.Ready, registry.sources.value.single().status)
            assertEquals(1, old.closes.get())
        } finally {
            release.complete(Unit)
            registry.unregister(old.id)
        }
    }

    @Test(timeout = 10000)
    fun factoryIdentityMismatchIsUnavailableAndClosesTheUnpublishedSource() = runBlocking {
        val registry = WebSourceRegistry()
        val wrong = CountingSource(metadata("wrong").id)
        registry.register(metadata("expected")) { wrong }
        try {
            assertTrue(registry.resolve(metadata("expected").id) is SourceResolution.Unavailable)
            assertEquals(0, wrong.loads.get())
            assertEquals(1, wrong.closes.get())
            assertTrue(registry.resolve(wrong.id) is SourceResolution.Missing)
        } finally { registry.unregister(metadata("expected").id) }
    }

    private open class CountingSource(override val id: Identifier, override val cache: Cache? = Cache()) :
        WebBookDataSource by EmptyWebDataSource, AutoCloseable {
        val loads = AtomicInteger()
        val chapters = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override val permits = 4
        override fun onLoad() { loads.incrementAndGet() }
        override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, io.nightfish.lightnovelreader.api.error.WebRequestError> = Ok(BookVolumes(id, emptyList()))
        override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, io.nightfish.lightnovelreader.api.error.WebRequestError> {
            chapters.incrementAndGet()
            return Ok(ChapterContent(chapterId, id.id, buildJsonObject {}))
        }
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
    }
}
