package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.*
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeViewModel
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ExploreHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    private val text = mockk<TextProcessingRepository> {
        every { processExploreBooksRow(any()) } answers { firstArg() }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private fun model() =
        ExploreHomeViewModel(registry, accounts, SavedStateHandle(), text, SourceBrowseSettings(org.robolectric.RuntimeEnvironment.getApplication())).also {
            stores += ViewModelStore().apply { put("explore", it) }
            it.setActive(true)
        }
    private fun add(name: String, provider: DiscoveryProvider, builtIn: Boolean = false,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Search)): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities, builtIn))
        return id
    }
    private open class Feed : DiscoveryProvider {
        override val hasFeed = true
        override val hasCategories = true
        var feeds = 0
        var catalogs = 0
        var failure: DiscoveryError? = null
        override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> {
            feeds++
            return failure?.let { Err(it) } ?: Ok(listOf(DiscoverySection("same", "Same list",
                listOf(DiscoveryBook("same", "Book")), "all")))
        }
        override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> {
            catalogs++
            return Ok(listOf(DiscoveryCategory("all", "All", "all")))
        }
    }

    @Test fun pendingEntriesKeepTheirLoadingStateAndCanOpenBeforeBooksArrive() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val entries = listOf("First", "Second").map { title ->
            DiscoverySection(title, title, emptyList(), "/$title", previewLoading = true)
        }
        val id = add("pending", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                emit(Ok(entries))
                finish.await()
                emit(Ok(entries.map { it.copy(previewLoading = false) }))
            }
        })
        val model = model()
        runCurrent()
        val pending = model.state.value.content.getValue(id)
        assertEquals(listOf("First", "Second"), pending.sections.map { it.title })
        assertTrue(pending.sections.all { it.previewLoading && it.books.isEmpty() })
        assertEquals(id, pending.sections.first().more!!.sourceId)
        assertEquals("/First", model.more(pending.sections.first())!!.target)
        finish.complete(Unit)
        advanceUntilIdle()
        val completed = model.state.value.content.getValue(id)
        assertTrue(completed.loaded)
        assertFalse(completed.loading)
        assertTrue(completed.sections.none { it.previewLoading })
    }

    @Test fun firstPreviewIsUsableBeforeLaterModulesAndKeepsScrollUntilCompletion() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val first = DiscoverySection("first", "First", listOf(DiscoveryBook("one", "Book one")), "/first")
        val second = DiscoverySection("second", "Second", emptyList(), "/second")
        val id = add("progressive", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                emit(Ok(listOf(first)))
                finish.await()
                emit(Ok(listOf(first, second)))
            }
        })
        val model = model()
        runCurrent()
        val partial = model.state.value.content.getValue(id)
        assertEquals(listOf("First"), partial.sections.map { it.title })
        assertTrue(partial.loading)
        assertFalse(partial.loaded)
        assertEquals(id, partial.sections.single().books.single().id.sourceId)
        assertEquals("/first", model.more(partial.sections.single())!!.target)
        io.mockk.verify { text.processExploreBooksRow(match { it.title == "Book one" }) }
        model.scroll(id, DiscoveryScroll(1, 17))
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertEquals(listOf("First", "Second"), complete.sections.map { it.title })
        assertFalse(complete.loading)
        assertTrue(complete.loaded)
        assertEquals(DiscoveryScroll(1, 17), complete.scroll)
    }

    @Test fun previewDiagnosticSurvivesSnapshotsAndItsEntryCanOpenBeforeCompletion() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val failure = DiscoveryPreviewFailure(DiscoveryError.PermissionDenied, "ruleExplore.bookList",
            DiscoveryPermission("https://ungranted.test:443", "Document"))
        val broken = DiscoverySection("broken", "Broken", emptyList(), "/broken", "broken-category", failure)
        val working = DiscoverySection("working", "Working", listOf(DiscoveryBook("book", "Book")), "/working")
        val id = add("progressive", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                emit(Ok(listOf(broken)))
                finish.await()
                emit(Ok(listOf(broken, working)))
            }
        })
        val model = model()
        runCurrent()
        val partial = model.state.value.content.getValue(id)
        assertEquals(failure, partial.sections.single().previewFailure)
        assertTrue(partial.loading)
        assertNull(partial.error)
        val route = model.more(partial.sections.single())!!
        assertEquals(id.namespace, route.namespace)
        assertEquals(id.id, route.sourceId)
        assertEquals("/broken", route.target)
        assertEquals("broken-category", route.categoryId)
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertEquals(listOf("Broken", "Working"), complete.sections.map { it.title })
        assertEquals(failure, complete.sections.first().previewFailure)
        assertNull(complete.sections.last().previewFailure)
        assertEquals(id, complete.sections.last().books.single().id.sourceId)
        assertTrue(complete.loaded)
        assertFalse(complete.loading)
        assertNull(complete.error)
        assertNull(complete.errorField)
        assertNull(complete.errorPermission)
    }

    @Test fun laterModuleFailureKeepsPartialResultsAndRetryReplacesWithoutDuplicates() = runTest(dispatcher) {
        var fail = true
        val first = DiscoverySection("first", "First", emptyList(), "/first")
        val second = DiscoverySection("second", "Second", emptyList(), "/second")
        val id = add("progressive", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                emit(Ok(listOf(first)))
                if (fail) emit(Err(DiscoveryError.Network)) else emit(Ok(listOf(first, second)))
            }
        })
        val model = model()
        advanceUntilIdle()
        val partial = model.state.value.content.getValue(id)
        assertEquals(listOf("First"), partial.sections.map { it.title })
        assertEquals(DiscoveryError.Network, partial.error)
        assertFalse(partial.loading)
        assertFalse(partial.loaded)
        fail = false
        model.refresh()
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertEquals(listOf("First", "Second"), complete.sections.map { it.title })
        assertTrue(complete.loaded)
        assertNull(complete.error)
    }

    @Test fun independentRetrySurvivesOlderFeedSnapshotsBeforeAndAfterItCompletes() = runTest(dispatcher) {
        val later = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        val daily = DiscoverySection("daily", "Daily", listOf(DiscoveryBook("day", "Day book")), "/daily")
        val weekly = DiscoverySection("weekly", "Weekly", emptyList(), "/weekly",
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network))
        val monthly = daily.copy(id = "monthly", title = "Monthly", more = "/monthly")
        var feeds = 0
        var retries = 0
        val id = add("retry", object : Feed(), DiscoveryPreviewProvider {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                feeds++
                emit(Ok(listOf(daily, weekly, monthly.copy(books = emptyList(), previewLoading = true))))
                later.await()
                emit(Ok(listOf(daily, weekly, monthly)))
                finish.await()
            }
            override suspend fun preview(id: String): Result<DiscoverySection, DiscoveryError> {
                assertEquals("weekly", id)
                retries++
                retry.await()
                return Ok(weekly.copy(books = listOf(DiscoveryBook("week", "Week book")), previewFailure = null))
            }
        })
        val model = model()
        runCurrent()
        val broken = model.state.value.content.getValue(id).sections[1]
        model.scroll(id, DiscoveryScroll(1, 12))
        model.retryPreview(broken)
        model.retryPreview(broken)
        runCurrent()
        later.complete(Unit)
        runCurrent()
        val waiting = model.state.value.content.getValue(id)
        assertTrue(waiting.sections[1].previewLoading)
        assertNull(waiting.sections[1].previewFailure)
        assertFalse(waiting.sections.last().previewLoading)
        assertEquals("Day book", waiting.sections.first().books.single().title)
        retry.complete(Unit)
        runCurrent()
        assertEquals("Week book", model.state.value.content.getValue(id).sections[1].books.single().title)
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertTrue(complete.loaded)
        assertEquals(listOf("Daily", "Weekly", "Monthly"), complete.sections.map { it.title })
        assertTrue(complete.sections.none { it.previewLoading || it.previewFailure != null })
        assertEquals(id, complete.sections[1].books.single().id.sourceId)
        assertEquals("/weekly", model.more(complete.sections[1])!!.target)
        assertEquals(DiscoveryScroll(1, 12), complete.scroll)
        assertEquals(1, feeds)
        assertEquals(1, retries)
        io.mockk.verify { text.processExploreBooksRow(match { it.title == "Week book" }) }
    }

    @Test fun navigationCancelsSingleRetryAndLateCompletionCannotReplaceItsOriginalFailure() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val broken = DiscoverySection("weekly", "Weekly", emptyList(), "/weekly",
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network))
        val id = add("a", object : Feed(), DiscoveryPreviewProvider {
            override suspend fun feed() = Ok(listOf(broken))
            override suspend fun preview(id: String) = withContext(NonCancellable) {
                finish.await()
                Ok(broken.copy(books = listOf(DiscoveryBook("late", "Late book")), previewFailure = null))
            }
        })
        val other = add("b", Feed())
        val model = model()
        runCurrent()
        val original = model.state.value.content.getValue(id).sections.single()
        model.retryPreview(original)
        runCurrent()
        assertTrue(model.state.value.content.getValue(id).sections.single().previewLoading)
        model.select(other)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(original, model.state.value.content.getValue(id).sections.single())
        model.select(id)
        runCurrent()
        assertEquals(original, model.state.value.content.getValue(id).sections.single())
        model.retryPreview(original)
        advanceUntilIdle()
        assertEquals("Late book", model.state.value.content.getValue(id).sections.single().books.single().title)
    }

    @Test fun leavingAnIncompleteFeedCancelsItAndReturningLoadsRemainingPreviews() = runTest(dispatcher) {
        var requests = 0
        var cancelled = 0
        val finish = CompletableDeferred<Unit>()
        val id = add("a", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                requests++
                emit(Ok(listOf(DiscoverySection("first", "First", emptyList()))))
                try { finish.await() } finally { if (!finish.isCompleted) cancelled++ }
                emit(Ok(listOf(DiscoverySection("complete", "Complete", emptyList()))))
            }
        })
        val other = add("b", Feed())
        val model = model()
        runCurrent()
        model.select(other)
        runCurrent()
        assertEquals(1, cancelled)
        assertFalse(model.state.value.content.getValue(id).loaded)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals("First", model.state.value.content.getValue(id).sections.single().title)
        assertEquals("Same list", model.state.value.content.getValue(other).sections.single().title)
        model.select(id)
        advanceUntilIdle()
        assertEquals(2, requests)
        assertEquals("Complete", model.state.value.content.getValue(id).sections.single().title)
    }

    @Test fun returningToAnIncompleteFeedRetainsSuccessfulBooksWhileTheSameSectionLoads() = runTest(dispatcher) {
        val first = DiscoverySection("first", "First", listOf(DiscoveryBook("one", "Book one")), "/first")
        val second = DiscoverySection("second", "Second", emptyList(), "/second", previewLoading = true)
        var loads = 0
        val finish = CompletableDeferred<Unit>()
        val id = add("resume", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                loads++
                emit(Ok(listOf(if (loads == 1) first else first.copy(books = emptyList(), previewLoading = true), second)))
                finish.await()
                emit(Ok(listOf(first, second.copy(previewLoading = false,
                    previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network)))))
            }
        })
        val model = model()
        runCurrent()
        model.scroll(id, DiscoveryScroll(1, 15))
        repeat(2) {
            model.setActive(false)
            runCurrent()
            model.setActive(true)
            runCurrent()
            assertEquals("Book one", model.state.value.content.getValue(id).sections.first().books.single().title)
        }
        val resumed = model.state.value.content.getValue(id)
        assertEquals("Book one", resumed.sections.first().books.single().title)
        assertTrue(resumed.sections.first().previewLoading)
        assertEquals("/first", model.more(resumed.sections.first())!!.target)
        assertEquals(DiscoveryScroll(1, 15), resumed.scroll)
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertTrue(complete.loaded)
        assertFalse(complete.sections.first().previewLoading)
        assertEquals("Book one", complete.sections.first().books.single().title)
        assertEquals(DiscoveryError.Network, complete.sections.last().previewFailure!!.error)
    }

    @Test fun refreshingACompletedFeedReplacesSuccessfulSectionsAndKeepsFailedSections() = runTest(dispatcher) {
        val first = DiscoverySection("first", "First", listOf(DiscoveryBook("old-first", "Old first")), "/first")
        val second = DiscoverySection("second", "Second", listOf(DiscoveryBook("old-second", "Old second")), "/second")
        val update = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var loads = 0
        val id = add("refresh", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                if (++loads == 1) { emit(Ok(listOf(first, second))); return@flow }
                val waiting = listOf(first, second).map { it.copy(books = emptyList(), previewLoading = true) }
                emit(Ok(waiting))
                update.await()
                val fresh = first.copy(books = listOf(DiscoveryBook("new-first", "New first")))
                emit(Ok(listOf(fresh, waiting[1])))
                finish.await()
                emit(Ok(listOf(fresh, second.copy(books = emptyList(),
                    previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network)))))
            }
        })
        val model = model()
        advanceUntilIdle()
        assertTrue(model.state.value.content.getValue(id).loaded)
        model.refresh()
        runCurrent()
        val waiting = model.state.value.content.getValue(id)
        assertEquals(listOf("Old first", "Old second"), waiting.sections.map { it.books.single().title })
        assertTrue(waiting.sections.all { it.previewLoading })
        update.complete(Unit)
        runCurrent()
        assertEquals(listOf("New first", "Old second"),
            model.state.value.content.getValue(id).sections.map { it.books.single().title })
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertTrue(complete.loaded)
        assertFalse(complete.sections.first().previewLoading)
        assertNull(complete.sections.first().previewFailure)
        assertEquals("New first", complete.sections.first().books.single().title)
        assertFalse(complete.sections.last().previewLoading)
        assertEquals("Old second", complete.sections.last().books.single().title)
        assertEquals(DiscoveryError.Network, complete.sections.last().previewFailure!!.error)
    }

    @Test fun changingTheEnvironmentOrAccountDuringRefreshDropsRetainedBooks() = runTest(dispatcher) {
        for (change in listOf("environment", "account")) {
            val first = DiscoverySection("first", "First", listOf(DiscoveryBook("old", "Old book")), "/first")
            var loads = 0
            val id = add(change, object : Feed() {
                override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                    if (++loads == 1) { emit(Ok(listOf(first))); return@flow }
                    emit(Ok(listOf(first.copy(books = emptyList(), previewLoading = true))))
                    awaitCancellation()
                }
            })
            val model = model()
            model.openSource(id)
            runCurrent()
            model.refresh()
            runCurrent()
            assertEquals("Old book", model.state.value.content.getValue(id).sections.single().books.single().title)
            if (change == "environment") model.environment(DiscoveryEnvironment(themeMode = "1")) else accounts.begin(id)
            model.setActive(true)
            runCurrent()
            assertTrue(change, model.state.value.content.getValue(id).sections.single().books.isEmpty())
            stores.last().clear()
            registry.unregister(id)
            runCurrent()
        }
    }

    @Test fun refreshEnvironmentAndAccountChangesDoNotRetainInterruptedBooks() = runTest(dispatcher) {
        for (change in listOf("refresh", "environment", "account")) {
            var loads = 0
            val first = DiscoverySection("first", "First", listOf(DiscoveryBook("old", "Old book")), "/first")
            val id = add(change, object : Feed() {
                override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                    loads++
                    emit(Ok(listOf(if (loads == 1) first else first.copy(books = emptyList(), previewLoading = true))))
                    awaitCancellation()
                }
            })
            val model = model()
            model.openSource(id)
            runCurrent()
            assertEquals("Old book", model.state.value.content.getValue(id).sections.single().books.single().title)
            model.setActive(false)
            runCurrent()
            when (change) {
                "refresh" -> model.refresh()
                "environment" -> model.environment(DiscoveryEnvironment(themeMode = "1"))
                else -> accounts.begin(id)
            }
            runCurrent()
            model.setActive(true)
            runCurrent()
            assertTrue(change, model.state.value.content.getValue(id).sections.single().books.isEmpty())
            stores.last().clear()
            registry.unregister(id)
            runCurrent()
        }
    }

    @Test fun accountChangeDiscardsPartialContentAndCancelsItsPendingModules() = runTest(dispatcher) {
        var requests = 0
        var cancelled = 0
        val id = add("account", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                val number = ++requests
                emit(Ok(listOf(DiscoverySection("$number", "Account $number", emptyList()))))
                try { awaitCancellation() } finally { cancelled++ }
            }
        })
        val model = model()
        runCurrent()
        val old = model.state.value.content.getValue(id)
        accounts.begin(id)
        runCurrent()
        val current = model.state.value.content.getValue(id)
        assertEquals(2, requests)
        assertEquals(1, cancelled)
        assertNotEquals(old.resetId, current.resetId)
        assertEquals("Account 2", current.sections.single().title)
        assertFalse(current.loaded)
    }

    @Test fun verificationFailureBeforePageResumesStopsLoadingAndKeepsPartialResults() = runTest(dispatcher) {
        val closeBrowser = CompletableDeferred<Unit>()
        var requests = 0
        val id = add("verified", object : Feed() {
            override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
                requests++
                emit(Ok(listOf(DiscoverySection("first", "First", emptyList()))))
                val foreground = currentCoroutineContext()[ForegroundSourceRequest]!!
                foreground.beginVerification()
                try { closeBrowser.await() } finally { foreground.endVerification() }
                // Closing/failing the owned browser may return before the reader's onStart.
                emit(Err(DiscoveryError.VerificationRequired))
            }
        })
        val model = model()
        runCurrent()
        model.setActive(false, retainBrowser = true)
        closeBrowser.complete(Unit)
        runCurrent()
        model.setActive(true)
        runCurrent()
        val current = model.state.value.content.getValue(id)
        assertFalse(current.loading)
        assertEquals(DiscoveryError.VerificationRequired, current.error)
        assertEquals("First", current.sections.single().title)
        assertEquals(1, requests)
    }

    @Test fun realTabsFilterCapabilitiesLoadOnlySelectedAndKeepVisitedFeedAndScroll() = runTest(dispatcher) {
        val aFeed = Feed()
        val zFeed = Feed()
        val a = add("a", aFeed)
        val z = add("z", zFeed, builtIn = true)
        add("search-only", Feed(), capabilities = setOf(SourceCapability.Search))
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(z, a), model.state.value.sources.map { it.metadata.id })
        assertEquals(0, aFeed.feeds)
        assertEquals(1, zFeed.feeds)
        assertEquals(0, zFeed.catalogs) // Native feed does not preload the category directory.
        model.scroll(z, DiscoveryScroll(3, 17))
        model.select(a)
        advanceUntilIdle()
        model.select(z)
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(1, zFeed.feeds)
        assertEquals(1, aFeed.feeds)
        assertEquals(DiscoveryScroll(3, 17), model.state.value.content[z]!!.scroll)
    }

    @Test fun failuresAndRetryRemainLocalAndFailedRefreshRetainsVisibleBooks() = runTest(dispatcher) {
        val feed = Feed()
        val a = add("a", feed)
        val b = add("b", Feed())
        val model = model()
        advanceUntilIdle()
        val shown = model.state.value.content[a]!!.sections
        feed.failure = DiscoveryError.AuthenticationRequired
        model.refresh()
        advanceUntilIdle()
        assertEquals(shown, model.state.value.content[a]!!.sections)
        assertEquals(DiscoveryError.AuthenticationRequired, model.state.value.content[a]!!.error)
        model.select(b)
        advanceUntilIdle()
        assertNull(model.state.value.content[b]!!.error)
        model.select(a)
        advanceUntilIdle()
        assertEquals(2, feed.feeds)
        feed.failure = null
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.content[a]!!.error)
        assertEquals(3, feed.feeds)
    }

    @Test fun lateFeedCompletionCannotOverwriteNewSourceOrRefresh() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val a = add("a", object : Feed() {
            override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> = withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                Ok(listOf(DiscoverySection("late", "Late A", emptyList())))
            }
        })
        val b = add("b", Feed())
        val model = model()
        runCurrent()
        assertTrue(started.isCompleted)
        model.select(b)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals("Same list", model.state.value.content[b]!!.sections.single().title)
        assertFalse(model.state.value.content[a]!!.loaded)
    }

    @Test fun moreAndSearchRoutesKeepSourceAndUseIndependentResultSessions() = runTest(dispatcher) {
        val a = add("a", Feed())
        val b = add("b", Feed())
        val model = model()
        advanceUntilIdle()
        val firstSection = model.state.value.content[a]!!.sections.single()
        val first = model.more(firstSection)!!
        val search = model.search()!!
        model.select(b)
        advanceUntilIdle()
        val second = model.more(model.state.value.content[b]!!.sections.single())!!
        assertEquals(a, firstSection.books.single().id.sourceId)
        assertEquals(a.id, search.sourceId)
        assertEquals(b.id, second.sourceId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertNull(model.more(firstSection))
        assertEquals(search, Json.decodeFromString<Route.Main.Explore.Search>(Json.encodeToString(search)))
        assertEquals(first, Json.decodeFromString<Route.Main.DiscoveryResults>(Json.encodeToString(first)))
    }

    @Test fun absentSearchCapabilityDoesNotOfferSourceSearch() = runTest(dispatcher) {
        add("feed", Feed(), capabilities = setOf(SourceCapability.Explore))
        val model = model()
        advanceUntilIdle()
        assertNull(model.search())
    }

    @Test fun sourceRegistrationAndAccountChangesInvalidateOnlyTheirOwnContent() = runTest(dispatcher) {
        val a = add("a", Feed())
        val bFeed = Feed()
        val b = add("b", bFeed)
        val model = model()
        advanceUntilIdle()
        model.select(b)
        advanceUntilIdle()
        model.scroll(b, DiscoveryScroll(2, 11))
        val oldReset = model.state.value.content[a]!!.resetId
        registry.unregister(a)
        val replaced = Feed()
        add("a", replaced)
        advanceUntilIdle()
        assertEquals(1, bFeed.feeds)
        assertEquals(0, replaced.feeds)
        assertEquals(DiscoveryScroll(2, 11), model.state.value.content[b]!!.scroll)
        model.select(a)
        advanceUntilIdle()
        assertNotEquals(oldReset, model.state.value.content[a]!!.resetId)
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, replaced.feeds)
        assertEquals(1, bFeed.feeds)
    }

    @Test fun interactiveFeedUsesSameDraftAndCarriesCategoryIdentityAndValuesToMore() = runTest(dispatcher) {
        var sort = "new"
        val provider = object : Feed() {
            override val hasInteractions = true
            fun snapshot() = DiscoveryCatalog(listOf(DiscoveryCategory("books", "Books", sort)),
                listOf(DiscoveryFilter.Text("Sort", "Sort", "new")), mapOf("Sort" to sort))
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Ok(snapshot())
            }
            override suspend fun feed(): Result<List<DiscoverySection>, DiscoveryError> {
                feeds++
                return Ok(listOf(DiscoverySection("books", "Books", emptyList(), sort, "books")))
            }
            override suspend fun interact(id: String, value: String?, longClick: Boolean): Result<DiscoveryUpdate, DiscoveryError> {
                sort = value!!
                return Ok(DiscoveryUpdate(snapshot()))
            }
        }
        val source = add("rules", provider)
        val model = model()
        advanceUntilIdle()
        assertEquals(1, provider.feeds)
        assertEquals(1, provider.catalogs)
        model.interact("Sort", "popular")
        advanceUntilIdle()
        val route = model.more(model.state.value.content[source]!!.sections.single())!!
        assertEquals("popular", route.target)
        assertEquals("books", route.categoryId)
        assertEquals(mapOf("Sort" to "popular"), Json.decodeFromString<Map<String, String>>(route.filtersJson))
        assertEquals(2, provider.feeds)
    }
}
