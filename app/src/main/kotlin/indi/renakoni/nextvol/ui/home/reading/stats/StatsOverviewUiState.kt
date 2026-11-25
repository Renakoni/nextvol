package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.data.statistics.BookRecord
import java.time.LocalDate

interface StatsOverviewUiState {
    var isLoading: Boolean
    var selectedDate: LocalDate
    val highThresholdSeconds: Long
    val startDate: LocalDate
    var dateLevelMap: Map<LocalDate, Level>
    var bookRecordsByDate: Map<LocalDate, List<BookRecord>>
    val selectedDateDetails: DailyDateDetails?
}

class MutableStatisticsOverviewUiState : StatsOverviewUiState {
    override var isLoading: Boolean by mutableStateOf(true)
    override var selectedDate: LocalDate by mutableStateOf(LocalDate.now())
    override var highThresholdSeconds: Long by mutableLongStateOf(0L)
    override val startDate: LocalDate by mutableStateOf(LocalDate.now().minusMonths(6))
    override var dateLevelMap: Map<LocalDate, Level> by mutableStateOf(emptyMap())
    override var bookRecordsByDate: Map<LocalDate, List<BookRecord>> by mutableStateOf(emptyMap())
    override var selectedDateDetails: DailyDateDetails? by mutableStateOf(null)
}
