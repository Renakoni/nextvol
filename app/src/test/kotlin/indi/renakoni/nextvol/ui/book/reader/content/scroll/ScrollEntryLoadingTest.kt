package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.MutableReaderScreenUiState
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollEntryLoadingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var clicks = 0
    private val bookmarks = ReaderBookmarkSession()
    private val state: MutableScrollContentUiSate = MutableScrollContentUiSate({}, {}, {}, {}, {},
        onProgressRestoring = { state.isRestoringProgress = true },
        onProgressRestored = { state.isRestoringProgress = false },
    ).apply {
        bookId = "book"
        readingChapterId = "current"
        readingProgress = 0.5f
        isRestoringProgress = true
    }
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } returns 16f
        every { fontWeigh } returns 400f
        every { reduceMotion } returns true
        every { isUsingContinuousScrolling } returns true
    }
    private val loading = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun entryHasOneCenteredIndicatorUntilTheRequestedPositionIsReady() {
        mount()
        compose.onAllNodes(loading).assertCountEquals(1)
        val bounds = compose.onAllNodes(loading).fetchSemanticsNodes().single().boundsInRoot
        assertEquals(160f, bounds.center.x, 1f)
        assertEquals(160f, bounds.center.y, 1f)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { loadChapters() }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("Only one loading indicator may be exposed during entry",
                compose.onAllNodes(loading).fetchSemanticsNodes().size <= 1)
        }
        compose.mainClock.autoAdvance = true
        awaitRestoration()
        assertBody()
    }

    @Test fun cancellingPendingBookmarkRevealsTheBodyWithoutAllowingHiddenClicks() {
        // A requested bookmark has not switched chapters yet; cancellation keeps this chapter.
        bookmarks.pending = ReadingBookmark(bookId = "book", chapterId = "previous", chapterTitle = "Previous",
            componentIndex = 0, offset = 0, fingerprint = "0".repeat(64), preview = "", progress = 0f)
        loadChapters()
        mount()
        awaitRestoration()
        compose.onAllNodes(loading).assertCountEquals(1)
        compose.onNodeWithTag("body-current").assertDoesNotExist()
        compose.onRoot().performTouchInput { click() }
        assertEquals("Hidden chapter content must not receive taps", 0, clicks)
        compose.runOnIdle { bookmarks.pending = null }
        assertBody()
        compose.onRoot().performTouchInput { click() }
        assertEquals(1, clicks)
    }

    @Test fun firstReaderModeAppearsAtFullSizeWithoutAnInnerScaleTransition() {
        every { settings.reduceMotion } returns false
        val screen = MutableReaderScreenUiState(null)
        mount(screen)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { screen.contentUiState = state }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val bounds = compose.onAllNodes(loading).fetchSemanticsNodes().single().boundsInRoot
        assertEquals("Initial loader must use the final viewport scale", 46f, bounds.width, 0.5f)
        compose.mainClock.autoAdvance = true
    }

    private fun loadChapters() {
        state.contentList[0] = "previous" to Ok(chapter("previous", next = "current"))
        state.contentList[1] = "current" to Ok(chapter("current", "previous", "next"))
        state.contentList[2] = "next" to Ok(chapter("next", prev = "current"))
    }

    private fun mount(readerState: MutableReaderScreenUiState? = null) {
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme),
                        LocalReaderBookmarks provides bookmarks,
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings)) {
                        Box(Modifier.size(320.dp)) {
                            if (readerState == null) ScrollContentComponent(Modifier, state, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) }, PaddingValues(0.dp), {}, {}, {})
                            else indi.renakoni.nextvol.ui.book.reader.Content(true, readerState, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) }, {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitRestoration() {
        compose.waitUntil(10_000) { compose.waitForIdle(); !state.isRestoringProgress }
    }

    private fun assertBody() {
        compose.onAllNodes(loading).assertCountEquals(0)
        compose.onNodeWithTag("body-current").assertIsDisplayed()
        val layout = state.lazyListState.layoutInfo
        val current = layout.visibleItemsInfo.first { it.key == "current" }
        assertEquals(0.5f, (layout.viewportSize.height - current.offset).toFloat() / current.size, 0.01f)
    }

    private fun chapter(id: String, prev: String? = null, next: String? = null) =
        ChapterContentUiState(id, id, listOf(Body(id)), prev, next)

    private inner class Body(private val chapter: String) : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) {
            Box(Modifier.fillMaxWidth().height(2000.dp).testTag("body-$chapter").clickable { clicks++ })
        }
    }
}
