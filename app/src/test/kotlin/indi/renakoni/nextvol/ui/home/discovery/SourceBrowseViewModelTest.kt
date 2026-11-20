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

    @Test fun resumingDuringScopeChangeDoesNotStartLoadingThePreviousSource() = runTest(dispatcher) {
        val female = add(0)
        val anime = add(1, SourceCategory.Anime)
        val model = model()
        advanceUntilIdle()
        model.selectScope(SourceCategory.Anime)
        model.setActive(true)
        assertFalse(model.state.value.content.containsKey(female))
        advanceUntilIdle()
        assertEquals(anime, model.state.value.selected)
        assertEquals(0, providers.getValue(female).feeds)
        assertEquals(1, providers.getValue(anime).feeds)
    }

}
