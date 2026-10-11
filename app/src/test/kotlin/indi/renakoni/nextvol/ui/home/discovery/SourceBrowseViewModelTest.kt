package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceBrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val browsing = SourceBrowseSettings(RuntimeEnvironment.getApplication())
    private val stores = mutableListOf<ViewModelStore>()
    private val providers = mutableMapOf<Identifier, Provider>()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun id(index: Int) = Identifier("fixture", index.toString().padStart(3, '0'))
    private open class Provider : DiscoveryProvider {
        override val hasFeed = true
        override val hasCategories = true
        var feeds = 0
        var catalogs = 0
        override suspend fun feed(): com.github.michaelbull.result.Result<List<DiscoverySection>, DiscoveryError> {
            feeds++
            return Ok(listOf(DiscoverySection("feed", "Feed", emptyList())))
        }
        override suspend fun categories(): com.github.michaelbull.result.Result<List<DiscoveryCategory>, DiscoveryError> {
            catalogs++
            return Ok(listOf(DiscoveryCategory("category", "Category", "/category")))
        }
    }
    private fun add(index: Int, category: SourceCategory? = SourceCategory.Female,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Explore, SourceCapability.Categories), provider: Provider = Provider()): Identifier {
        val id = id(index)
        providers[id] = provider
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, "Same source name", "fixture"), capabilities, category = category))
        return id
    }
    private fun model(capability: SourceCapability = SourceCapability.Explore, saved: SavedStateHandle = SavedStateHandle(),
        settings: SourceBrowseSettings = browsing): DiscoveryPageViewModel =
        object : DiscoveryPageViewModel(registry, accounts, saved, capability, settings) {}.also {
            stores += ViewModelStore().apply { put("page", it) }
        }

    @Test fun failedProgressiveRefreshKeepsOldBooksUntilRetryOrSuccessfulEmptyResult() = runTest(dispatcher) {
        val old = DiscoverySection("feed", "Feed", listOf(DiscoveryBook("old", "Old book")), "all")
        val loading = old.copy(books = emptyList(), previewLoading = true)
        val failure = DiscoveryPreviewFailure(DiscoveryError.Network)
        val response = CompletableDeferred<Unit>()
        val provider = object : Provider(), DiscoveryPreviewProvider {
            var refreshes = 0
            override fun feedUpdates() = kotlinx.coroutines.flow.flow {
                when (refreshes++) {
                    0 -> emit(Ok(listOf(old)))
                    1, 2 -> {
                        emit(Ok(listOf(loading)))
                        response.await()
                        emit(Ok(listOf(loading.copy(previewLoading = false, previewFailure = failure))))
                    }
                    else -> emit(Ok(listOf(old.copy(books = emptyList()))))
                }
            }
            override suspend fun preview(id: String) = Ok(old.copy(books = listOf(DiscoveryBook("new", "New book"))))
        }
        val source = add(0, provider = provider)
        val model = model()
        model.setActive(true)
        advanceUntilIdle()
        fun section() = model.state.value.content.getValue(source).sections.single()
        assertEquals("old", section().books.single().id.remoteId)
        model.refresh()
        advanceUntilIdle()
        assertTrue(section().previewLoading)
        assertEquals("old", section().books.single().id.remoteId)
        response.complete(Unit)
        advanceUntilIdle()
        assertFalse(section().previewLoading)
        assertEquals(failure, section().previewFailure)
        assertEquals("old", section().books.single().id.remoteId)
        model.refresh()
        advanceUntilIdle()
        assertEquals(failure, section().previewFailure)
        assertEquals("old", section().books.single().id.remoteId)
        model.retryPreview(section())
        advanceUntilIdle()
        assertNull(section().previewFailure)
        assertEquals("new", section().books.single().id.remoteId)
        model.refresh()
        advanceUntilIdle()
        assertTrue(section().books.isEmpty())
        assertNull(section().previewFailure)
    }

    @Test fun sourcePagesRespectAllBoundariesAndNeverLoadUnselectedSources() = runTest(dispatcher) {
        val model = model()
        var count = 0
        for ((total, sizes) in listOf(0 to emptyList(), 1 to listOf(1), 19 to listOf(19), 20 to listOf(20),
            21 to listOf(20, 1), 40 to listOf(20, 20), 41 to listOf(20, 20, 1), 89 to listOf(20, 20, 20, 20, 9))) {
            while (count < total) add(count++)
            advanceUntilIdle()
            assertEquals(total, model.state.value.sources.size)
            assertEquals(sizes.size, model.state.value.pageCount)
            sizes.forEachIndexed { page, size ->
                model.selectPage(page)
                assertEquals(page, model.state.value.pageIndex)
                assertEquals(size, model.state.value.pageSources.size)
                assertEquals(id(page * 20), model.state.value.selected)
            }
            val selected = model.state.value.selected
            model.selectPage(-1)
            model.selectPage(sizes.size)
            assertEquals(selected, model.state.value.selected)
        }
        assertTrue(providers.values.all { it.feeds == 0 && it.catalogs == 0 })
    }

    @Test fun sharedScopeKeepsIndependentCapabilitiesSelectionsAndContentPositions() = runTest(dispatcher) {
        repeat(23) { add(it, capabilities = if (it < 19) setOf(SourceCapability.Explore, SourceCapability.Categories) else setOf(SourceCapability.Explore)) }
        val anime = add(30, SourceCategory.Anime)
        val unknown = add(31, null)
        val explore = model()
        val categories = model(SourceCapability.Categories)
        explore.setActive(true)
        categories.setActive(true)
        advanceUntilIdle()
        explore.select(id(21))
        categories.select(id(7))
        advanceUntilIdle()
        explore.scroll(id(21), DiscoveryScroll(8, 19))
        categories.scroll(id(7), DiscoveryScroll(4, 11))
        explore.selectScope(SourceCategory.Female)
        advanceUntilIdle()
        assertEquals(23, explore.state.value.sources.size)
        assertEquals(19, categories.state.value.sources.size)
        assertEquals(2, explore.state.value.pageCount)
        assertEquals(1, categories.state.value.pageCount)
        assertEquals(id(21), explore.state.value.selected)
        assertEquals(id(7), categories.state.value.selected)
        assertTrue(explore.state.value.sources.none { it.metadata.id == unknown })
        categories.selectScope(SourceCategory.Anime)
        advanceUntilIdle()
        assertEquals(anime, explore.state.value.selected)
        explore.selectScope(SourceCategory.Female)
        advanceUntilIdle()
        assertEquals(id(21), explore.state.value.selected)
        assertEquals(id(7), categories.state.value.selected)
        assertEquals(DiscoveryScroll(8, 19), explore.state.value.content.getValue(id(21)).scroll)
        assertEquals(DiscoveryScroll(4, 11), categories.state.value.content.getValue(id(7)).scroll)
        assertEquals(1, providers.getValue(id(21)).feeds)
        assertEquals(1, providers.getValue(id(7)).catalogs)
        assertEquals(25, registry.sources.value.size)
    }

    @Test fun decayedCategoryPagesRebuildTheirControlsWithTheUsersDraft() = runTest(dispatcher) {
        val seeds = mutableListOf<Map<String, String>>()
        val first = add(0, provider = object : Provider() {
            override fun openSession(id: String, values: Map<String, String>, environment: DiscoveryEnvironment): DiscoveryProvider {
                seeds += values
                return object : Provider() {
                    private var draft = values
                    private fun catalog() = DiscoveryCatalog(listOf(DiscoveryCategory("all", "All", "/all")),
                        listOf(DiscoveryFilter.Text("query", "Query")), draft, listOf(DiscoveryButton("go", "Go")))
                    override suspend fun catalog(refresh: Boolean) = Ok(catalog())
                    override suspend fun interact(id: String, value: String?, longClick: Boolean): com.github.michaelbull.result.Result<DiscoveryUpdate, DiscoveryError> {
                        draft = draft + (id to requireNotNull(value))
                        return Ok(DiscoveryUpdate(catalog()))
                    }
                }
            }
        })
        repeat(MAX_RETAINED_SOURCE_PAGES) { add(it + 1) }
        val model = model(SourceCapability.Categories)
        model.setActive(true)
        advanceUntilIdle()
        model.interact("query", "my draft")
        advanceUntilIdle()
        model.scroll(first, DiscoveryScroll(2, 17))
        repeat(MAX_RETAINED_SOURCE_PAGES) { model.select(id(it + 1)); advanceUntilIdle() }
        val decayed = model.state.value.content.getValue(first)
        assertTrue(decayed.categories.isEmpty() && decayed.filters.isEmpty() && decayed.buttons.isEmpty())
        assertFalse(decayed.loaded)
        assertEquals(mapOf("query" to "my draft"), decayed.values)
        model.select(first)
        advanceUntilIdle()
        val restored = model.state.value.content.getValue(first)
        assertTrue(restored.loaded)
        assertEquals("query", restored.filters.single().id)
        assertEquals(mapOf("query" to "my draft"), restored.values)
        assertEquals(DiscoveryScroll(2, 17), restored.scroll)
        assertEquals(listOf(emptyMap<String, String>(), restored.values), seeds)
    }

    @Test fun aLateCancelledLoadCannotRestoreADecayedPage() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val first = add(0, provider = object : Provider() {
            override suspend fun categories() = withContext(NonCancellable) {
                finish.await()
                Ok(listOf(DiscoveryCategory("late", "Late", "/late")))
            }
        })
        repeat(MAX_RETAINED_SOURCE_PAGES) { add(it + 1) }
        val model = model(SourceCapability.Categories)
        model.setActive(true)
        runCurrent()
        try {
            repeat(MAX_RETAINED_SOURCE_PAGES) { model.select(id(it + 1)); runCurrent() }
        } finally { finish.complete(Unit) }
        advanceUntilIdle()
        val decayed = model.state.value.content.getValue(first)
        assertFalse(decayed.loaded || decayed.loading)
        assertTrue(decayed.categories.isEmpty())
        assertEquals(MAX_RETAINED_SOURCE_PAGES, model.state.value.content.values.count { it.loaded })
    }

    @Test fun revisitingPagesRestoresTheirSelectedSourceAndFetchesOnlyVisitedContent() = runTest(dispatcher) {
        repeat(41) { add(it) }
        val model = model()
        model.setActive(true)
        advanceUntilIdle()
        model.select(id(25))
        advanceUntilIdle()
        model.scroll(id(25), DiscoveryScroll(3, 12))
        model.selectPage(0)
        assertEquals(id(0), model.state.value.selected)
        model.select(id(3))
        advanceUntilIdle()
        model.selectPage(1)
        advanceUntilIdle()
        assertEquals(id(25), model.state.value.selected)
        assertEquals(DiscoveryScroll(3, 12), model.state.value.content.getValue(id(25)).scroll)
        model.selectPage(0)
        assertEquals(id(3), model.state.value.selected)
        assertEquals(3, providers.values.sumOf { it.feeds })
    }

    @Test fun removalChoosesSuccessorThenPredecessorAndNewSourcesDoNotStealSelection() = runTest(dispatcher) {
        repeat(21) { add(it) }
        val model = model()
        advanceUntilIdle()
        model.select(id(5))
        registry.unregister(id(5))
        advanceUntilIdle()
        assertEquals(id(6), model.state.value.selected)
        model.select(id(20))
        registry.unregister(id(20))
        advanceUntilIdle()
        assertEquals(id(19), model.state.value.selected)
        assertEquals(0, model.state.value.pageIndex)
        assertEquals(1, model.state.value.pageCount)
        add(40)
        advanceUntilIdle()
        assertEquals(id(19), model.state.value.selected)
        model.selectScope(SourceCategory.Literature)
        advanceUntilIdle()
        assertTrue(model.state.value.sources.isEmpty())
        assertNull(model.state.value.selected)
        assertEquals(SourceCategory.Literature, browsing.scope.value)
    }

    @Test fun processRecreationRestoresScopeAndEachPagesStableIdentity() = runTest(dispatcher) {
        repeat(25) { add(it) }
        val explore = model()
        advanceUntilIdle()
        explore.selectScope(SourceCategory.Female)
        advanceUntilIdle()
        explore.select(id(23))
        val categories = model(SourceCapability.Categories)
        advanceUntilIdle()
        assertEquals(id(23), categories.state.value.selected)
        categories.select(id(4))
        stores.forEach { it.clear() }
        val settings = SourceBrowseSettings(RuntimeEnvironment.getApplication())
        val restoredExplore = model(settings = settings)
        val restoredCategories = model(SourceCapability.Categories, settings = settings)
        advanceUntilIdle()
        assertEquals(SourceCategory.Female, settings.scope.value)
        assertEquals(id(23), restoredExplore.state.value.selected)
        assertEquals(1, restoredExplore.state.value.pageIndex)
        assertEquals(id(4), restoredCategories.state.value.selected)
        assertEquals(0, restoredCategories.state.value.pageIndex)
    }

    @Test fun explicitRoutesRevealKnownOrUnclassifiedSourcesWithoutChangingOrdinaryTabSelection() = runTest(dispatcher) {
        add(0)
        val anime = add(1, SourceCategory.Anime)
        val unknown = add(2, null)
        browsing.selectScope(SourceCategory.Female)
        val explore = model()
        val categories = model(SourceCapability.Categories, SavedStateHandle(mapOf("namespace" to anime.namespace, "sourceId" to anime.id)))
        advanceUntilIdle()
        assertEquals(SourceCategory.Anime, browsing.scope.value)
        assertEquals(anime, categories.state.value.selected)
        assertEquals(anime, explore.state.value.selected)
        categories.openSource(unknown)
        advanceUntilIdle()
        assertNull(browsing.scope.value)
        assertEquals(unknown, categories.state.value.selected)
        categories.setActive(false)
        explore.select(id(0))
        categories.setActive(true)
        advanceUntilIdle()
        assertEquals(unknown, categories.state.value.selected)
    }

    @Test fun emptyInitialInventoryKeepsThePersistedSelectionUntilItArrives() = runTest(dispatcher) {
        browsing.selectScope(SourceCategory.Female)
        browsing.remember("explore", SourceCategory.Female, id(5))
        val model = model()
        assertTrue(model.state.value.loadingSources)
        advanceUntilIdle()
        assertNull(model.state.value.selected)
        assertEquals(id(5), browsing.selected("explore", SourceCategory.Female))
        add(0)
        add(5)
        advanceUntilIdle()
        assertEquals(id(5), model.state.value.selected)
    }

    @Test fun lateFeedFromAnotherScopeCannotOverwriteTheCurrentSource() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        add(0, provider = object : Provider() {
            override suspend fun feed() = withContext(NonCancellable) {
                finish.await()
                Ok(listOf(DiscoverySection("late", "Late female feed", emptyList())))
            }
        })
        val anime = add(1, SourceCategory.Anime)
        val model = model()
        model.setActive(true)
        runCurrent()
        model.selectScope(SourceCategory.Anime)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(anime, model.state.value.selected)
        assertEquals("Feed", model.state.value.content.getValue(anime).sections.single().title)
        assertFalse(model.state.value.content.getValue(id(0)).loaded)
    }
}
