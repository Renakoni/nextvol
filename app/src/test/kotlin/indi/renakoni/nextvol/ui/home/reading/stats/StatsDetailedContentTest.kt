package indi.renakoni.nextvol.ui.home.reading.stats

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.statistics.BookRecord
import indi.renakoni.nextvol.data.statistics.Count
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.BookStack
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.MutableStatsDetailedUiState
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.StatisticsContent
import indi.renakoni.nextvol.ui.home.reading.stats.detailed.StatsViewOption
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookInformation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsDetailedContentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val date = LocalDate.of(2026, 10, 4)
    private var starts = 0
    private var stops = 0
    private val book = mockk<BookInformation> {
        every { id } returns "book"
        every { title } returns "Stable book"
        every { author } returns "Author"
        every { coverUri } returns Uri.EMPTY
    }
    private val information = flow {
        starts++
        try { emit(Ok(book)); awaitCancellation() } finally { stops++ }
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun changingPeriodKeepsVisibleActivitySubscriptionsMounted() {
        val state = MutableStatsDetailedUiState().apply {
            selectedDate = date
            targetDateRange = date.minusDays(6) to date
            bookFirstReadDateMap = mapOf(("book" to information) to date)
        }
        activity.get().setContent { MaterialTheme {
            StatisticsContent(state, listOf("Week", "Month", "Year"), onViewSelected = {
                state.selectedViewIndex = it
                state.displayedViewIndex = it
                state.targetDateRange = date.minusDays(6 + it.toLong()) to date
            })
        } }
        compose.waitForIdle()
        val initial = starts
        assertTrue(initial > 0)
        listOf("Month", "Year", "Week").forEach {
            compose.onNodeWithText(it).performClick()
            compose.waitForIdle()
            assertEquals(initial, starts)
            assertEquals(0, stops)
        }
    }

    @Test fun repeatedRecordsForOneBookCreateOnlyOneCoverSubscription() {
        val other = flow { starts++; emit(Ok(book)); awaitCancellation() }
        activity.get().setContent { MaterialTheme {
            BookStack(books = listOf("book" to information, "book" to other), count = 8)
        } }
        compose.waitForIdle()
        assertEquals(1, starts)
    }

    @Test fun periodTimesUpdateWithoutWaitingForNewBookMetadata() {
        val laterBook = CompletableDeferred<BookInformation>()
        val first = BookRecord("book", information, date, 1, 60,
            firstSeen = LocalTime.NOON, lastSeen = LocalTime.NOON)
        val records = mutableStateOf(listOf(first))
        activity.get().setContent { MaterialTheme { ReadingTimeBar(records.value) } }
        compose.onNodeWithText("01:00").assertIsDisplayed()
        assertEquals(1, starts)

        compose.runOnIdle {
            records.value = listOf(first.copy(seconds = 120), first.copy(
                bookId = "later", seconds = 180,
                bookInformationFlow = flow { emit(Ok(laterBook.await())) },
            ))
        }
        compose.onNodeWithText("01:00").assertDoesNotExist()
        compose.onNodeWithText("02:00").assertIsDisplayed()
        compose.onNodeWithText("03:00").assertIsDisplayed()
        compose.onNodeWithText("Stable book").assertIsDisplayed()
        assertEquals("An existing book must keep its metadata subscription", 1, starts)

        compose.runOnIdle {
            laterBook.complete(mockk { every { title } returns "Later book" })
        }
        compose.onNodeWithText("Later book").assertIsDisplayed()
        compose.onNodeWithText("03:00").assertIsDisplayed()
    }

    @Test fun sixWeekMonthFitsInsideANarrowChartWithoutScrolling() {
        val primary = Color(0xFF185ABC)
        val month = LocalDate.of(2026, 3, 31)
        val counts = (1..31).associate { day -> month.withDayOfMonth(day) to Count().apply { setMinute(12, 60) } }
        activity.get().setContent { MaterialTheme(colorScheme = lightColorScheme(primary = primary)) {
            Box(Modifier.width(220.dp)) {
                ReadingTimeStatsChart(counts, month, StatsViewOption.Weekly)
            }
        } }
        var columns = 0
        compose.waitUntil(timeoutMillis = 5_000) {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            var insideColumn = false
            columns = 0
            for (x in 0 until pixels.width) {
                val hasColumn = (0 until pixels.height).count { y -> pixels[x, y].toArgb() == primary.toArgb() } > 5
                if (hasColumn && !insideColumn) columns++
                insideColumn = hasColumn
            }
            columns > 0
        }
        assertEquals("All six week buckets must be visible when scrolling is disabled", 6, columns)
    }

}
