package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.*
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class ExploreSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val stores = mutableListOf<ViewModelStore>()
    private val historyText = MutableStateFlow<String?>(null)
    private val dao = mockk<UserDataDao> {
        every { getFlow(any()) } returns historyText
        coEvery { this@mockk.get(any<String>()) } answers { historyText.value }
        coEvery { insert(any(), any(), any(), any()) } coAnswers { historyText.value = arg(3) }
    }
    private val users = UserDataRepository(dao)
    private val shelves = mockk<BookshelfRepository> { every { getAllBookshelfBookIdsFlow() } returns flowOf(emptyList()) }
    private val books = mockk<BookRepository> { every { getBookInformationFlow(any<String>(), any()) } returns emptyFlow() }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }
    private open class Search : SearchProvider {
        override val searchTypes = listOf("title", "author").map { SearchType(it, LocalString(it), LocalString(it)) }
        val requests = mutableListOf<Pair<String, String>>()
        var results: (String) -> Flow<SearchResult> = { flowOf(SearchResult.MultipleBook("same"), SearchResult.End()) }
        override fun search(searchType: SearchType, keyword: String): Flow<SearchResult> {
            requests += searchType.type to keyword
            return results(keyword)
        }
        override fun getSearchSuggestions(history: List<String>, keyword: String) = history.filter { it.startsWith(keyword) }
    }
    private fun add(name: String, search: SearchProvider = Search(), capabilities: Set<SourceCapability> = setOf(SourceCapability.Search)): Identifier {
        val id = Identifier("fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val searchProvider = search
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities))
        return id
    }
    private fun model(id: Identifier, saved: SavedStateHandle = SavedStateHandle(), repository: ExploreRepository = ExploreRepository(registry)) =
        ExploreSearchViewModel(repository, shelves, books, users, registry, accounts, saved,
            Route.Main.Explore.Search(id.namespace, id.id), dispatcher).also {
            stores += ViewModelStore().apply { put("search", it) }
            it.setActive(true)
        }

    @Test fun lateSearchCannotReplaceNewResultsOrNavigateToOldSingleBook() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val provider = Search().apply {
            results = { keyword -> if (keyword == "old") flow {
                withContext(NonCancellable) { started.complete(Unit); finish.await() }
                emit(SearchResult.SingleBook("old"))
            } else flowOf(SearchResult.MultipleBook("new"), SearchResult.End()) }
        }
        val id = add("a", provider)
        val model = model(id)
        val commands = mutableListOf<SearchNavigation>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.navigation.collect { commands += it } }
        advanceUntilIdle()
        model.search("old")
        runCurrent()
        assertTrue(started.isCompleted)
        model.search("new")
        runCurrent()
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(SourceBookId(id, "new").storageKey, model.uiState.searchResult.single().first)
        assertTrue(commands.isEmpty())
        assertTrue(model.uiState.isLoadingComplete)
    }

    @Test fun delayedSuggestionCannotOverwriteNewQueryOrType() = runTest(dispatcher) {
        val id = add("a")
        val old = CompletableDeferred<List<String>>()
        val session = mockk<SourceSearch> {
            every { types } returns Search().searchTypes
            coEvery { suggestions(any(), "old") } coAnswers { withContext(NonCancellable) { old.await() } }
            coEvery { suggestions(any(), "new") } returns listOf("new suggestion")
        }
        val repository = mockk<ExploreRepository> { coEvery { open(id) } returns Ok(session) }
        val model = model(id, repository = repository)
        advanceUntilIdle()
        model.updateSuggestions("old")
        runCurrent()
        model.updateSuggestions("new")
        runCurrent()
        old.complete(listOf("old suggestion"))
        advanceUntilIdle()
        assertEquals(listOf("new suggestion"), model.uiState.suggestions)
        model.changeSearchType("author")
        advanceUntilIdle()
        assertEquals("author", model.uiState.searchType)
        assertEquals(listOf("new suggestion"), model.uiState.suggestions)
    }

    @Test fun accountOrRegistrationChangesReloadOnlyMatchingSourceAndInvalidateOldCommands() = runTest(dispatcher) {
        val aProvider = Search()
        val bProvider = Search()
        val a = add("a", aProvider)
        val b = add("b", bProvider)
        val first = model(a)
        val second = model(b)
        advanceUntilIdle()
        first.search("title")
        second.search("title")
        advanceUntilIdle()
        accounts.begin(a)
        advanceUntilIdle()
        assertEquals(2, aProvider.requests.size)
        assertEquals(1, bProvider.requests.size)
        registry.unregister(a)
        val replacement = Search()
        add("a", replacement)
        advanceUntilIdle()
        assertEquals(1, replacement.requests.size)
        assertEquals(1, bProvider.requests.size)
        registry.unregister(a)
        advanceUntilIdle()
        assertEquals(DiscoveryError.Unavailable, first.uiState.failure!!.error)
        assertTrue(first.uiState.searchResult.isEmpty())
        assertEquals(1, second.uiState.searchResult.size)
    }

    @Test fun scoredPagedResultsKeepTheSameOrderWhenChannelsFinishInReverse() = runTest(dispatcher) {
        var reverse = false
        val provider = object : Search(), PagedSearchProvider {
            override suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String?) = error("Use batches")
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                for (id in if (reverse) listOf("2", "1") else listOf("1", "2"))
                    emit(SearchPage(listOf(SearchResult.MultipleBook(id)), null, complete = false, scores = mapOf(id to 10)))
                emit(SearchPage(emptyList(), null))
            }
        }
        val model = model(add("a", provider))
        advanceUntilIdle(); model.search("Title"); advanceUntilIdle()
        val first = model.uiState.searchResult.map { it.first }
        reverse = true
        model.search("Title"); advanceUntilIdle()
        assertEquals(first, model.uiState.searchResult.map { it.first })
    }

    @Test fun pagedSourcePublishesIncrementallyAndLoadsMoreOnlyWhenRequested() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val requested = mutableListOf<Int>()
        val provider = object : Search(), PagedSearchProvider {
            override suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String?) = error("Use batches")
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                requested += page
                emit(SearchPage(listOf(SearchResult.MultipleBook("$page")), null, complete = false))
                if (page == 1) release.await()
                emit(SearchPage(emptyList(), if (page == 1) 2 else null))
            }
        }
        val model = model(add("a", provider))
        advanceUntilIdle()
        model.search("title")
        runCurrent()
        assertEquals(1, model.uiState.searchResult.size)
        assertFalse(model.uiState.isLoadingComplete)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1), requested)
        assertEquals(2, model.uiState.nextPage)
        model.loadMore()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), requested)
        assertEquals(2, model.uiState.searchResult.size)
        assertNull(model.uiState.nextPage)
        assertTrue(model.uiState.isLoadingComplete)
    }
}
