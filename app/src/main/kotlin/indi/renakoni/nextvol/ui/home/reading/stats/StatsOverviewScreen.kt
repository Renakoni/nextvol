package indi.renakoni.nextvol.ui.home.reading.stats

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow.Companion.Ellipsis
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.AnimatedText
import indi.renakoni.nextvol.ui.components.HeatMapCalendar
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.calendar.core.CalendarDay
import indi.renakoni.nextvol.ui.components.calendar.core.CalendarMonth
import indi.renakoni.nextvol.ui.components.calendar.core.CalendarWeek
import indi.renakoni.nextvol.ui.components.calendar.core.displayText
import indi.renakoni.nextvol.ui.components.calendar.core.yearMonth
import indi.renakoni.nextvol.ui.components.calendar.rememberHeatMapCalendarState
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.utils.formReadingDuration
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsOverviewScreen(
    onClickBack: () -> Unit,
    viewModel: StatsOverviewViewModel,
    onClickDetailScreen: (Int) -> Unit,
) {
    val uiState = viewModel.uiState
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val haptic = LocalHapticFeedback.current
    val contentHazeState = rememberHazeState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pinnedScrollBehavior.nestedScrollConnection),
        topBar = {
            TopBar(
                scrollBehavior = pinnedScrollBehavior,
                hazeState = contentHazeState,
                onClickBack = onClickBack,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Crossfade(
            targetState = uiState.isLoading,
            label = "statsOverviewLoading",
        ) { isLoading ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(contentHazeState),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    item(key = "summary_strip") {
                        OverviewSummaryLedger(uiState.bookRecordsByDate)
                    }
                    item(key = "calendar_block") {
                        CalendarBlock(
                            viewModel = viewModel,
                            onSelectedDate = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.selectDate(it)
                            },
                        )
                    }
                    item(key = "daily_stats_block") {
                        DailyStatsBlock(uiState, onClickDetailScreen)
                    }
                    navigationBarSpacer()
                }
            }
        }
    }
}

@Composable
private fun OverviewSummaryLedger(
    bookRecordsByDate: Map<LocalDate, List<BookRecord>>,
) {
    val tokens = NextVolDesign.tokens
    val (totalSeconds, readBookCount) = remember(bookRecordsByDate) {
        var seconds = 0L
        val readBookIds = HashSet<String>()
        bookRecordsByDate.values.forEach { records ->
            records.forEach { record ->
                seconds += record.seconds
                if (record.reads > 0 || record.seconds > 0) {
                    readBookIds.add(record.bookId)
                }
            }
        }
        seconds to readBookIds.size
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(text = stringResource(R.string.stats_last_six_months))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerMetricColumn(
                value = if (totalSeconds > 0L) formReadingDuration(totalSeconds) else "--",
                label = stringResource(R.string.reading_duration),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(
                modifier = Modifier
                    .height(42.dp)
                    .padding(horizontal = 16.dp),
                thickness = 0.6.dp,
                color = tokens.divider,
            )
            LedgerMetricColumn(
                value = stringResource(R.string.n_books, readBookCount),
                label = stringResource(R.string.activity_read),
                modifier = Modifier.weight(1f),
            )
        }

        EditorialHairlineDivider()
    }
}

@Composable
private fun LedgerMetricColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = typography.displayMedium.copy(
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.W700,
                fontFeatureSettings = "tnum",
                letterSpacing = (-0.4).sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = Ellipsis,
        )
        Text(
            text = label,
            style = typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = Ellipsis,
        )
    }
}

@Composable
private fun CalendarBlock(
    viewModel: StatsOverviewViewModel,
    onSelectedDate: (LocalDate) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val uiState = viewModel.uiState
    val now = LocalDate.now()
    val startDate = uiState.startDate
    val state = rememberHeatMapCalendarState(
        startMonth = startDate.yearMonth,
        endMonth = now.yearMonth,
        firstVisibleMonth = now.yearMonth,
        firstDayOfWeek = DayOfWeek.MONDAY,
    )
    val surfaceRadius = 20.dp

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = stringResource(R.string.calendar),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (!tokens.isDark) {
                        Modifier.shadow(
                            elevation = 3.dp,
                            shape = RoundedCornerShape(surfaceRadius),
                            ambientColor = tokens.glassShadow.copy(alpha = 0.05f),
                            spotColor = tokens.glassShadow.copy(alpha = 0.05f),
                            clip = false,
                        )
                    } else {
                        Modifier
                    },
                )
                .squircleBackground(color = tokens.cardSurface, cornerRadius = surfaceRadius)
                .squircleClip(surfaceRadius)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeatMapCalendar(
                state = state,
                contentPadding = PaddingValues(end = 6.dp),
                dayContent = { day, week ->
                    val isClicked = uiState.selectedDate == day.date
                    val level = uiState.dateLevelMap[day.date] ?: Level.Zero
                    Day(
                        selected = isClicked,
                        day = day,
                        startDate = startDate,
                        endDate = now,
                        week = week,
                        level = level,
                    ) { date ->
                        onSelectedDate(date)
                    }
                },
                weekHeader = { WeekHeader(it) },
                monthHeader = { MonthHeader(it, now) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.heatmap_indicator_less),
                    style = typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Level.entries.forEach { level ->
                    LevelBox(
                        color = level.themedColor(isWeekend = false),
                        isZero = level == Level.Zero,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (uiState.thresholds > 0) {
                        stringResource(R.string.heatmap_indicator_threshold, uiState.thresholds)
                    } else {
                        stringResource(R.string.heatmap_indicator_more)
                    },
                    style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DailyStatsBlock(
    uiState: StatsOverviewUiState,
    onClickDetailScreen: (Int) -> Unit,
) {
    val selectedDate = uiState.selectedDate
    val records = uiState.bookRecordsByDate[selectedDate] ?: emptyList()
    val details = remember(records) { getDailyDetails(records) }
    val untitledBook = stringResource(R.string.cover_untitled)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedText(
                text = selectedDate.toString(),
                style = typography.titleLarge.copy(
                    fontWeight = FontWeight.W700,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (details?.formattedTotalTime?.isNotBlank() == true) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) {
                            onClickDetailScreen(
                                selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")).toInt(),
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.detail_title),
                        style = typography.labelLarge.copy(fontWeight = FontWeight.W500),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f),
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }

        EditorialHairlineDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.activity_reading_time),
                style = typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = details?.formattedTotalTime ?: "--",
                style = typography.titleMedium.copy(
                    fontWeight = FontWeight.W700,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Crossfade(
            targetState = details?.timeDetails.isNullOrEmpty(),
            label = "dailyStatsDetails",
        ) { isEmpty ->
            if (isEmpty) {
                NoRecords()
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    details?.timeDetails?.forEachIndexed { index, pair ->
                        val formattedTime = formReadingDuration(pair.second.toLong())
                        val bookInformation by pair.first.collectAsStateWithLifecycle(null)
                        bookInformation?.onOk {
                            DataItem(
                                leftText = it.title.ifBlank { untitledBook },
                                rightText = formattedTime,
                            )
                        }?.onErr {
                            DataItem(
                                leftText = untitledBook,
                                rightText = formattedTime,
                            )
                        } ?: DataItemPlaceholder(rightText = formattedTime)

                        if (index != details.timeDetails.lastIndex) {
                            EditorialHairlineDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoRecords() {
    Text(
        text = stringResource(R.string.no_records),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = typography.bodyMedium,
    )
}

private fun getDailyDetails(records: List<BookRecord>): DailyDateDetails? {
    if (records.isEmpty()) return null

    var totalSeconds = 0L
    val timeDetailsList = mutableListOf<Pair<Flow<Result<BookInformation, WebRequestError>>, Int>>()

    for (rec in records) {
        val seconds = rec.seconds
        totalSeconds += seconds
        timeDetailsList.add(rec.bookInformationFlow to seconds)
    }

    val sortedTimeDetails = timeDetailsList.sortedByDescending { it.second }
    val formattedTotalTime = formReadingDuration(totalSeconds)

    return DailyDateDetails(
        formattedTotalTime = formattedTotalTime,
        timeDetails = sortedTimeDetails,
    )
}

@Composable
private fun DataItem(leftText: String, rightText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = leftText,
            style = typography.bodyMedium.copy(fontWeight = FontWeight.W500),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = Ellipsis,
        )
        Text(
            text = rightText,
            style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun DataItemPlaceholder(rightText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Text(
            text = rightText,
            style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    hazeState: HazeState,
    onClickBack: () -> Unit,
) {
    EditorialTopBar(
        title = stringResource(R.string.nav_statistics),
        scrollBehavior = scrollBehavior,
        hazeState = hazeState,
        frosted = true,
        navigationIcon = {
            EditorialIconButton(
                onClick = onClickBack,
                painter = painterResource(id = R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
    )
}

@Composable
private fun Day(
    selected: Boolean? = false,
    day: CalendarDay,
    startDate: LocalDate,
    endDate: LocalDate,
    week: CalendarWeek,
    level: Level,
    onClick: (LocalDate) -> Unit,
) {
    val weekDates = week.days.map { it.date }
    val isWeekend = day.date.dayOfWeek.value !in 1..5

    if (day.date in startDate..endDate) {
        LevelBox(
            color = level.themedColor(isWeekend = isWeekend),
            selected = selected,
            isZero = level == Level.Zero,
        ) {
            onClick(day.date)
        }
    } else if (weekDates.contains(startDate)) {
        LevelBox(
            color = Color.Transparent,
        )
    }
}

@Composable
private fun LevelBox(
    color: Color,
    selected: Boolean? = false,
    isZero: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tileShape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(24.dp)
            .padding(2.dp)
            .clip(tileShape)
            .background(color = color)
            .then(
                if (selected == true) {
                    Modifier.border(
                        width = 1.8.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = tileShape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

@Composable
private fun WeekHeader(dayOfWeek: DayOfWeek) {
    val text = if (
        dayOfWeek in listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SUNDAY,
        )
    ) dayOfWeek.displayText() else ""

    Box(
        modifier = Modifier.height(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = text,
            style = typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonthHeader(
    calendarMonth: CalendarMonth,
    endDate: LocalDate,
) {
    if (calendarMonth.weekDays.first().first().date <= endDate) {
        val month = calendarMonth.yearMonth
        val title = if (month.month == Month.JANUARY) {
            month.displayText(short = true)
        } else {
            month.month.displayText()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(
                text = title,
                style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
