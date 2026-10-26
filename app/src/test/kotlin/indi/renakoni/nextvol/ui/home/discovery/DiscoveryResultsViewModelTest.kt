package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionResult
import hnovel.execution.FailureCode
import hnovel.rules.ScriptHostCall
import hnovel.rules.ScriptArgumentType
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.RuleDiscoveryProvider
import io.mockk.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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

    @Test fun resultDiagnosticsBelongToTheFailedLoadAndClearOnSuccess() = runTest(dispatcher) {
        val detail = ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall =
            ScriptHostCall("cookie.getCookie", 2, List(2) { ScriptArgumentType.String }))
        var failed = true
        val provider = mockk<RuleDiscoveryProvider>(relaxed = true)
        every { provider.hasCategories } returns true
        every { provider.openSession(any(), any(), any()) } returns provider
        every { provider.filters(any()) } returns emptyList()
        every { provider.diagnosticFailure } answers { detail.takeIf { failed } }
        every { provider.failureField } answers { "ruleExplore.name".takeIf { failed } }
        coEvery { provider.catalog(any()) } returns Ok(DiscoveryCatalog(listOf(DiscoveryCategory("category", "Category", "tag"))))
        coEvery { provider.page(any()) } coAnswers {
            if (failed) Err(DiscoveryError.InvalidRules) else Ok(DiscoveryPage(emptyList(), null))
        }
        add(provider)
        val model = model()
        advanceUntilIdle()
        assertEquals(detail, model.state.value.errorDiagnostic)
        assertEquals("ruleExplore.name", model.state.value.errorField)
        failed = false
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertNull(model.state.value.errorDiagnostic)
        assertNull(model.state.value.errorField)
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

    @Test fun applyingFiltersRefreshesOnceClearsOldBooksAndRestoresLightweightState() = runTest(dispatcher) {
        val provider = object : Pages() {
            override fun filters(target: String) = super.filters(target) + DiscoveryFilter.Toggle("completed", "Completed")
        }
        add(provider)
        val saved = SavedStateHandle()
        val original = model(saved)
        advanceUntilIdle()
        original.scroll(DiscoveryScroll(3, 20))
        val selection = mapOf("sort" to "b", "completed" to "true")
        original.filter(selection)
        assertTrue(original.state.value.books.isEmpty())
        advanceUntilIdle()
        assertEquals(2, provider.requests.size)
        assertEquals(selection, provider.requests.last().filters)
        original.filter(selection)
        advanceUntilIdle()
        assertEquals(2, provider.requests.size)
        assertEquals(DiscoveryScroll(), original.state.value.scroll)
        assertEquals("b", original.state.value.books.single().title)
        val restored = model(SavedStateHandle(mapOf("discovery.filters" to saved.get<String>("discovery.filters"))))
        advanceUntilIdle()
        assertEquals("b", restored.state.value.filters["sort"])
        assertEquals("b", restored.state.value.books.single().title)
        assertEquals(3, provider.requests.size)
        assertEquals(selection, Json.decodeFromString<Map<String, String>>(saved.get<String>("discovery.filters")!!))
    }

    @Test fun invalidRestoredCategoryStopsBeforeLoadingAndInvalidFiltersUseDefaults() = runTest(dispatcher) {
        val provider = Pages()
        add(provider)
        val missing = model(route = route.copy(categoryId = "removed"))
        advanceUntilIdle()
        assertEquals(DiscoveryError.InvalidRequest, missing.state.value.error)
        assertTrue(provider.requests.isEmpty())
        val restored = model(route = route.copy(filtersJson = "{\"sort\":\"gone\",\"removed\":\"value\"}"))
        advanceUntilIdle()
        assertEquals(mapOf("sort" to "a"), restored.state.value.filters)
    }

    @Test fun sourceSearchNeverEvaluatesCatalogOnInitialLoadPaginationOrRefresh() = runTest(dispatcher) {
        var catalogs = 0
        val provider = object : Pages() {
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Err(DiscoveryError.InvalidRules)
            }
            override fun filters(target: String) = emptyList<DiscoveryFilter.Choice>()
        }
        add(provider)
        val target = DISCOVERY_SEARCH_PREFIX + "A title"
        val model = model(route = route.copy(target = target, categoryId = null, filtersJson = "{\"sort\":\"b\"}"))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(1, model.state.value.books.size)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, model.state.value.books.size)
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(0, catalogs)
        assertEquals(listOf(null, "2", null), provider.requests.map { it.cursor })
        assertTrue(provider.requests.all { it.target == target && it.filters.isEmpty() })
    }

    @Test fun rawExploreActionsStillLoadCatalogValuesWithoutACategoryId() = runTest(dispatcher) {
        var catalogs = 0
        val provider = object : Pages() {
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Ok(DiscoveryCatalog(emptyList(), filters(""), mapOf("sort" to "b")))
            }
        }
        add(provider)
        val model = model(route = route.copy(target = "raw-explore", categoryId = null))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(1, catalogs)
        assertEquals("raw-explore", provider.requests.single().target)
        assertEquals(mapOf("sort" to "b"), provider.requests.single().filters)
    }

    @Test fun manualRefreshInvalidatesTheHomepageHandoffBeforeRequestingTheFirstPage() = runTest(dispatcher) {
        val refreshes = mutableListOf<Boolean>()
        val provider = object : Pages() {
            override suspend fun homepageCatalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                refreshes += refresh
                return Ok(DiscoveryCatalog(emptyList(), filters("")))
            }
        }
        add(provider)
        val model = model(route = route.copy(categoryId = null))
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(false, true), refreshes)
        assertEquals(listOf(null, null), provider.requests.map { it.cursor })
    }

    @Test fun refreshKeepsVisibleCardsUntilTheReplacementPageArrives() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var refreshing = false
        val provider = object : Pages() {
            override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
                if (refreshing) release.await()
                return super.page(request)
            }
        }
        add(provider)
        val model = model()
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        val previous = model.state.value.books
        assertEquals(2, previous.size)
        refreshing = true
        model.refresh()
        runCurrent()
        assertEquals(previous, model.state.value.books)
        assertTrue(model.state.value.loading)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, model.state.value.books.size)
        assertFalse(model.state.value.loading)
        assertEquals(listOf(null, "2", null), provider.requests.map { it.cursor })
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

    @Test fun rotationAndBackResumeDoNotReloadCompletedPage() = runTest(dispatcher) {
        val provider = Pages()
        add(provider)
        val model = model()
        advanceUntilIdle()
        model.scroll(DiscoveryScroll(4, 12))
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(1, provider.requests.size)
        assertEquals(DiscoveryScroll(4, 12), model.state.value.scroll)
        accounts.begin(source)
        advanceUntilIdle()
        assertEquals(2, provider.requests.size)
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
