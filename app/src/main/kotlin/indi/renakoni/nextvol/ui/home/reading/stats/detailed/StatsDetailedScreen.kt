package indi.renakoni.nextvol.ui.home.reading.stats.detailed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.reading.stats.ActivityStatsCard
import indi.renakoni.nextvol.ui.home.reading.stats.ReadingDetailStatsCard
import indi.renakoni.nextvol.ui.home.reading.stats.ReadingTimeStatsChart
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

sealed class StatsViewOption(val viewIndex: Int) {
    abstract fun rangeFor(date: LocalDate): ClosedRange<LocalDate>

    object Daily : StatsViewOption(viewIndex = 0) {
        override fun rangeFor(date: LocalDate): ClosedRange<LocalDate> {
            return date.minusDays(6)..date
        }
    }

    object Weekly : StatsViewOption(viewIndex = 1) {
        override fun rangeFor(date: LocalDate): ClosedRange<LocalDate> {
            val startOfMonth = date.withDayOfMonth(1)
            val endOfMonth = date.with(TemporalAdjusters.lastDayOfMonth())
            return startOfMonth..endOfMonth
        }
    }

    object Monthly : StatsViewOption(viewIndex = 2) {
        override fun rangeFor(date: LocalDate): ClosedRange<LocalDate> {
            val startOfYear = LocalDate.of(date.year, 1, 1)
            val endOfYear = LocalDate.of(date.year, 12, 31)
            return startOfYear..endOfYear
        }
    }

    companion object {
        fun fromIndex(index: Int): StatsViewOption = when (index) {
            Daily.viewIndex -> Daily
            Weekly.viewIndex -> Weekly
            Monthly.viewIndex -> Monthly
            else -> throw IllegalArgumentException("invalid viewIndex $index")
        }
    }
}

private operator fun LocalDate.rangeTo(other: LocalDate): ClosedRange<LocalDate> = object : ClosedRange<LocalDate> {
    override val start: LocalDate = this@rangeTo
    override val endInclusive: LocalDate = other
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsDetailedScreen(
    targetDate: LocalDate,
    initialize: (LocalDate) -> Unit,
    viewModel: StatsDetailedViewModel,
    onClickBack: () -> Unit,
) {
    val uiState = viewModel.uiState
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val viewOptions = listOf(
        stringResource(R.string.view_weekly),
        stringResource(R.string.view_monthly),
        stringResource(R.string.view_yearly),
    )

    LaunchedEffect(targetDate) {
        initialize(targetDate)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pinnedScrollBehavior.nestedScrollConnection),
        topBar = {
            TopBar(
                scrollBehavior = pinnedScrollBehavior,
                onClickBack = onClickBack,
                dateRange = uiState.targetDateRange,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        StatisticsContent(
            uiState = uiState,
            modifier = Modifier.padding(paddingValues),
            viewOptions = viewOptions,
            onViewSelected = viewModel::setSelectedView,
        )
    }
}

@Composable
internal fun StatisticsContent(
    uiState: StatsDetailedUiState,
    viewOptions: List<String>,
    onViewSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        stickyHeader {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surface.copy(alpha = 0.96f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EditorialPillTabRow(
                        tabs = viewOptions,
                        selectedTabIndex = uiState.selectedViewIndex.coerceIn(0, viewOptions.lastIndex),
                        onTabSelected = { index ->
                            onViewSelected(index)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                EditorialHairlineDivider()
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
        }

        item(key = "activity") {
            ActivityStatsCard(uiState)
        }
        item(key = "reading-time") {
            StatsCard(title = stringResource(R.string.activity_reading_time)) {
                if (uiState.hasData) {
                    ReadingTimeStatsChart(
                        statsMap = uiState.targetDateRangeCountMap,
                        selectedDate = uiState.selectedDate,
                        viewOption = StatsViewOption.fromIndex(uiState.displayedViewIndex),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                text = stringResource(R.string.no_records),
                                style = typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item(key = "reading-details") {
            ReadingDetailStatsCard(uiState)
        }
        navigationBarSpacer()
    }
}

@Composable
fun StatsCard(
    modifier: Modifier = Modifier,
    title: String,
    subTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            SectionHeader(text = title)
            if (subTitle != null) {
                Text(
                    text = subTitle,
                    style = typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }

        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            contentPadding = PaddingValues(18.dp),
            content = content,
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun BookStack(
    modifier: Modifier = Modifier,
    books: List<Pair<String, Flow<Result<BookInformation, WebRequestError>>>>,
    count: Int,
    scaleEnabled: Boolean = false,
    compact: Boolean = true,
    rotate: Float? = null,
) {
    val displayBooks = books.distinctBy { it.first }.take(count)

    BoxWithConstraints(
        modifier = modifier.then(
            if (compact) {
                Modifier
                    .wrapContentWidth()
                    .padding(end = (displayBooks.size * 20).dp)
            } else {
                Modifier.fillMaxWidth()
            },
        ),
    ) {
        val baseWidth = 63.dp
        val baseOffset = 20.dp

        val offsetStep = if (compact) {
            baseOffset
        } else {
            if (displayBooks.size <= 1) {
                0.dp
            } else {
                val availableWidth = maxWidth - baseWidth
                (availableWidth / (displayBooks.size - 1)).coerceAtMost(baseWidth)
            }
        }

        displayBooks.fastForEachIndexed { index, pair ->
            key(pair.first) {
                val scale = if (scaleEnabled) {
                    1f - (index * 0.01f).coerceAtMost(0.3f)
                } else {
                    1f
                }

                val offsetY = remember(pair.first) {
                    (Math.floorMod(pair.first.hashCode(), 7) - 3).dp
                }

                val itemWidth = 63.dp * scale
                val itemHeight = 90.dp * scale

                Box(
                    modifier = Modifier
                        .wrapContentHeight()
                        .zIndex((displayBooks.size - index).toFloat())
                        .align(Alignment.CenterStart)
                        .offset(
                            x = offsetStep * index,
                            y = offsetY,
                        )
                        .graphicsLayer {
                            rotationZ = rotate ?: 0f
                        },
                ) {
                    val result by pair.second.collectAsStateWithLifecycle(null)
                    result?.onOk {
                        Cover(
                            bookId = it.id,
                            width = itemWidth,
                            height = itemHeight,
                            uri = it.coverUri,
                            title = it.title,
                            author = it.author,
                            rounded = 7.dp,
                        )
                    }?.onErr {
                        MiniCoverPlaceholder(width = itemWidth, height = itemHeight)
                    } ?: MiniCoverPlaceholder(width = itemWidth, height = itemHeight)
                }
            }
        }
    }
}

@Composable
private fun MiniCoverPlaceholder(
    width: Dp,
    height: Dp,
) {
    val tokens = NextVolDesign.tokens
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(colorScheme.surfaceContainerHigh)
            .border(0.75.dp, tokens.cardBorder, shape),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    dateRange: Pair<LocalDate, LocalDate>,
    scrollBehavior: TopAppBarScrollBehavior,
    onClickBack: () -> Unit,
) {
    val rangeText = if (dateRange.first == dateRange.second) {
        dateRange.second.toString()
    } else {
        "${dateRange.first} ${stringResource(R.string.to)} ${dateRange.second}"
    }
    EditorialTopBar(
        title = stringResource(R.string.detail_title),
        subtitle = rangeText,
        scrollBehavior = scrollBehavior,
        hazeState = null,
        frosted = false,
        navigationIcon = {
            EditorialIconButton(
                onClick = onClickBack,
                painter = painterResource(id = R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
    )
}
