package indi.renakoni.nextvol.ui.home.reading.stats.detailed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshots.Snapshot
import com.github.michaelbull.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.data.statistics.Count
import indi.renakoni.nextvol.data.statistics.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class StatsDetailedViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStatsDetailedUiState()
    val uiState: StatsDetailedUiState = _uiState


    private var loadJob: Job? = null
    private var requestedDate: LocalDate? = null
    private val bookInformation = mutableMapOf<String, Flow<Result<BookInformation, WebRequestError>>>()

    private fun information(bookId: String) = bookInformation.getOrPut(bookId) {
        bookRepository.getBookInformationFlow(bookId).flowOn(Dispatchers.IO)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    fun initialize(targetDate: LocalDate) {
        if (requestedDate == targetDate && loadJob?.isActive == true) return
        requestedDate = targetDate
        loadStatistics(_uiState.selectedViewIndex, targetDate)
    }

    fun setSelectedView(index: Int) {
        StatsViewOption.fromIndex(index)
        if (_uiState.selectedViewIndex == index && loadJob?.isActive == true) return
        _uiState.selectedViewIndex = index
        requestedDate?.let { loadStatistics(index, it) }
    }

    private fun loadStatistics(index: Int, date: LocalDate) {
        loadJob?.cancel()
        val range = StatsViewOption.fromIndex(index).rangeFor(date)
        val startDate = range.start
        val endDate = range.endInclusive
        if (!_uiState.hasData) {
            _uiState.selectedDate = date
            _uiState.targetDateRange = startDate to endDate
        }
        _uiState.isLoading = true
        loadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bookRecordsMap: Map<LocalDate, List<BookRecord>> =
                        statsRepository.getBookRecords(startDate, endDate)
                    val dailyCountsMap: Map<LocalDate, Count> =
                        statsRepository.getDailyCounts(startDate, endDate)
                    val firstReadDateMap = statsRepository.getBookFirstReadDateMap()
                    val firstFinishedDateMap = statsRepository.getBookFirstFinishedDateMap()
                    val favoriteDateMap = statsRepository.getBookFavoriteDateMap()

                    val allDates = generateSequence(startDate) { it.plusDays(1) }
                        .takeWhile { it <= endDate }
                        .toList()

                    val counts = allDates.associateWith { date ->
                        dailyCountsMap[date] ?: Count()
                    }.toSortedMap()
                    // The old period stays visible until the whole replacement is ready. Returning to
                    // Main is cancellable, so a superseded database read cannot publish late results.
                    withContext(Dispatchers.Main.immediate) {
                        Snapshot.withMutableSnapshot {
                            _uiState.targetDateRangeCountMap = counts
                            _uiState.targetDateRangeRecordsMap = allDates.associateWith { day ->
                                bookRecordsMap[day].orEmpty().map { it.copy(bookInformationFlow = information(it.bookId)) }
                            }
                            _uiState.bookFirstReadDateMap = firstReadDateMap.mapKeys { it.key to information(it.key) }
                            _uiState.bookFirstFinishedDateMap = firstFinishedDateMap.mapKeys { it.key to information(it.key) }
                            _uiState.bookFavoriteDateMap = favoriteDateMap.mapKeys { it.key to information(it.key) }
                            _uiState.selectedDate = date
                            _uiState.targetDateRange = startDate to endDate
                            _uiState.displayedViewIndex = index
                            _uiState.hasData = true
                        }
                    }
                }
            } finally {
                if (currentCoroutineContext().isActive) _uiState.isLoading = false
            }
        }
    }
}
