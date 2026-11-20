package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.web.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.Route
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DiscoveryResultsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    private val source = Identifier("fixture", "a")
    private val route = Route.Main.DiscoveryResults(source.namespace, source.id, "tag", "Same category", "session-a", "category")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle(), route: Route.Main.DiscoveryResults = this.route) =
        DiscoveryResultsViewModel(registry, accounts, saved, route).also {
            stores += ViewModelStore().apply { put("result", it) }
            it.setActive(true)
        }
    private fun add(provider: DiscoveryProvider, id: Identifier = source,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Categories)) {
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, "Source", "fixture"), capabilities))
    }
    private open class Pages : DiscoveryProvider {
        override val hasCategories = true
        val requests = mutableListOf<DiscoveryRequest>()
        var nextFailure = false
        override suspend fun categories() = Ok(listOf(DiscoveryCategory("category", "Same category", "tag")))
        override fun filters(target: String): List<DiscoveryFilter> = listOf(DiscoveryFilter.Choice("sort", "Sort", linkedMapOf("a" to "A", "b" to "B"), "a"))
        override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
            requests += request
            if (request.cursor != null && nextFailure) { nextFailure = false; return Err(DiscoveryError.Network) }
            return Ok(DiscoveryPage(listOf(DiscoveryBook(request.cursor ?: "1", request.filters["sort"].orEmpty())),
                if (request.cursor == null) "2" else null))
        }
    }

    @Test fun paginationRetryKeepsBooksAndCursorAndOtherSessionFiltersStayIndependent() = runTest(dispatcher) {
        val provider = Pages()
        add(provider)
        val a = model()
        val b = model(route = route.copy(sessionId = "session-b", filtersJson = "{\"sort\":\"b\"}"))
        advanceUntilIdle()
        assertEquals("a", a.state.value.books.single().title)
        assertEquals("b", b.state.value.books.single().title)
        provider.nextFailure = true
        a.loadMore()
        advanceUntilIdle()
        assertEquals(DiscoveryError.Network, a.state.value.error)
        assertEquals(1, a.state.value.books.size)
        a.loadMore()
        advanceUntilIdle()
        assertEquals(2, a.state.value.books.size)
        assertEquals(listOf(null, null, "2", "2"), provider.requests.map { it.cursor })
        assertEquals(1, b.state.value.books.size)
        assertEquals(source, a.state.value.books.first().id.sourceId)
    }

    @Test fun feedOnlyMoreInitializesItsScopedFiltersAndPaginatesWithoutCategories() = runTest(dispatcher) {
        var catalogs = 0
        val provider = object : Pages() {
            override val hasFeed = true
            override val hasCategories = false
            override val hasInteractions = true
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> = error("No category catalogue")
            override suspend fun homepageCatalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Ok(DiscoveryCatalog(emptyList(), values = mapOf("sort" to "b")))
            }
            override fun filters(target: String) = if (catalogs > 0) super.filters(target) else emptyList()
        }
        add(provider, capabilities = setOf(SourceCapability.Explore))
        val model = model(route = route.copy(target = "recent", categoryId = null))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(mapOf("sort" to "b"), model.state.value.filters)
        assertEquals("b", model.state.value.books.single().title)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, model.state.value.books.size)
        assertTrue(provider.requests.all { it.target == "recent" && it.filters == mapOf("sort" to "b") })
        assertEquals(listOf(null, "2"), provider.requests.map { it.cursor })
        assertEquals(1, catalogs)
    }

    @Test fun dynamicCategoryKeepsItsIdWhileFiltersRegenerateTheUrlAndPageDraft() = runTest(dispatcher) {
        val snapshots = mutableListOf<Map<String, String>>()
        val observed = mutableListOf<DiscoveryRequest>()
        add(object : Pages() {
            override fun openSession(id: String, values: Map<String, String>, environment: DiscoveryEnvironment): DiscoveryProvider {
                snapshots += values.toMap()
                return object : Pages() {
                    override suspend fun catalog(refresh: Boolean) = Ok(DiscoveryCatalog(
                        listOf(DiscoveryCategory("category", "Dynamic title", "tag-" + values["sort"])),
                        filters(""), values))
                    override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
                        observed += request
                        return Ok(DiscoveryPage(emptyList()))
                    }
                }
            }
        })
        val model = model(route = route.copy(target = "tag-a", filtersJson = "{\"sort\":\"a\",\"draft\":\"page-only\"}"))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        model.filter(mapOf("sort" to "b"))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(listOf("tag-a", "tag-b"), observed.map { it.target })
        assertEquals(listOf("page-only", "page-only"), snapshots.map { it["draft"] })
        assertEquals(mapOf("sort" to "b"), model.state.value.filters)
        accounts.begin(source)
        advanceUntilIdle()
        assertNull(snapshots.last()["draft"])
        assertEquals("b", snapshots.last()["sort"])
    }

    @Test fun sourceRemovalAndReplacementNeverRetargetThePageToAnotherSource() = runTest(dispatcher) {
        val provider = Pages()
        add(provider)
        val otherProvider = Pages()
        add(otherProvider, Identifier("fixture", "b"))
        val model = model()
        advanceUntilIdle()
        registry.unregister(source)
        advanceUntilIdle()
        assertEquals(DiscoveryError.Unavailable, model.state.value.error)
        assertTrue(model.state.value.books.isEmpty())
        assertTrue(otherProvider.requests.isEmpty())
        val replacement = Pages()
        add(replacement)
        advanceUntilIdle()
        assertEquals(1, replacement.requests.size)
        assertNull(model.state.value.error)
        accounts.begin(Identifier("fixture", "b"))
        advanceUntilIdle()
        assertEquals(1, replacement.requests.size)
    }

    @Test fun supersededFilterRequestCannotPublishLateBooks() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var delay = false
        add(object : Pages() {
            override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
                if (delay && request.filters["sort"] == "a") withContext(NonCancellable) {
                    started.complete(Unit)
                    finish.await()
                }
                return super.page(request)
            }
        })
        val model = model()
        advanceUntilIdle()
        delay = true
        model.loadMore()
        runCurrent()
        assertTrue(started.isCompleted)
        model.filter(mapOf("sort" to "b"))
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("b"), model.state.value.books.map { it.title })
    }
}
