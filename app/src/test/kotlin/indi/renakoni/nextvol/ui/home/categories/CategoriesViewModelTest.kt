package indi.renakoni.nextvol.ui.home.categories

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.web.*
import io.mockk.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryCommand
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryScroll
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
