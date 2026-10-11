package indi.renakoni.nextvol.ui.home.reading.stats.detailed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.data.statistics.Count
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface StatsDetailedUiState {
    val targetDateRangeCountMap: Map<LocalDate, Count>
    val targetDateRangeRecordsMap: Map<LocalDate, List<BookRecord>>
    val targetDateRange: Pair<LocalDate, LocalDate>
    var selectedChartDateRange: Pair<LocalDate, LocalDate>?
    var selectedDate: LocalDate
    var selectedViewIndex: Int
    val displayedViewIndex: Int
    val hasData: Boolean
    val isLoading: Boolean
    val bookFirstReadDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate>
    val bookFirstFinishedDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate>
    val bookFavoriteDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate>
}

class MutableStatsDetailedUiState : StatsDetailedUiState {
    override var targetDateRangeCountMap: Map<LocalDate, Count> by mutableStateOf(emptyMap())
    override var targetDateRangeRecordsMap: Map<LocalDate, List<BookRecord>> by mutableStateOf(emptyMap())
    override var targetDateRange: Pair<LocalDate, LocalDate> by mutableStateOf(LocalDate.now() to LocalDate.now())
    override var selectedChartDateRange: Pair<LocalDate, LocalDate>? by mutableStateOf(null)
    override var selectedDate: LocalDate by mutableStateOf(LocalDate.now())
    override var selectedViewIndex: Int by mutableIntStateOf(0)
    override var displayedViewIndex: Int by mutableIntStateOf(0)
    override var hasData: Boolean by mutableStateOf(false)
    override var isLoading: Boolean by mutableStateOf(false)
    override var bookFirstReadDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate> by mutableStateOf(emptyMap())
    override var bookFirstFinishedDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate> by mutableStateOf(emptyMap())
    override var bookFavoriteDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate> by mutableStateOf(emptyMap())
}

val StatsDetailedUiState.currentDateRange: ClosedRange<LocalDate>
    get() = targetDateRange.first..targetDateRange.second
