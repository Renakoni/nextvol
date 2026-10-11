package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.ui.book.reader.content.flip.MutableFlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.MutableScrollContentUiSate
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
class ReaderScreenUpdatesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var screenReads = 0
    private var bottomPadding by mutableStateOf(0f)
    private val reportedLayouts = mutableMapOf<String, MutableState<ReaderLayoutResult?>>()
    private lateinit var bookmarks: ReaderBookmarkSession
    private val content = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
        bookId = "book"
        readingChapterId = "a"
    }
    private val reader = MutableReaderScreenUiState(content).apply { bookId = "book" }

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        content.contentList[1] = "a" to Ok(chapter("a"))
    }

    @After fun teardown() { activity.pause().stop().destroy() }

    @Test fun immersiveChapterUpdatesKeepTheLayoutOwnerAndDoNotInvalidateTheScreenScope() =
        checkChapterUpdate(showMenu = false)

    @Test fun visibleMenuStillUpdatesItsTitleWithoutInvalidatingTheScreenScope() =
        checkChapterUpdate(showMenu = true)

    private fun checkChapterUpdate(showMenu: Boolean) {
        show()
        if (showMenu) compose.onNodeWithText("Body a").performTouchInput { click(center) }
        val (reads, layout) = compose.runOnIdle { screenReads to reportedLayouts.getValue("a") }
        compose.runOnIdle {
            content.contentList[1] = "b" to Ok(chapter("b"))
            content.readingChapterId = "b"
            content.readingProgress = .42f
        }
        compose.onNodeWithText("Body b").assertIsDisplayed()
        compose.onAllNodesWithText("Chapter b").assertCountEquals(if (showMenu) 2 else 1)
        compose.onAllNodesWithText("Chapter a").assertCountEquals(0)
        compose.runOnIdle {
            assertSame("Scroll geometry does not belong to a chapter", layout, reportedLayouts.getValue("b"))
            assertEquals("Chapter consumers must update without restarting the screen scope", reads, screenReads)
        }
    }

    @Test fun retainedScrollLayoutStillReportsChangedViewportGeometry() {
        show()
        val layout = compose.runOnIdle { reportedLayouts.getValue("a") }
        val height = compose.runOnIdle { layout.value!!.geometry!!.leafSize.height }
        compose.runOnIdle { bottomPadding = 32f }
        compose.runOnIdle {
            assertSame(layout, reportedLayouts.getValue("a"))
            assertEquals(height - 32, layout.value!!.geometry!!.leafSize.height)
            assertEquals(ReaderLayoutReason.ScrollMode, layout.value!!.reason)
        }
    }

    @Test fun modeSwitchAndFlipChapterChangesStillReplaceTheLayoutOwner() {
        show()
        val scrollLayout = compose.runOnIdle { reportedLayouts.getValue("a") }
        lateinit var flip: MutableFlipPageContentUiState
        flip = MutableFlipPageContentUiState({}, {}, {}, { flip.pagerState = it }).apply {
            bookId = "book"
            readingChapterId = "b"
            readingChapterContent = Ok(chapter("b"))
        }
        compose.runOnIdle { reader.contentUiState = flip }
        compose.onNodeWithText("Body b").assertIsDisplayed()
        val flipLayout = compose.runOnIdle { reportedLayouts.getValue("b") }
        assertNotSame(scrollLayout, flipLayout)
        compose.runOnIdle {
            flip.readingChapterContent = null
            flip.readingChapterId = "c"
        }
        compose.onNodeWithText("Body b").assertDoesNotExist()
        compose.runOnIdle { flip.readingChapterContent = Ok(chapter("c")) }
        compose.onNodeWithText("Body c").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(flipLayout, reportedLayouts.getValue("c"))
            assertEquals(ReaderLayoutReason.UnsupportedContent, reportedLayouts.getValue("c").value?.reason)
        }
    }

    @Test fun pendingBookmarkStillObservesChapterLoadFailure() {
        show()
        compose.runOnIdle {
            bookmarks.pending = ReadingBookmark(bookId = "book", chapterId = "b", chapterTitle = "Chapter b",
                componentIndex = 0, offset = 0, fingerprint = "a".repeat(64), preview = "", progress = 0f)
            content.readingChapterId = "b"
            content.contentList[1] = null
        }
        compose.runOnIdle { assertNotNull(bookmarks.pending) }
        compose.runOnIdle { content.contentList[1] = "b" to Err(mockk(relaxed = true)) }
        compose.runOnIdle { assertNull(bookmarks.pending) }
        compose.onNodeWithText(activity.get().getString(R.string.reader_bookmarks_load_failed)).assertIsDisplayed()
    }

    private fun show() {
        val settings = mockk<ReaderSettingsEditor>(relaxed = true) {
            every { paperId } returns "paper"
            every { fontFamilyUri } returns Uri.EMPTY
            every { fontSize } returns 15f
            every { fontWeigh } returns 500f
            every { reduceMotion } returns true
            every { enableChapterTitleIndicator } returns true
            every { enableReadingChapterProgressIndicator } returns true
            every { isUsingFlipPage } answers { reader.contentUiState is MutableFlipPageContentUiState }
            every { flipAnime } returns MenuOptions.FlipAnimationOptions.ScrollWithoutShadow
            every { bottomPadding } answers { this@ReaderScreenUpdatesTest.bottomPadding }
            every { backBlockMode } answers { screenReads++; MenuOptions.ReaderBackBlockMode.None }
        }
        val fonts = mockk<ReaderFontFamilySettings> { every { getFlow() } returns flowOf(Uri.EMPTY) }
        compose.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(LocalAppTheme provides AppTheme(false, lightColorScheme()),
                    LocalDensity provides Density(1f)) {
                    MaterialTheme {
                        ReaderScreen(reader, settings, fonts, {}, { _, _ -> }, { _, _ -> }, {}, {},
                            {}, {}, ReadAloudState(), {}, {}, {}, {})
                    }
                }
            }
        }
        // Compose can become idle while chapter preparation/restoration is still running.
        compose.waitUntil(5_000) { compose.onNodeWithText("Body a").isDisplayed() }
        compose.onNodeWithText("Body a").assertIsDisplayed()
    }

    private fun chapter(id: String) = ChapterContentUiState(id, "Chapter $id", listOf(Body(id)), null, null)

    private inner class Body(private val chapterId: String) :
        AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id = Identifier("fixture", "body")
        @Composable override fun Content(modifier: Modifier) {
            val result = LocalReaderLayoutResult.current!!
            val session = LocalReaderBookmarks.current
            if (session != null) SideEffect { reportedLayouts[chapterId] = result; bookmarks = session }
            Text("Body $chapterId", modifier.height(300.dp))
        }
    }
}
