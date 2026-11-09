package indi.renakoni.nextvol.ui.components.calendar

import android.util.Log
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.ui.components.calendar.core.CalendarMonth
import indi.renakoni.nextvol.ui.components.calendar.core.VisibleItemState
import indi.renakoni.nextvol.ui.components.calendar.core.checkRange
import indi.renakoni.nextvol.ui.components.calendar.core.firstDayOfWeekFromLocale
import indi.renakoni.nextvol.ui.components.calendar.data.DataStore
import indi.renakoni.nextvol.ui.components.calendar.data.getHeatMapCalendarMonthData
import indi.renakoni.nextvol.ui.components.calendar.data.getMonthIndex
import indi.renakoni.nextvol.ui.components.calendar.data.getMonthIndicesCount
import java.time.DayOfWeek
import java.time.YearMonth

@Immutable
private data class CalendarInputs(
    val startMonth: YearMonth,
    val endMonth: YearMonth,
    val firstVisibleMonth: YearMonth,
    val firstDayOfWeek: DayOfWeek,
)

@Composable
fun rememberHeatMapCalendarState(
    startMonth: YearMonth = YearMonth.now(),
    endMonth: YearMonth = startMonth,
    firstVisibleMonth: YearMonth = startMonth,
    firstDayOfWeek: DayOfWeek = firstDayOfWeekFromLocale(),
): HeatMapCalState {
    val inputs = remember(startMonth, endMonth, firstVisibleMonth, firstDayOfWeek) {
        CalendarInputs(startMonth, endMonth, firstVisibleMonth, firstDayOfWeek)
    }

    return rememberSaveable(
        inputs = arrayOf(inputs),
        saver = HeatMapCalState.Saver,
    ) {
        HeatMapCalState(
            startMonth = inputs.startMonth,
            endMonth = inputs.endMonth,
            firstVisibleMonth = inputs.firstVisibleMonth,
            firstDayOfWeek = inputs.firstDayOfWeek,
            visibleItemState = null,
        )
    }
}


@Stable
class HeatMapCalState internal constructor(
    startMonth: YearMonth,
    endMonth: YearMonth,
    firstVisibleMonth: YearMonth,
    firstDayOfWeek: DayOfWeek,
    visibleItemState: VisibleItemState?,
) : ScrollableState {

    private var _startMonth by mutableStateOf(startMonth)

    var startMonth: YearMonth
        get() = _startMonth
        set(value) {
            if (value != startMonth) {
                _startMonth = value
                onMonthDataChanged()
            }
        }

    private var _endMonth by mutableStateOf(endMonth)

    var endMonth: YearMonth
        get() = _endMonth
        set(value) {
            if (value != endMonth) {
                _endMonth = value
                onMonthDataChanged()
            }
        }

    private var _firstDayOfWeek by mutableStateOf(firstDayOfWeek)

    var firstDayOfWeek: DayOfWeek
        get() = _firstDayOfWeek
        set(value) {
            if (value != firstDayOfWeek) {
                _firstDayOfWeek = value
                onMonthDataChanged()
            }
        }

    val firstVisibleMonth: CalendarMonth by derivedStateOf {
        store[listState.firstVisibleItemIndex]
    }

    @Suppress("UNUSED")
    private val lastVisibleMonth: CalendarMonth by derivedStateOf {
        store[listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0]
    }

    internal val listState = LazyListState(
        firstVisibleItemIndex = visibleItemState?.firstVisibleItemIndex
            ?: getScrollIndex(firstVisibleMonth) ?: 0,
        firstVisibleItemScrollOffset = visibleItemState?.firstVisibleItemScrollOffset ?: 0,
    )

    internal var calendarInfo by mutableStateOf(CalendarInfo(indexCount = 0))

    internal val store = DataStore { offset ->
        getHeatMapCalendarMonthData(
            startMonth = this.startMonth,
            offset = offset,
            firstDayOfWeek = this.firstDayOfWeek,
        ).calendarMonth
    }

    init {
        onMonthDataChanged()
    }

    private fun onMonthDataChanged() {
        store.clear()
        checkRange(startMonth, endMonth)
        calendarInfo = CalendarInfo(
            indexCount = getMonthIndicesCount(startMonth, endMonth),
            firstDayOfWeek = firstDayOfWeek,
        )
    }

    private fun getScrollIndex(month: YearMonth): Int? {
        if (month !in startMonth..endMonth) {
            Log.d("CalendarState", "Attempting to scroll out of range: $month")
            return null
        }
        return getMonthIndex(startMonth, month)
    }

    override val isScrollInProgress: Boolean get() = listState.isScrollInProgress

    override fun dispatchRawDelta(delta: Float): Float = listState.dispatchRawDelta(delta)

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit,
    ): Unit = listState.scroll(scrollPriority, block)

    companion object {
        internal val Saver: Saver<HeatMapCalState, Any> = listSaver(
            save = {
                listOf(
                    it.startMonth,
                    it.endMonth,
                    it.firstVisibleMonth.yearMonth,
                    it.firstDayOfWeek,
                    it.listState.firstVisibleItemIndex,
                    it.listState.firstVisibleItemScrollOffset,
                )
            },
            restore = {
                HeatMapCalState(
                    startMonth = it[0] as YearMonth,
                    endMonth = it[1] as YearMonth,
                    firstVisibleMonth = it[2] as YearMonth,
                    firstDayOfWeek = it[3] as DayOfWeek,
                    visibleItemState = VisibleItemState(
                        firstVisibleItemIndex = it[4] as Int,
                        firstVisibleItemScrollOffset = it[5] as Int,
                    ),
                )
            }
        )
    }
}
