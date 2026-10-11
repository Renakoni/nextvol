package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.*
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
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
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SearchHubViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val authority = ExecutionAuthority()
    private val registry = WebSourceRegistry(dispatcher, authority)
    private val accounts = SourceSessionManager(authority)
    private val coordinator = SearchCoordinator(ExploreRepository(registry), dispatcher)
    private val stores = mutableListOf<ViewModelStore>()
    private val historyText = MutableStateFlow<String?>(null)
    private val dao = mockk<UserDataDao> {
        every { getFlow(any()) } returns historyText
        coEvery { this@mockk.get(any<String>()) } answers { historyText.value }
        coEvery { insert(any(), any(), any(), any()) } coAnswers { historyText.value = arg(3) }
    }
    private val books = mockk<BookRepository> {
        every { getBookInformationFlow(any<String>(), any()) } returns emptyFlow()
    }
    private val browsing = mockk<SourceBrowseSettings> { every { scope } returns MutableStateFlow(null) }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        registry.sources.value.forEach { registry.unregister(it.metadata.id) }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private open class Stream : SearchProvider {
        override val searchTypes = listOf(SearchType("keyword", LocalString("keyword"), LocalString("keyword")))
        var events: (String) -> Flow<SearchResult> = { flowOf(SearchResult.End()) }
        override fun search(searchType: SearchType, keyword: String) = events(keyword)
    }
    private open class Paged : Stream(), PagedSearchProvider {
        val requests = mutableListOf<Pair<String, Int>>()
        val queries = mutableListOf<String?>()
        var load: suspend (String, Int) -> SearchPage = { _, _ -> SearchPage(emptyList(), null) }
        override suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String?): SearchPage {
            requests += keyword to page
            queries += query
            return load(keyword, page)
        }
    }
    private fun add(name: String, provider: SearchProvider = Paged(), category: SourceCategory? = null,
        capabilities: Set<SourceCapability> = setOf(SourceCapability.Search)): Identifier {
        val id = Identifier("search-fixture", name)
        registry.register(object : WebBookDataSource by EmptyWebDataSource {
            override val id = id
            override val searchProvider = provider
        }, SourceMetadata(WebDataSourceItem(id, name, "fixture"), capabilities, category = category))
        return id
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = SearchHubViewModel(registry, coordinator, books,
        UserDataRepository(dao), accounts, saved, browsing, dispatcher).also {
        stores += ViewModelStore().apply { put("search", it) }
        it.setActive(true)
    }
    private fun item(id: String, title: String = id) = SearchResult.MultipleBook(id, BookInformation(id, title,
        author = "Author", description = "Description", publishingHouse = "", wordCount = WordCount(0),
        lastUpdated = LocalDateTime.MIN, isComplete = false))

    @Test fun thousandSourcesAreLazyAndRunWithAtMostEightRequests() = runTest(dispatcher) {
        var active = 0
        var peak = 0
        var calls = 0
        repeat(1000) { index -> add("source-$index", Paged().apply { load = { _, _ ->
            active++; calls++; peak = maxOf(peak, active)
            try { delay(10); SearchPage(emptyList(), null) } finally { active-- }
        } }) }
        val model = model()
        runCurrent()
        assertEquals(0, calls)
        model.search("title")
        runCurrent()
        assertEquals(8, calls)
        advanceUntilIdle()
        assertEquals(8, peak)
        assertEquals(1000, calls)
        assertEquals(1000, model.state.value.completed)
        assertFalse(model.state.value.searching)
        assertTrue(model.state.value.books.isEmpty())
    }

    @Test fun groupsRestrictRequestsAndAnEmptyGroupNeverFallsBackToAllSources() = runTest(dispatcher) {
        val adult = Paged()
        val general = Paged()
        val unsupported = Paged()
        add("adult", adult, SourceCategory.Adult)
        add("general", general, SourceCategory.General)
        add("unsupported", unsupported, SourceCategory.Adult, emptySet())
        val model = model()
        runCurrent()
        model.selectScope(SourceCategory.Adult)
        model.search("title")
        advanceUntilIdle()
        assertEquals(1, adult.requests.size)
        assertTrue(general.requests.isEmpty())
        assertTrue(unsupported.requests.isEmpty())
        model.selectScope(SourceCategory.Literature)
        advanceUntilIdle()
        assertTrue(model.state.value.scopedSources.isEmpty())
        assertEquals(1, adult.requests.size)
        assertTrue(general.requests.isEmpty())
    }

    @Test fun entrySourceOverridesTheBrowseGroupAndSwitchingSourceKeepsTheKeyword() = runTest(dispatcher) {
        val a = Paged().apply { load = { _, _ -> SearchPage(listOf(item("a")), null) } }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("b")), null) } }
        val aId = add("a", a, SourceCategory.Anime)
        val bId = add("b", b, SourceCategory.General)
        every { browsing.scope } returns MutableStateFlow(SourceCategory.Adult)
        val model = model(SavedStateHandle(mapOf("sourceNamespace" to aId.namespace, "sourceId" to aId.id, "sourceName" to "a")))
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertEquals(listOf("Title" to 1), a.requests)
        assertTrue(b.requests.isEmpty())
        assertEquals(aId, model.state.value.selectedSource)
        assertNull(model.state.value.scope)
        model.selectSource(bId)
        advanceUntilIdle()
        assertEquals(listOf("Title" to 1), b.requests)
        assertEquals(1, a.requests.size)
        assertEquals("Title", model.state.value.query)
        assertEquals(bId, model.state.value.books.single().source)
        model.selectScope(null)
        advanceUntilIdle()
        assertEquals(2, a.requests.size)
        assertEquals(2, b.requests.size)
        assertEquals(2, model.state.value.books.size)
        verify(exactly = 0) { browsing.selectScope(any()) }
    }

    @Test fun switchingSourceDuringProgressiveSearchRejectsLateResultsAndResetsPagination() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val a = object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                emit(SearchPage(listOf(item("preview")), null, complete = false))
                withContext(NonCancellable) { release.await() }
                emit(SearchPage(listOf(item("late")), 2))
            }
        }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("b")), null) } }
        val aId = add("a", a)
        val bId = add("b", b)
        val model = model()
        runCurrent()
        model.selectSource(aId)
        model.search("Title")
        runCurrent()
        assertTrue(model.state.value.searching)
        assertEquals(aId, model.state.value.books.single().source)
        model.selectSource(bId)
        runCurrent()
        assertEquals(bId, model.state.value.books.single().source)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(bId, model.state.value.books.single().source)
        assertEquals("Title", model.state.value.submittedKeyword)
        assertEquals(1, model.state.value.completed)
        assertEquals(1, model.state.value.total)
        assertFalse(model.state.value.hasMore)
        assertFalse(model.state.value.searching)
    }

    @Test fun selectedSourceSurvivesRestorationAndRemovalNeverBroadensTheSearch() = runTest(dispatcher) {
        val a = Paged()
        val b = Paged()
        val aId = add("a", a)
        add("b", b)
        val saved = SavedStateHandle()
        val original = model(saved)
        runCurrent()
        original.selectSource(aId)
        original.search("Title")
        advanceUntilIdle()
        original.setActive(false)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals(aId, restored.state.value.selectedSource)
        assertEquals("a", restored.state.value.selectedSourceName)
        assertEquals("Title", restored.state.value.submittedKeyword)
        assertEquals(2, a.requests.size)
        assertTrue(b.requests.isEmpty())
        registry.unregister(aId)
        advanceUntilIdle()
        assertTrue(restored.state.value.scopedSources.isEmpty())
        assertEquals(aId, restored.state.value.selectedSource)
        assertTrue(b.requests.isEmpty())
    }

    @Test fun expandingToAllIsRestoredInsteadOfReapplyingTheEntrySource() = runTest(dispatcher) {
        val id = add("a")
        add("b")
        val saved = SavedStateHandle(mapOf("sourceNamespace" to id.namespace, "sourceId" to id.id, "sourceName" to "a"))
        val original = model(saved)
        runCurrent()
        original.selectScope(null)
        original.setActive(false)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertNull(restored.state.value.selectedSource)
        assertNull(restored.state.value.scope)
        assertEquals(2, restored.state.value.scopedSources.size)
    }

    @Test fun pageRequestsAreOnDemandAndSameTitleFromDifferentSourcesRetainsItsIdentity() = runTest(dispatcher) {
        val a = Paged().apply { load = { _, page -> when (page) {
            1 -> SearchPage((1..20).map { item("$it", "Title") }, 2)
            else -> SearchPage(listOf(item("20", "Title"), item("21", "Title")), null)
        } } }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("1", "Title")), null) } }
        val aId = add("a", a)
        val bId = add("b", b)
        val model = model()
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertEquals(21, model.state.value.books.size)
        assertTrue(model.state.value.hasMore)
        assertEquals(listOf("Title" to 1), a.requests)
        assertTrue(model.state.value.books.any { it.id == SourceBookId(aId, "1").storageKey })
        assertTrue(model.state.value.books.any { it.id == SourceBookId(bId, "1").storageKey })
        model.loadMore()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("Title" to 1, "Title" to 2), a.requests)
        assertEquals(1, b.requests.size)
        assertEquals(22, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
        assertTrue(model.state.value.books.first().information.first().isOk)
        verify(exactly = 0) { books.getBookInformationFlow(any<String>(), any()) }
        // Load-more pages continue one query; submitting the same keyword again starts another.
        model.search("Title")
        advanceUntilIdle()
        assertEquals(3, a.queries.size)
        assertTrue(a.queries.all { it != null })
        assertEquals(a.queries[0], a.queries[1])
        assertNotEquals(a.queries[1], a.queries[2])
        assertEquals(a.queries[0], b.queries[0])
    }

    @Test fun filteredEmptyPagesRetainAnExplicitContinuationUntilTheProviderEnds() = runTest(dispatcher) {
        val provider = Paged().apply { load = { _, page -> when (page) {
            1 -> SearchPage(emptyList(), 2)
            2 -> SearchPage(listOf(item("final", "Title")), 3)
            else -> SearchPage(emptyList(), null)
        } } }
        add("a", provider)
        val model = model()
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertTrue(model.state.value.books.isEmpty())
        assertTrue(model.state.value.hasMore)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(1, model.state.value.books.size)
        assertTrue(model.state.value.hasMore)
        model.loadMore()
        advanceUntilIdle()
        assertFalse(model.state.value.hasMore)
        assertEquals(listOf("Title" to 1, "Title" to 2, "Title" to 3), provider.requests)
    }

    @Test fun progressiveResultsRetainPreviewsRankUpdatesAndTheNextCursor() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val provider = object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                requests += keyword to page
                if (page == 1) {
                    emit(SearchPage(listOf(item("weak"), item("best")), null, complete = false,
                        scores = mapOf("weak" to 30, "best" to 40), previewIds = setOf("weak", "best")))
                    finish.await()
                    emit(SearchPage(listOf(item("best", "Full metadata")), 2, scores = mapOf("best" to 5),
                        evidence = mapOf("best" to SearchEvidence.VerifiedAlias)))
                } else emit(SearchPage(listOf(item("last")), null))
            }
        }
        every { books.getBookInformationFlow(any<String>(), any()) } returns flowOf(
            Err(io.nightfish.lightnovelreader.api.error.WebRequestError("Search", "offline")))
        add("a", provider)
        val model = model()
        runCurrent()
        model.search("title")
        runCurrent()
        assertEquals(2, model.state.value.books.size)
        assertEquals(0, model.state.value.completed)
        assertTrue(model.state.value.searching)
        assertTrue(model.state.value.books.first().information.toList().all { it.isOk })
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals("Full metadata", model.state.value.books.first().preview!!.title)
        assertEquals(1, model.state.value.completed)
        assertTrue(model.state.value.hasMore)
        assertTrue(model.state.value.failures.isEmpty())
        assertEquals(listOf("title" to 1), provider.requests)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(3, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
    }

    @Test fun repeatedPageEndsPaginationAndMatchingBooksRankAheadOfUnrelatedTitles() = runTest(dispatcher) {
        val provider = Paged().apply { load = { _, page -> SearchPage(listOf(item("other", "Other"), item("match", "Title")), page + 1) } }
        add("a", provider)
        val model = model()
        runCurrent()
        model.search("Title")
        advanceUntilIdle()
        assertEquals("Title", model.state.value.books.first().preview!!.title)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
    }

    @Test fun cachedPageAfterResumeKeepsItsNextCursor() = runTest(dispatcher) {
        var calls = 0
        val provider = object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                if (++calls == 1) {
                    emit(SearchPage(listOf(item("1")), null, complete = false))
                    awaitCancellation()
                }
                emit(SearchPage(listOf(item("1")), 2))
            }
        }
        add("a", provider)
        val model = model()
        runCurrent(); model.search("Title"); runCurrent()
        assertEquals(1, model.state.value.books.size)
        model.stop(); model.resume(); advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(1, model.state.value.books.size)
        assertTrue(model.state.value.hasMore)
    }

    @Test fun repeatedProgressivePageStopsWithoutLosingExistingResults() = runTest(dispatcher) {
        val provider = object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                emit(SearchPage(listOf(item("1")), null, complete = false))
                emit(SearchPage(emptyList(), page + 1))
            }
        }
        add("a", provider)
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        assertTrue(model.state.value.hasMore)
        model.loadMore(); advanceUntilIdle()
        assertEquals(1, model.state.value.books.size)
        assertFalse(model.state.value.hasMore)
    }

    @Test fun scoredBatchesHaveStableOrderRegardlessOfArrivalOrder() = runTest(dispatcher) {
        var reverse = false
        val provider = object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                for (id in if (reverse) listOf("2", "1") else listOf("1", "2"))
                    emit(SearchPage(listOf(item(id)), null, complete = false, scores = mapOf(id to 10)))
                emit(SearchPage(emptyList(), null))
            }
        }
        add("a", provider)
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        val first = model.state.value.books.map { it.id }
        reverse = true
        model.search("Title"); advanceUntilIdle()
        assertEquals(first, model.state.value.books.map { it.id })
    }

    @Test fun replacingQueryWaitsForOldRequestsAndNeverPublishesTheirLateResults() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val providers = List(8) { Paged().apply { load = { keyword, _ ->
            active++; peak = maxOf(peak, active)
            try {
                if (keyword == "old") withContext(NonCancellable) { release.await() }
                SearchPage(listOf(item(keyword)), null)
            } finally { active-- }
        } } }
        providers.forEachIndexed { i, provider -> add("$i", provider) }
        val model = model()
        runCurrent()
        model.search("old")
        runCurrent()
        model.search("new")
        runCurrent()
        assertTrue(providers.all { provider -> provider.requests.none { it.first == "new" } })
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(8, peak)
        assertEquals(8, model.state.value.books.size)
        assertTrue(model.state.value.books.all { it.preview?.title == "new" })
    }

    @Test fun timeoutsAndFailuresStayLocalAndRetryDoesNotRepeatSuccessfulSources() = runTest(dispatcher) {
        val good = Paged().apply { load = { _, _ -> SearchPage(listOf(item("good")), null) } }
        val slow = Paged().apply { load = { _, _ -> awaitCancellation() } }
        val denied = Paged().apply { load = { _, _ -> throw SourceRequestException(DiscoveryError.AuthenticationRequired) } }
        add("good", good); add("slow", slow); add("denied", denied)
        val model = model()
        runCurrent()
        model.search("query")
        runCurrent()
        assertEquals(1, model.state.value.books.size)
        assertTrue(model.state.value.searching)
        advanceUntilIdle()
        assertEquals(2, model.state.value.failures.size)
        assertTrue(model.state.value.failures.any { it.failure!!.error == DiscoveryError.AuthenticationRequired })
        assertEquals("search.execution", model.state.value.failures.single { it.id.id == "slow" }.failure!!.field)
        slow.load = { _, _ -> SearchPage(listOf(item("slow")), null) }
        denied.load = { _, _ -> SearchPage(listOf(item("denied")), null) }
        model.retryFailures()
        advanceUntilIdle()
        assertTrue(model.state.value.failures.isEmpty())
        assertEquals(1, good.requests.size)
        assertEquals(3, model.state.value.books.size)
    }

    @Test fun fourSlowSourcesDoNotBlockTheFollowingFourFastSources() = runTest(dispatcher) {
        repeat(4) { add("slow-$it", Paged().apply { load = { _, _ -> awaitCancellation() } }) }
        repeat(4) { add("fast-$it", Paged().apply { load = { _, _ -> delay(100); SearchPage(listOf(item("fast")), null) } }) }
        val model = model()
        runCurrent(); model.search("query"); advanceTimeBy(101); runCurrent()
        assertEquals(4, model.state.value.books.size)
        assertEquals(4, model.state.value.completed)
        assertTrue(model.state.value.searching)
        advanceUntilIdle()
    }

    @Test fun replacementAdmissionTimesOutWithoutFreeingOldRequestsAndCanRetry() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val providers = List(8) { Paged().apply { load = { keyword, _ ->
            if (keyword == "old") withContext(NonCancellable) { release.await() }
            SearchPage(listOf(item(keyword)), null)
        } } }
        providers.forEachIndexed { i, provider -> add("$i", provider) }
        val model = model()
        runCurrent(); model.search("old"); runCurrent(); model.search("new"); runCurrent()
        advanceTimeBy(30_001); runCurrent()
        assertFalse(model.state.value.searching)
        assertEquals(8, model.state.value.failures.size)
        assertTrue(model.state.value.failures.all { it.failure!!.field == "search.queue" })
        assertTrue(providers.all { it.requests.size == 1 })
        release.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.books.isEmpty())
        model.retryFailures(); advanceUntilIdle()
        assertEquals(8, model.state.value.books.size)
        assertTrue(model.state.value.books.all { it.preview!!.title == "new" })
        assertTrue(model.state.value.failures.isEmpty())
    }

    @Test fun unscoredArrivalPermutationsAndTruncationHaveDeterministicOrder() = runTest(dispatcher) {
        var reverse = false
        repeat(2) { source -> add("$source", object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = flow {
                delay(if ((source == 0) == reverse) 100 else 0)
                val candidates = (1..600).map { item("$it", "Title") }
                for (batch in (if (reverse) candidates.reversed() else candidates).chunked(37))
                    emit(SearchPage(batch, null, complete = false))
                emit(SearchPage(emptyList(), null))
            }
        }) }
        val model = model()
        runCurrent(); model.search("title"); advanceUntilIdle()
        val first = model.state.value.books.map { it.id }
        reverse = true
        model.search("title"); advanceUntilIdle()
        assertEquals(first, model.state.value.books.map { it.id })
        assertEquals(1000, first.size)
        assertTrue(model.state.value.limited)
    }

    @Test fun arbitraryProviderScoresCannotOutrankBetterTextMatches() = runTest(dispatcher) {
        add("ordinary", Paged().apply { load = { _, _ -> SearchPage(listOf(item("other", "Other")), null, scores = mapOf("other" to -100)) } })
        add("fuzzy", Paged().apply { load = { _, _ -> SearchPage(listOf(item("near", "义妺生活")), null, scores = mapOf("near" to 40)) } })
        val model = model()
        runCurrent(); model.search("义妹生活"); advanceUntilIdle()
        assertEquals(listOf("义妺生活", "Other"), model.state.value.books.map { it.preview!!.title })
        verify(exactly = 0) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun previewsUpdateWithoutScoreChangesAndCompleteDetailsRerankWithoutReplacingTheFlow() = runTest(dispatcher) {
        val updates = MutableSharedFlow<SearchPage>()
        add("a", object : Paged() {
            override fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String?) = updates
        })
        val model = model()
        runCurrent(); model.search("Title"); runCurrent()
        updates.emit(SearchPage(listOf(item("1", "Other"), item("2", "Title sequel")), null,
            complete = false, previewIds = setOf("1", "2"))); runCurrent()
        val book = model.state.value.books.single { it.preview!!.title == "Other" }
        updates.emit(SearchPage(listOf(item("1", "Updated preview")), null, complete = false, previewIds = setOf("1"))); runCurrent()
        assertEquals("Updated preview", model.state.value.books.single { it.id == book.id }.preview!!.title)
        assertSame(book.information, model.state.value.books.single { it.id == book.id }.information)
        every { books.getBookInformationFlow(book.id, any()) } returns flowOf(Ok(item(book.id, "Title").information!!))
        book.information.toList(); runCurrent()
        assertEquals(book.id, model.state.value.books.first().id)
        assertEquals("Title", model.state.value.books.first().preview!!.title)
        assertSame(book.information, model.state.value.books.first().information)
        updates.emit(SearchPage(listOf(item("1", "Stale preview")), null, previewIds = setOf("1"))); runCurrent()
        assertEquals("Title", model.state.value.books.first().preview!!.title)
        book.information.first()
        verify(exactly = 1) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun lateDetailsCannotCrossQueryOrAccountBoundaries() = runTest(dispatcher) {
        val source = add("a", Stream().apply { events = { flowOf(SearchResult.SingleBook("1")) } })
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        for (accountChange in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val old = model.state.value.books.single()
            every { books.getBookInformationFlow(old.id, any()) } returns flow {
                release.await(); emit(Ok(item(old.id, "Stale").information!!))
            }
            val read = async { old.information.toList() }
            runCurrent()
            if (accountChange) accounts.begin(source) else model.search("Next")
            runCurrent()
            release.complete(Unit)
            assertTrue(read.await().isEmpty())
            advanceUntilIdle()
            assertNull(model.state.value.books.single().preview)
        }
    }

    @Test fun obsoleteDetailsNeitherStartRequestsNorReplayCachedMetadata() = runTest(dispatcher) {
        val source = add("a", Stream().apply { events = { flowOf(SearchResult.SingleBook("1")) } })
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        for (accountChange in listOf(false, true)) {
            val old = model.state.value.books.single()
            if (accountChange) accounts.begin(source) else model.search("Next")
            // The registry/account guard must work even before its StateFlow observer runs.
            assertTrue(old.information.toList().isEmpty())
            advanceUntilIdle()
        }
        verify(exactly = 0) { books.getBookInformationFlow(any<String>(), any()) }
        val cached = model.state.value.books.single()
        every { books.getBookInformationFlow(cached.id, any()) } returns flowOf(Ok(item(cached.id).information!!))
        assertTrue(cached.information.toList().single().isOk)
        model.search("Another")
        assertTrue(cached.information.toList().isEmpty())
        verify(exactly = 1) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun queuedDetailsRecheckTheirAccountBeforeStartingARequest() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        every { books.getBookInformationFlow(any<String>(), any()) } returns flow { release.await() }
        val source = add("a", Stream().apply { events = { flow {
            repeat(5) { emit(SearchResult.MultipleBook("$it")) }
        } } })
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        val old = model.state.value.books.map { async { it.information.toList() } }
        runCurrent()
        verify(exactly = 4) { books.getBookInformationFlow(any<String>(), any()) }
        accounts.begin(source); runCurrent()
        release.complete(Unit)
        old.forEach { assertTrue(it.await().isEmpty()) }
        verify(exactly = 4) { books.getBookInformationFlow(any<String>(), any()) }
        model.state.value.books.first().information.toList()
        verify(exactly = 5) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun idOnlyDetailsImproveRankingAndConcurrentCollectorsShareOneRefresh() = runTest(dispatcher) {
        add("a", Paged().apply { load = { _, _ ->
            SearchPage(listOf(SearchResult.MultipleBook("1"), item("2", "Title sequel")), null)
        } })
        val model = model()
        runCurrent(); model.search("Title"); advanceUntilIdle()
        val empty = model.state.value.books.last()
        assertNull(empty.preview)
        verify(exactly = 0) { books.getBookInformationFlow(any<String>(), any()) }
        every { books.getBookInformationFlow(empty.id, any()) } returns flow {
            delay(100); emit(Ok(item(empty.id, "Title").information!!))
            emit(Err(io.nightfish.lightnovelreader.api.error.WebRequestError("Search", "refresh failed")))
        }
        val first = async { empty.information.toList() }
        val second = async { empty.information.toList() }
        advanceUntilIdle()
        assertTrue(first.await().all { it.isOk })
        assertTrue(second.await().last().isOk)
        assertEquals(empty.id, model.state.value.books.first().id)
        assertEquals("Title", model.state.value.books.first().preview!!.title)
        verify(exactly = 1) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun legacyStreamsRetainPartialResultsAndStopAtTerminalEvents() = runTest(dispatcher) {
        var reachedAfterEnd = false
        add("legacy", Stream().apply { events = { flow {
            repeat(25) { emit(item("$it")) }
            emit(SearchResult.End())
            reachedAfterEnd = true
            awaitCancellation()
        } } })
        add("partial", Stream().apply { events = { flow {
            emit(item("first")); emit(SearchResult.Error(java.io.IOException())); emit(item("late"))
        } } })
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        assertEquals(26, model.state.value.books.size)
        assertEquals(1, model.state.value.failures.size)
        assertFalse(reachedAfterEnd)
        assertFalse(model.state.value.searching)
    }

    @Test fun stopAndNavigationPreserveResultsAndOnlyInterruptedSourcesResume() = runTest(dispatcher) {
        val good = Paged().apply { load = { _, _ -> SearchPage(listOf(item("good")), null) } }
        val slow = Paged().apply { load = { _, _ -> awaitCancellation() } }
        add("good", good); add("slow", slow)
        val model = model()
        runCurrent(); model.search("query"); runCurrent()
        model.stop(); runCurrent()
        assertEquals(1, model.state.value.books.size)
        model.setActive(false); model.setActive(true); runCurrent()
        assertEquals(1, slow.requests.size)
        slow.load = { _, _ -> SearchPage(listOf(item("slow")), null) }
        model.resume(); advanceUntilIdle()
        assertEquals(1, good.requests.size)
        assertEquals(2, slow.requests.size)
        model.setActive(false); model.setActive(true); advanceUntilIdle()
        assertEquals(2, slow.requests.size)
        assertEquals(2, model.state.value.books.size)
    }

    @Test fun registrationAndAccountChangesInvalidateOnlyAffectedSourceResults() = runTest(dispatcher) {
        val a = Paged().apply { load = { _, _ -> SearchPage(listOf(item("a")), null) } }
        val b = Paged().apply { load = { _, _ -> SearchPage(listOf(item("b")), null) } }
        val id = add("a", a); add("b", b)
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        accounts.begin(id); advanceUntilIdle()
        assertEquals(2, a.requests.size)
        assertNotEquals(a.queries[0], a.queries[1])
        assertEquals(1, b.requests.size)
        registry.unregister(id); advanceUntilIdle()
        assertEquals(1, model.state.value.books.size)
        assertEquals("b", model.state.value.books.single().preview!!.title)
    }

    @Test fun savedStateRestoresQueryAndScopeButReloadsContentAndHistoryKeepsNewestFirst() = runTest(dispatcher) {
        val provider = Paged()
        add("a", provider, SourceCategory.Adult)
        val saved = SavedStateHandle()
        val model = model(saved)
        runCurrent()
        model.selectScope(SourceCategory.Adult)
        model.search(" first "); advanceUntilIdle()
        model.search("second"); advanceUntilIdle()
        model.search("first"); advanceUntilIdle()
        assertEquals(listOf("first", "second"), model.state.value.history)
        model.setActive(false)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        advanceUntilIdle()
        assertEquals("first", restored.state.value.query)
        assertEquals(SourceCategory.Adult, restored.state.value.scope)
        assertEquals(4, provider.requests.size)
        restored.deleteHistory("first"); advanceUntilIdle()
        assertEquals(listOf("second"), restored.state.value.history)
        restored.clearHistory(); advanceUntilIdle()
        assertTrue(restored.state.value.history.isEmpty())
    }

    @Test fun resultsAreBoundedAndTruncationIsExplicit() = runTest(dispatcher) {
        repeat(2) { source -> add("$source", Paged().apply { load = { _, _ ->
            SearchPage((1..600).map { item("$it") }, 2)
        } }) }
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        assertEquals(1000, model.state.value.books.size)
        assertTrue(model.state.value.limited)
        assertFalse(model.state.value.hasMore)
        assertEquals(2, model.state.value.completed)
    }

    @Test fun cachedBookInformationSurvivesRefreshTimeoutAndReleasesDetailPermits() = runTest(dispatcher) {
        var active = 0
        var peak = 0
        every { books.getBookInformationFlow(any<String>(), any()) } answers {
            val id = firstArg<String>()
            flow {
                active++; peak = maxOf(peak, active)
                try {
                    emit(Ok(item(id).information!!))
                    awaitCancellation()
                } finally { active-- }
            }
        }
        add("legacy", Stream().apply { events = { flow {
            repeat(5) { emit(SearchResult.MultipleBook("$it")) }
            emit(SearchResult.End())
        } } })
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        val loaded = model.state.value.books.map { async { it.information.toList() } }
        runCurrent()
        assertEquals(4, active)
        advanceUntilIdle()
        loaded.forEach { assertTrue(it.await().last().isOk) }
        assertEquals(4, peak)
        assertEquals(0, active)
        assertTrue(model.state.value.books.first().information.first().isOk)
        verify(exactly = 5) { books.getBookInformationFlow(any<String>(), any()) }
    }

    @Test fun uncachedBookInformationTimeoutReportsFailureAndCanRetry() = runTest(dispatcher) {
        every { books.getBookInformationFlow(any<String>(), any()) } returns flow { awaitCancellation() }
        add("legacy", Stream().apply { events = { flowOf(SearchResult.SingleBook("1")) } })
        val model = model()
        runCurrent(); model.search("query"); advanceUntilIdle()
        val book = model.state.value.books.single()
        val first = async { book.information.toList() }
        advanceUntilIdle()
        assertTrue(first.await().single().isErr)
        every { books.getBookInformationFlow(any<String>(), any()) } returns flowOf(Ok(item(book.id).information!!))
        assertTrue(book.information.last().isOk)
        verify(exactly = 2) { books.getBookInformationFlow(any<String>(), any()) }
    }
}
