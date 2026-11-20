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
        assertTrue(waiting.refreshing)
        assertEquals(listOf("Old first", "Old second"), waiting.sections.map { it.books.single().title })
        assertTrue(waiting.sections.all { it.previewLoading })
        update.complete(Unit)
        runCurrent()
        assertEquals(listOf("New first", "Old second"),
            model.state.value.content.getValue(id).sections.map { it.books.single().title })
        assertTrue(model.state.value.content.getValue(id).refreshing)
        model.refresh()
        runCurrent()
        assertEquals(2, loads)
        finish.complete(Unit)
        advanceUntilIdle()
        val complete = model.state.value.content.getValue(id)
        assertTrue(complete.loaded)
        assertFalse(complete.refreshing)
        assertFalse(complete.sections.first().previewLoading)
        assertNull(complete.sections.first().previewFailure)
        assertEquals("New first", complete.sections.first().books.single().title)
        assertFalse(complete.sections.last().previewLoading)
        assertEquals("Old second", complete.sections.last().books.single().title)
        assertEquals(DiscoveryError.Network, complete.sections.last().previewFailure!!.error)
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
