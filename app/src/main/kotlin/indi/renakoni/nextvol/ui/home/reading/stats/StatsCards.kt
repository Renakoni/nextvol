package indi.renakoni.nextvol.ui.home.reading.stats

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.BookStack
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.StatsCard
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.StatsDetailedUiState
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.currentDateRange
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

val predefinedColors = listOf(
    Color(0xFF3E6BB3), // Archival Indigo
    Color(0xFF3F8F64), // Editorial Jade
    Color(0xFFC26A4A), // Warm Terracotta
    Color(0xFF7B5EA7), // Muted Plum
    Color(0xFF2D8C96), // Slate Teal
    Color(0xFFC78F38), // Ochre Gold
    Color(0xFFB5546E), // Dusty Rose
    Color(0xFF5D768E), // Steel Blue
)

private fun assignColors(
    records: List<BookRecord>,
    fallbackColor: Color,
): Map<String, Color> {
    return records
        .groupBy { it.bookId }
        .mapValues { (_, list) -> list.sumOf { it.seconds } }
        .toList()
        .sortedByDescending { it.second }
        .mapIndexed { index, (bookId, _) ->
            val color = predefinedColors.getOrElse(index) { fallbackColor }
            bookId to color
        }
        .toMap()
}

/**
 * @return startedBooks/finishedBooks 在日期范围内的 BookId 列表
 */
private fun getBooksInRange(
    bookDateMap: Map<Pair<String, Flow<Result<BookInformation, WebRequestError>>>, LocalDate>,
    dateRange: ClosedRange<LocalDate>,
): List<Pair<String, Flow<Result<BookInformation, WebRequestError>>>> {
    return bookDateMap
        .filterValues { it in dateRange }
        .toList()
        .sortedBy { it.second }
        .map { it.first }
}

/**
 * 统计详情: 活动卡片的行
 */
@Composable
private fun BookActivitySection(
    titleResId: Int,
    books: List<Pair<String, Flow<Result<BookInformation, WebRequestError>>>>,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) return
    val untitledBook = stringResource(R.string.cover_untitled)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(titleResId),
                style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                color = colorScheme.onSurface,
            )
            val bookList = books.take(2)
            bookList.forEach { (bookId, flow) ->
                key(bookId) {
                    val result by flow.collectAsStateWithLifecycle(null)
                    result?.onOk {
                        Text(
                            text = it.title.ifBlank { untitledBook },
                            style = typography.bodySmall,
                            maxLines = 1,
                            color = colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }?.onErr {
                        Text(
                            text = untitledBook,
                            style = typography.bodySmall,
                            maxLines = 1,
                            color = colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } ?: Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colorScheme.surfaceContainerHigh),
                    )
                }
            }
            if (books.size > bookList.size) {
                Text(
                    text = stringResource(R.string.activity_etc, books.size),
                    style = typography.labelSmall.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box {
            BookStack(
                modifier = Modifier,
                books = books,
                count = 5,
                rotate = 4.5f,
                scaleEnabled = false,
            )
        }
    }
}

/**
 * 活动卡片（适用各种时间范围）
 */
@Composable
fun ActivityStatsCard(
    uiState: StatsDetailedUiState,
    modifier: Modifier = Modifier,
) {
    val dateRange = uiState.currentDateRange
    val startedBooks = getBooksInRange(uiState.bookFirstReadDateMap, dateRange)
    val finishedBooks = getBooksInRange(uiState.bookFirstFinishedDateMap, dateRange)
    val favoriteBooks = getBooksInRange(uiState.bookFavoriteDateMap, dateRange)

    val hasActivity = startedBooks.isNotEmpty() || finishedBooks.isNotEmpty() || favoriteBooks.isNotEmpty()
    if (!hasActivity) return

    StatsCard(
        modifier = modifier,
        title = stringResource(R.string.activity),
    ) {
        Column {
            val sections = listOf(
                R.string.activity_first_read to startedBooks,
                R.string.activity_collections to favoriteBooks,
                R.string.activity_finished to finishedBooks,
            ).filter { it.second.isNotEmpty() }

            sections.forEachIndexed { index, (title, books) ->
                key(title) {
                    BookActivitySection(
                        titleResId = title,
                        books = books,
                    )
                    if (index != sections.lastIndex) {
                        EditorialHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * 阅读详情卡片（适用各种时间范围）
 */
@Composable
fun ReadingDetailStatsCard(
    uiState: StatsDetailedUiState,
) {
    val dateRange = uiState.currentDateRange
    val allRecords = uiState.targetDateRangeRecordsMap
        .filterKeys { it in dateRange }
        .values
        .flatten()

    StatsCard(title = stringResource(R.string.reading_details)) {
        if (allRecords.isEmpty()) {
            Text(
                text = stringResource(R.string.no_records),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                textAlign = TextAlign.Center,
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val books = allRecords
                        .sortedBy { it.lastSeen }
                        .map { it.bookId to it.bookInformationFlow }
                        .distinctBy { it.first }
                    BookStack(
                        books = books,
                        count = 8,
                        compact = false,
                    )
                    Spacer(Modifier.weight(1f))
                }

                ReadingTimeBar(recordList = allRecords)
            }
        }
    }
}

@Composable
fun ReadingTimeBar(
    recordList: List<BookRecord>?,
) {
    if (recordList.isNullOrEmpty()) return

    val fallbackColor = colorScheme.outlineVariant
    val untitledLabel = stringResource(R.string.cover_untitled)
    val othersLabel = stringResource(R.string.others)

    val colorMap = remember(recordList, fallbackColor) {
        assignColors(recordList, fallbackColor)
    }

    val groupedTime = remember(recordList) {
        recordList.groupBy { it.bookId }.values
            .map { it.first() to it.sumOf(BookRecord::seconds) }
            .sortedByDescending { it.second }
    }
    // Times belong to the committed period. Metadata may arrive later, without holding back
    // those times or restarting subscriptions for books that remain in the visible list.
    val barItems = buildList {
        groupedTime.take(8).forEach { (record, seconds) ->
            key(record.bookId) {
                val information by record.bookInformationFlow.collectAsStateWithLifecycle(null)
                val resolvedTitle = information?.get()?.title?.takeIf { it.isNotBlank() } ?: untitledLabel
                add(
                    TimeBarItem(
                        title = resolvedTitle,
                        timeSeconds = seconds,
                        color = colorMap[record.bookId] ?: fallbackColor,
                    ),
                )
            }
        }
        val othersTime = groupedTime.drop(8).sumOf { it.second }
        if (othersTime > 0) add(TimeBarItem(othersLabel, othersTime, fallbackColor))
    }
    val normalizedItems = remember(barItems) {
        barItems.normalize()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            normalizedItems.fastForEach { (item, ratio) ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(ratio)
                        .clip(RoundedCornerShape(3.dp))
                        .background(item.color),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            normalizedItems.fastForEach { (item, _) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(item.color),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        modifier = Modifier.weight(1f),
                        text = item.title,
                        style = typography.bodySmall,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = DateUtils.formatElapsedTime(item.timeSeconds.toLong()),
                        style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
