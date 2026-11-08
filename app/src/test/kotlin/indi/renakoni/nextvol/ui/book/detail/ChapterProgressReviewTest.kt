package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChapterProgressReviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val chapters = (0..5).map { ChapterInformation("chapter-$it", "Chapter $it") }

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun destroy() { activity.pause().stop().destroy() }

    private fun state(progress: Map<String, Float> = mapOf(
        "chapter-0" to .7f, "chapter-1" to .1f, "chapter-2" to 1f,
        "chapter-3" to 0f, "chapter-5" to .9f,
    )) = MutableDetailUiState().apply {
        readingAvailable = true
        bookInformation = Ok(BookInformation("review-book", "Review book", author = "Author",
            description = "", publishingHouse = "", wordCount = WordCount(0),
            lastUpdated = LocalDateTime.of(2026, 9, 19, 0, 0), isComplete = false))
        bookVolumes = Ok(BookVolumes("review-book", listOf(Volume("v1", "Volume 1", chapters))))
        userReadingData = UserReadingData("review-book", lastReadChapterId = "chapter-5",
            currentChapterReadingProgressMap = mapOf("chapter-0" to .1f),
            maxChapterReadingProgressMap = progress)
    }

    private fun show(state: MutableDetailUiState, fontScale: Float = 1f, onChapter: (String) -> Unit = {}) {
        activity.get().setContent {
            CompositionLocalProvider(
                LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides SnackbarHostState(), LocalClaimSnackbarHost provides {},
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme { DetailScreen(state, {}, {}, onChapter, {}, {}, {}, {}, {}, {}) }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(activity.get().getString(R.string.detail_contents)))
    }

    @Test fun historyManualCompletionUnreadAndCurrentChapterUseTheExpectedMarkers() {
        val state = state()
        var clicked: String? = null
        show(state, onChapter = { clicked = it })
        compose.onNodeWithText("70%", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 1"))
        compose.onNodeWithText("10%", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 2"))
        compose.onNodeWithText("100%", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 3"))
        compose.onNodeWithText("0%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 5"))
        compose.onNodeWithText("90%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.last_read), useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 1"))
        compose.onNodeWithText("Chapter 1").performClick()
        assertEquals("chapter-1", clicked)
    }

    @Test fun changingProgressAndCurrentChapterRefreshesTheExistingDirectory() {
        val state = state()
        show(state)
        compose.runOnIdle {
            state.userReadingData = state.userReadingData!!.copy(
                lastReadChapterId = "chapter-0",
                maxChapterReadingProgressMap = state.userReadingData!!.maxChapterReadingProgressMap + ("chapter-1" to .8f),
            )
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 0"))
        compose.onNodeWithText("70%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Chapter 0") and hasContentDescription(activity.get().getString(R.string.last_read))).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 1"))
        compose.onNodeWithText("10%", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("80%", useUnmergedTree = true).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter 5"))
        compose.onNodeWithText("90%", useUnmergedTree = true).assertExists()
    }

}
