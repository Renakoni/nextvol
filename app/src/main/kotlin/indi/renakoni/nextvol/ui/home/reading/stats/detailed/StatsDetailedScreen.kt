package indi.renakoni.nextvol.ui.home.reading.stats.detailed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.home.reading.stats.ActivityStatsCard
import indi.renakoni.nextvol.ui.home.reading.stats.ReadingTimeStatsChart
import indi.renakoni.nextvol.ui.home.reading.stats.ReadingDetailStatsCard
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
    onClickBack: () -> Unit
) {
    val uiState = viewModel.uiState
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val viewOptions = listOf(
        stringResource(R.string.view_weekly),
        stringResource(R.string.view_monthly),
        stringResource(R.string.view_yearly)
    )

    LaunchedEffect(targetDate) {
        initialize(targetDate)
    }

    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = pinnedScrollBehavior,
                onClickBack = onClickBack,
                dateRange = uiState.targetDateRange
            )
        }
    ) { paddingValues ->
        StatisticsContent(
            uiState = uiState,
            modifier = Modifier.padding(paddingValues),
            viewOptions = viewOptions,
            onViewSelected = viewModel::setSelectedView
        )
    }
}

@Composable
internal fun StatisticsContent(
    uiState: StatsDetailedUiState,
    viewOptions: List<String>,
    onViewSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    LazyColumn(modifier.fillMaxSize()) {
        stickyHeader {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(colorScheme.background)
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                SingleChoiceSegmentedButtonRow {
                    viewOptions.forEachIndexed { index, label ->
                        SegmentedButton(
                            modifier = Modifier.width(90.dp),
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = viewOptions.size
                            ),
                            onClick = { onViewSelected(index).let {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            } },
                            selected = uiState.selectedViewIndex == index
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }

        item(key = "activity") {
            ActivityStatsCard(uiState)
        }
        item(key = "reading-time") {
            StatsCard(title = stringResource(R.string.activity_reading_time)) {
                if (uiState.hasData) ReadingTimeStatsChart(
                    statsMap = uiState.targetDateRangeCountMap,
                    selectedDate = uiState.selectedDate,
                    viewOption = StatsViewOption.fromIndex(uiState.displayedViewIndex),
                ) else Spacer(Modifier.fillMaxWidth().height(280.dp))
            }
        }
        item(key = "reading-details") {
            ReadingDetailStatsCard(uiState)
        }
    }
}

@Composable
fun StatsCard(
    modifier: Modifier = Modifier,
    title: String,
    subTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = typography.titleMedium,
                fontWeight = FontWeight.W600
            )
            if (subTitle != null) {
                Text(
                    text = subTitle,
                    style = typography.titleSmall,
                    color = colorScheme.secondary
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
        Spacer(Modifier.height(16.dp))
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
            }
        )
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
                } else 1f

                val offsetY = remember(pair.first) {
                    (Math.floorMod(pair.first.hashCode(), 7) - 3).dp
                }

                Box(
                    modifier = Modifier
                        .wrapContentHeight()
                        .zIndex((displayBooks.size - index).toFloat())
                        .align(Alignment.CenterStart)
                        .offset(
                            x = offsetStep * index,
                            y = offsetY
                        )
                        .graphicsLayer {
                            rotationZ = rotate ?: 0f
                        }
                ) {
                    val result by pair.second.collectAsStateWithLifecycle(null)
                    result?.onOk {
                        Cover(
                            bookId = it.id,
                            width = 63.dp * scale,
                            height = 90.dp * scale,
                            uri = it.coverUri,
                            title = it.title,
                            author = it.author,
                            rounded = 6.dp
                        )
                    }?.onErr {
                        //TODO 错误显示
                    } ?: {
                        //TODO 加载显示
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    dateRange: Pair<LocalDate, LocalDate>,
    scrollBehavior: TopAppBarScrollBehavior,
    onClickBack: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.detail_title),
                    style = typography.displayLarge,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (dateRange.first == dateRange.second) dateRange.second.toString()
                    else "${dateRange.first} " + stringResource(R.string.to) + " ${dateRange.second} ",
                    style = typography.labelMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClickBack) {
                Icon(
                    painter = painterResource(id = R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.sources_back)
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
