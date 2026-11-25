package indi.renakoni.nextvol.ui.home.reading.stats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.utils.formReadingDuration
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class StatsOverviewViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bookRepository: BookRepository
) : ViewModel() {
    private var _uiState = MutableStatisticsOverviewUiState()
    val uiState: StatsOverviewUiState = _uiState

    init {
        reloadData()
    }

    private fun reloadData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.isLoading = true

            val time = System.currentTimeMillis()
            Log.d("AppReadingStats", "Refresh started")
            _uiState.selectedDate = LocalDate.now()

            val startDate = _uiState.startDate
            val endDate = LocalDate.now()

            val bookRecordsMap = statsRepository.getBookRecords(startDate, endDate)
            generateLevelMap(bookRecordsMap)
            _uiState.bookRecordsByDate = bookRecordsMap
            selectDate(_uiState.selectedDate)

            _uiState.isLoading = false
            val elapsed = (System.currentTimeMillis() - time) / 1000.0
            Log.d("AppReadingStats", "Refresh completed in $elapsed seconds")
        }
    }

    fun selectDate(date: LocalDate) {
        _uiState.selectedDate = date
        getDateDetails(date)
    }

    private fun getDateDetails(selectedDate: LocalDate) {
        val records = _uiState.bookRecordsByDate[selectedDate] ?: emptyList()
        if (records.isEmpty()) {
            _uiState.selectedDateDetails = null
            return
        }
        var totalSeconds = 0L
        val detailsList = mutableListOf<Pair<Flow<Result<BookInformation, WebRequestError>>, Int>>()

        for (rec in records) {
            val seconds = rec.seconds
            totalSeconds += seconds

            detailsList += bookRepository.getBookInformationFlow(rec.bookId) to seconds
        }

        val sortedDetails = detailsList
            .sortedByDescending { it.second }
            .toMutableList()

        val formattedTotal = formReadingDuration(totalSeconds)

        _uiState.selectedDateDetails = DailyDateDetails(
            formattedTotalTime = formattedTotal,
            timeDetails = sortedDetails
        )
    }

    private fun generateLevelMap(recordsByDate: Map<LocalDate, List<BookRecord>>) {
        val secondsByDate = recordsByDate.mapValues { (_, records) -> records.sumOf { it.seconds.toLong() } }
        val activeSeconds = secondsByDate.values.filter { it > 0 }.sorted()
        val low = activeSeconds.getOrElse(activeSeconds.size / 4) { 0L }
        val middle = activeSeconds.getOrElse(activeSeconds.size / 2) { 0L }
        val high = activeSeconds.getOrElse(activeSeconds.size * 3 / 4) { 0L }
        _uiState.highThresholdSeconds = high

        val dateLevelMap = secondsByDate.mapValues { (_, readingTime) ->
            when {
                readingTime == 0L -> Level.Zero
                readingTime >= high -> Level.Four
                readingTime >= middle -> Level.Three
                readingTime >= low -> Level.Two
                else -> Level.One
            }
        }
        _uiState.dateLevelMap = dateLevelMap
    }
}
