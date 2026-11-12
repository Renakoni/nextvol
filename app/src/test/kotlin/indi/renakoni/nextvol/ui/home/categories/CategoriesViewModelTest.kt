package indi.renakoni.nextvol.ui.home.categories

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionResult
import hnovel.execution.FailureCode
import hnovel.rules.ScriptDependency
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.RuleDiscoveryProvider
import io.mockk.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryCommand
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryScroll
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
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private fun model() = CategoriesViewModel(registry, accounts, SavedStateHandle(),
        SourceBrowseSettings(org.robolectric.RuntimeEnvironment.getApplication())).also {
        stores += ViewModelStore().apply { put("categories", it) }
        it.setActive(true)
    }
    private fun add(name: String, provider: DiscoveryProvider, builtIn: Boolean = false): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val discoveryProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"),
            if (provider.hasCategories) setOf(SourceCapability.Categories) else emptySet(), builtIn))
        return id
    }
    private open class Categories : DiscoveryProvider {
        override val hasCategories = true
        var calls = 0
        override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> {
            calls++
            return Ok(listOf(DiscoveryCategory("same", "Same category", "tag")))
        }
    }

    @Test fun catalogDiagnosticsReachThePageAndClearAfterRefresh() = runTest(dispatcher) {
        val detail = ExecutionResult.Failure(FailureCode.UnsupportedDependency, dependency = ScriptDependency.Sleep)
        var failed = true
        val provider = mockk<RuleDiscoveryProvider>(relaxed = true)
        every { provider.hasCategories } returns true
        every { provider.openSession(any(), any(), any()) } returns provider
        every { provider.diagnosticFailure } answers { detail.takeIf { failed } }
        every { provider.failureField } answers { "exploreUrl".takeIf { failed } }
        coEvery { provider.catalog(any()) } coAnswers {
            if (failed) Err(DiscoveryError.InvalidRules) else Ok(DiscoveryCatalog(emptyList()))
        }
        val id = add("diagnostic", provider)
        val model = model()
        advanceUntilIdle()
        assertEquals(detail, model.state.value.content.getValue(id).errorDiagnostic)
        assertEquals("exploreUrl", model.state.value.content.getValue(id).errorField)
        failed = false
        model.refresh()
        advanceUntilIdle()
        val page = model.state.value.content.getValue(id)
        assertNull(page.error)
        assertNull(page.errorDiagnostic)
        assertNull(page.errorField)
    }

    private class BrowserCategories(private val refresh: Boolean = false) : Categories() {
        val completion = CompletableDeferred<Result<Unit, DiscoveryError>>()
        var started = 0
        var cancelled = 0
        override val failureField = "discovery.browser"
        override suspend fun interact(id: String, value: String?, longClick: Boolean) =
            Ok(DiscoveryUpdate(DiscoveryCatalog(emptyList()), listOf(DiscoveryAction.Browser("https://fixture.invalid/")), refresh))
        override suspend fun openBrowser(action: DiscoveryAction.Browser): Result<Unit, DiscoveryError> {
            started++
            return try { completion.await() } catch (failure: CancellationException) { cancelled++; throw failure }
        }
    }

    @Test fun stableTabsAreLazyAndRetainVisitedContentAndScroll() = runTest(dispatcher) {
        val zProvider = Categories()
        val aProvider = Categories()
        val a = add("a", aProvider)
        val z = add("z", zProvider, builtIn = true)
        add("no-categories", object : DiscoveryProvider {})
        val model = model()
        advanceUntilIdle()
        assertEquals(listOf(z, a), model.state.value.sources.map { it.metadata.id })
        assertEquals(1, zProvider.calls)
        assertEquals(0, aProvider.calls)
        model.scroll(z, DiscoveryScroll(2, 19))
        model.select(a)
        advanceUntilIdle()
        model.select(z)
        advanceUntilIdle()
        assertEquals(1, zProvider.calls)
        assertEquals(1, aProvider.calls)
        assertEquals(DiscoveryScroll(2, 19), model.state.value.content[z]!!.scroll)
        model.setActive(false)
        model.setActive(true)
        advanceUntilIdle()
        assertEquals(1, zProvider.calls)
    }

    @Test fun initialInventoryLoadingEndsEvenWhenThereAreNoSources() = runTest(dispatcher) {
        val model = model()
        assertTrue(model.state.value.loadingSources)
        advanceUntilIdle()
        assertFalse(model.state.value.loadingSources)
        assertTrue(model.state.value.sources.isEmpty())
        val id = add("later", Categories())
        advanceUntilIdle()
        assertEquals(id, model.state.value.selected)
        assertTrue(model.state.value.content[id]!!.loaded)
    }

    @Test fun cancelledLateACompletionCannotOverwriteB() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val a = add("a", object : Categories() {
            override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> = withContext(NonCancellable) {
                started.complete(Unit)
                finish.await()
                Ok(listOf(DiscoveryCategory("late", "A late", "a")))
            }
        })
        val b = add("b", Categories())
        val model = model()
        runCurrent()
        assertTrue(started.isCompleted)
        model.select(b)
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(b, model.state.value.selected)
        assertEquals("Same category", model.state.value.content[b]!!.categories.single().title)
        assertFalse(model.state.value.content[a]!!.loaded)
    }

    @Test fun sourceQualifiedRoutesSurviveSerializationAndSameTitlesNeverMerge() = runTest(dispatcher) {
        val a = add("a", Categories())
        val b = add("b", Categories())
        val model = model()
        advanceUntilIdle()
        val first = model.result(model.state.value.content[a]!!.categories.single())!!
        model.select(b)
        advanceUntilIdle()
        val second = model.result(model.state.value.content[b]!!.categories.single())!!
        assertNotEquals(first.sourceId, second.sourceId)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(first.title, second.title)
        assertEquals(second, Json.decodeFromString<Route.Main.DiscoveryResults>(Json.encodeToString(second)))
        assertNull(model.result(model.state.value.content[a]!!.categories.single()))
    }

    @Test fun replacementWithIdenticalMetadataRefreshesOnlyItsOwnPage() = runTest(dispatcher) {
        val aProvider = Categories()
        val a = add("a", aProvider)
        val bProvider = Categories()
        val b = add("b", bProvider)
        val model = model()
        advanceUntilIdle()
        model.select(b)
        advanceUntilIdle()
        registry.unregister(a)
        val replacement = Categories()
        add("a", replacement)
        advanceUntilIdle()
        assertEquals(1, bProvider.calls)
        assertEquals(0, replacement.calls)
        model.select(a)
        advanceUntilIdle()
        assertEquals(1, replacement.calls)
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, replacement.calls)
        assertEquals(1, bProvider.calls)
    }

    @Test fun failureIsSourceLocalAndExplicitRetryCanRecover() = runTest(dispatcher) {
        var failure = true
        val a = add("a", object : Categories() {
            override suspend fun categories() = if (failure) Err(DiscoveryError.AuthenticationRequired) else super.categories()
        })
        val b = add("b", Categories())
        val model = model()
        advanceUntilIdle()
        assertEquals(DiscoveryError.AuthenticationRequired, model.state.value.content[a]!!.error)
        model.select(b)
        advanceUntilIdle()
        assertNull(model.state.value.content[b]!!.error)
        model.select(a)
        failure = false
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.content[a]!!.loaded)
        assertNull(model.state.value.content[a]!!.error)
    }

    @Test fun sourceActionsRefreshOnceAndPreserveOtherTabsAndRouteFilterSnapshots() = runTest(dispatcher) {
        var catalogs = 0
        var value = "new"
        fun snapshot() = DiscoveryCatalog(listOf(DiscoveryCategory("books", "Books", "target")),
            listOf(DiscoveryFilter.Choice("Sort", "Sort", linkedMapOf("new" to "New", "popular" to "Popular"), "new")),
            mapOf("Sort" to value), listOf(DiscoveryButton("refresh", "Refresh source")))
        val a = add("a", object : Categories() {
            override suspend fun catalog(refresh: Boolean): Result<DiscoveryCatalog, DiscoveryError> {
                catalogs++
                return Ok(snapshot())
            }
            override suspend fun interact(id: String, value: String?, longClick: Boolean): Result<DiscoveryUpdate, DiscoveryError> {
                return Ok(DiscoveryUpdate(snapshot(), listOf(DiscoveryAction.Results("target", "Books")), refresh = true))
            }
        })
        val other = Categories()
        val b = add("b", other)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect { commands += it } }
        advanceUntilIdle()
        model.select(b); advanceUntilIdle()
        model.scroll(b, DiscoveryScroll(3, 21))
        model.select(a); advanceUntilIdle()
        value = "popular"
        model.interact("refresh")
        advanceUntilIdle()
        assertEquals(2, catalogs)
        assertEquals(1, other.calls)
        assertEquals(DiscoveryScroll(3, 21), model.state.value.content[b]!!.scroll)
        assertEquals(a, commands.single().source)
        assertTrue(model.accepts(commands.single()))
        val route = model.result(model.state.value.content[a]!!.categories.single())!!
        assertEquals(mapOf("Sort" to "popular"), Json.decodeFromString<Map<String, String>>(route.filtersJson))
        model.select(b)
        assertFalse(model.accepts(commands.single()))
    }

    @Test fun lateInteractionCannotNavigateOrRefreshAnotherSource() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        add("a", object : Categories() {
            override suspend fun interact(id: String, value: String?, longClick: Boolean): Result<DiscoveryUpdate, DiscoveryError> = withContext(NonCancellable) {
                started.complete(Unit); finish.await()
                Ok(DiscoveryUpdate(DiscoveryCatalog(emptyList()), listOf(DiscoveryAction.Login), refresh = true))
            }
        })
        val other = Categories()
        val b = add("b", other)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect { commands += it } }
        advanceUntilIdle()
        model.interact("button"); runCurrent()
        assertTrue(started.isCompleted)
        model.select(b); runCurrent()
        finish.complete(Unit); advanceUntilIdle()
        assertTrue(commands.isEmpty())
        assertEquals(b, model.state.value.selected)
        assertEquals(1, other.calls)
    }

    @Test fun retainedBrowserStillCancelsOnTabSwitchAndLeavingThePage() = runTest(dispatcher) {
        val provider = BrowserCategories()
        val a = add("a", provider)
        val other = Categories()
        val b = add("b", other)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect { commands += it } }
        advanceUntilIdle()
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.last()); runCurrent()
        assertEquals(1, provider.started)
        model.setActive(false, retainBrowser = true); runCurrent()
        assertEquals(0, provider.cancelled)
        model.select(b); runCurrent()
        assertEquals(1, provider.cancelled)
        model.setActive(true); runCurrent()
        assertFalse(model.accepts(commands.last()))
        assertEquals(1, other.calls)
        model.select(a); runCurrent()
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.last()); runCurrent()
        assertEquals(2, provider.started)
        model.setActive(false); runCurrent()
        assertEquals(2, provider.cancelled)
        assertFalse(model.state.value.content[a]!!.acting)
    }

    @Test fun browserCoverRetainsWorkAndSuccessRefreshesOnceAfterResume() = runTest(dispatcher) {
        val provider = BrowserCategories(refresh = true)
        val a = add("a", provider)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect {
            commands += it
            model.openBrowser(it)
        } }
        advanceUntilIdle()
        model.interact("browser"); runCurrent()
        assertEquals(1, provider.started)
        assertFalse(model.state.value.content[a]!!.loaded)
        model.setActive(false, retainBrowser = true); runCurrent()
        assertTrue(model.state.value.content[a]!!.acting)
        assertFalse(model.accepts(commands.single()))
        model.setActive(true); runCurrent()
        assertTrue(model.state.value.content[a]!!.acting)
        assertEquals(1, provider.calls)
        model.setActive(false, retainBrowser = true); runCurrent()
        provider.completion.complete(Ok(Unit)); advanceUntilIdle()
        assertEquals(0, provider.cancelled)
        assertFalse(model.state.value.content[a]!!.acting)
        assertFalse(model.state.value.content[a]!!.loaded)
        assertFalse(model.state.value.content[a]!!.loading)
        assertEquals(1, provider.calls)
        model.setActive(true); advanceUntilIdle()
        assertTrue(model.state.value.content[a]!!.loaded)
        assertEquals(2, provider.calls)
        assertFalse(model.accepts(commands.single()))
        model.setActive(false); model.setActive(true); advanceUntilIdle()
        assertEquals(2, provider.calls)
    }

    @Test fun browserFailureBeforeResumeIsRetainedWithoutReloadingTheCatalog() = runTest(dispatcher) {
        val provider = BrowserCategories()
        val a = add("a", provider)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect { commands += it } }
        advanceUntilIdle()
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.single()); runCurrent()
        model.setActive(false, retainBrowser = true); runCurrent()
        provider.completion.complete(Err(DiscoveryError.Network)); advanceUntilIdle()
        assertFalse(model.state.value.content[a]!!.acting)
        assertEquals(DiscoveryError.Network, model.state.value.content[a]!!.error)
        assertEquals("discovery.browser", model.state.value.content[a]!!.errorField)
        model.setActive(true); advanceUntilIdle()
        assertEquals(DiscoveryError.Network, model.state.value.content[a]!!.error)
        assertEquals(1, provider.calls)
        assertEquals(0, provider.cancelled)
    }

    @Test fun retainedBrowserIsCancelledOnAccountChangeReplacementAndRemoval() = runTest(dispatcher) {
        val provider = BrowserCategories()
        val a = add("a", provider)
        val model = model()
        val commands = mutableListOf<DiscoveryCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.commands.collect { commands += it } }
        advanceUntilIdle()
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.last()); runCurrent()
        model.setActive(false, retainBrowser = true)
        accounts.begin(a); runCurrent()
        assertEquals(1, provider.cancelled)
        assertNull(model.state.value.content[a])
        model.setActive(true); advanceUntilIdle()
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.last()); runCurrent()
        model.setActive(false, retainBrowser = true)
        registry.unregister(a)
        val replacement = BrowserCategories()
        add("a", replacement); advanceUntilIdle()
        assertEquals(2, provider.cancelled)
        assertFalse(model.accepts(commands.last()))
        model.setActive(true); advanceUntilIdle()
        assertEquals(1, replacement.calls)
        model.interact("browser"); runCurrent()
        model.openBrowser(commands.last()); runCurrent()
        model.setActive(false, retainBrowser = true)
        registry.unregister(a); advanceUntilIdle()
        assertEquals(1, replacement.cancelled)
        assertTrue(model.state.value.content.isEmpty())
        assertNull(model.state.value.selected)
    }
}
