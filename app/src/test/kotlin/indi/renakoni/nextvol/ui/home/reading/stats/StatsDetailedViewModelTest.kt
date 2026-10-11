package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.lifecycle.ViewModelStore
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.statistics.Count
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.StatsDetailedViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class StatsDetailedViewModelTest {
    private val date = LocalDate.of(2026, 10, 4)
    private val stats = mockk<StatsRepository>()
    private val books = mockk<BookRepository>()
    private val store = ViewModelStore()
    private lateinit var model: StatsDetailedViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { stats.getBookRecords(any(), any()) } returns emptyMap()
        coEvery { stats.getDailyCounts(any(), any()) } returns mapOf(date to Count())
        coEvery { stats.getBookFirstReadDateMap() } returns mapOf("book" to date)
        coEvery { stats.getBookFirstFinishedDateMap() } returns emptyMap()
        coEvery { stats.getBookFavoriteDateMap() } returns emptyMap()
        every { books.getBookInformationFlow(any<String>(), any()) } answers { flow { } }
        model = StatsDetailedViewModel(stats, books)
        store.put("stats", model)
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private suspend fun awaitLoaded(days: Int) = withTimeout(5_000) {
        while (model.uiState.isLoading || model.uiState.targetDateRangeCountMap.size != days) delay(10)
    }

    @Test fun delayedOldPeriodCannotOverwriteTheLatestSelection() = runBlocking {
        model.initialize(date)
        awaitLoaded(7)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        coEvery { stats.getDailyCounts(date.withDayOfMonth(1), date.withDayOfMonth(31)) } coAnswers {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                returned.complete(Unit)
                mapOf(date to Count())
            }
        }
        model.setSelectedView(1)
        withTimeout(5_000) { started.await() }
        assertEquals(0, model.uiState.displayedViewIndex)
        assertEquals(date.minusDays(6) to date, model.uiState.targetDateRange)
        model.setSelectedView(2)
        awaitLoaded(365)
        release.complete(Unit)
        withTimeout(5_000) { returned.await() }
        delay(100)
        assertEquals(2, model.uiState.selectedViewIndex)
        assertEquals(365, model.uiState.targetDateRangeCountMap.size)
        assertEquals(LocalDate.of(2026, 1, 1), model.uiState.targetDateRange.first)
    }

    @Test fun emptyLeapYearCommitsTheSelectedPeriodAndAllDaysTogether() = runBlocking {
        val leapDate = LocalDate.of(2024, 2, 29)
        model.initialize(leapDate)
        awaitLoaded(7)
        model.setSelectedView(2)
        awaitLoaded(366)
        assertEquals(2, model.uiState.displayedViewIndex)
        assertEquals(leapDate, model.uiState.selectedDate)
        assertEquals(LocalDate.of(2024, 12, 31), model.uiState.targetDateRange.second)
        assertEquals(0, model.uiState.targetDateRangeCountMap.values.sumOf { it.getTotalMinutes() })
        assertEquals(model.uiState.targetDateRangeCountMap.keys, model.uiState.targetDateRangeRecordsMap.keys)
    }

    @Test fun changingPeriodKeepsTheSameBookSubscription() = runBlocking {
        model.initialize(date)
        awaitLoaded(7)
        val original = model.uiState.bookFirstReadDateMap.keys.single().second
        model.setSelectedView(1)
        awaitLoaded(31)
        assertSame(original, model.uiState.bookFirstReadDateMap.keys.single().second)
    }
}
