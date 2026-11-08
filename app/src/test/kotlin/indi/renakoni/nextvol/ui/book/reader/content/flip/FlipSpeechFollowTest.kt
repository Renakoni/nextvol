package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.tts.SpeechChapter
import indi.renakoni.nextvol.tts.SpeechPosition
import indi.renakoni.nextvol.tts.SpeechFollowEstimate
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.ReaderSpeechFollow
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
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
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlipSpeechFollowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val bookmarks = ReaderBookmarkSession()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var text = (1..80).joinToString("\n") { "PARAGRAPH_%03d".format(it) }
    private var speech by mutableStateOf(position(40))
    private var following by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private var height by mutableStateOf(300.dp)
    private var width by mutableStateOf(320.dp)
    private val state: MutableFlipPageContentUiState = MutableFlipPageContentUiState({}, {}, {},
        updatePageState = { state.pagerState = it })
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } returns 15f
        every { fontLineHeight } returns 7f
        every { fontWeigh } returns 500f
        every { paragraphSpacing } returns 4f
        every { isUsingFlipPage } returns true
        every { isUsingClickFlipPage } returns true
        every { reduceMotion } returns true
    }

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun speechFindsTheSourcePageAndManualPageTurnsSuspendFollowing() {
        mount()
        awaitText("PARAGRAPH_040")
        val firstPage = state.pagerState.currentPage
        assertTrue(firstPage > 0)
        val pager = state.pagerState
        compose.onNodeWithTag("viewport").performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f)) }
        compose.waitForIdle()
        assertFalse(following)
        assertEquals(firstPage + 1, state.pagerState.currentPage)
        compose.runOnIdle { speech = position(70) }
        compose.waitForIdle()
        assertEquals(firstPage + 1, state.pagerState.currentPage)
        compose.runOnIdle { following = true }
        awaitText("PARAGRAPH_070")
        assertSame("Highlight updates must not repaginate", pager, state.pagerState)
    }

    @Test fun resizingRepaginatesAroundTheAudibleCharacter() {
        mount()
        awaitText("PARAGRAPH_040")
        val oldPager = state.pagerState
        compose.runOnIdle { height = 180.dp }
        compose.waitUntil(10_000) { compose.waitForIdle(); state.pagerState !== oldPager && state.pagerState.pageCount > 0 }
        awaitText("PARAGRAPH_040")
        assertTrue(following)
    }

    @Test fun entryUsesSpeechBeforeResumeAndContinuousFollowingRequiresTheForeground() {
        active = false
        speech = speech.copy(end = position(70).end)
        mount()
        awaitText("PARAGRAPH_040")
        val initialPage = state.pagerState.currentPage
        compose.runOnIdle { speech = speech.copy(anchor = position(70).start) }
        compose.waitForIdle()
        assertEquals(initialPage, state.pagerState.currentPage)
        compose.runOnIdle { active = true }
        awaitText("PARAGRAPH_070")
    }

    @Test fun oneSentenceAcrossPagesTurnsAtTheAudibleCharacterWithoutRepagination() {
        assertSentenceAcrossPages(estimated = false)
    }

    @Test fun audioOnlySentenceAlsoFollowsAcrossPagesWithoutChangingItsHighlightRange() {
        assertSentenceAcrossPages(estimated = true)
    }

    @Test fun animatedFollowingFinishesDespiteMoreAnchorsOnTheSameTargetPage() {
        every { settings.reduceMotion } returns false
        assertSentenceAcrossPages(estimated = true, updateDuringAnimation = true)
    }

    private fun assertSentenceAcrossPages(estimated: Boolean, updateDuringAnimation: Boolean = false) {
        text = (1..24).joinToString(" ") { "word%03d".format(it) } + "."
        val estimate = SpeechFollowEstimate(text)
        fun anchor(offset: Int) = if (estimated) estimate.anchorAt(offset * 100L + 1, text.length * 100L)!! else offset
        speech = SpeechPosition("book", "chapter", SpeechChapter("book", "chapter", "", "", text).fingerprint,
            0, text.length)
        height = 70.dp
        mount()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.pagerState.pageCount > 1 }
        val pager = state.pagerState
        val first = compose.onNodeWithText("word001", substring = true, useUnmergedTree = true)
        first.assertIsDisplayed()
        val firstText = first.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(text.startsWith(firstText))
        assertTrue(firstText.length < text.length)
        val pageBoundary = firstText.length

        compose.runOnIdle { speech = speech.copy(anchor = anchor(pageBoundary - 1)) }
        compose.waitForIdle()
        assertEquals("The rest of the highlighted sentence must not turn the page early", 0, pager.currentPage)
        if (updateDuringAnimation) compose.mainClock.autoAdvance = false
        compose.runOnIdle { speech = speech.copy(anchor = anchor(pageBoundary)) }
        if (updateDuringAnimation) {
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { speech = speech.copy(anchor = anchor(pageBoundary + 1)) }
            compose.mainClock.advanceTimeBy(1000)
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); pager.settledPage == 1 }
        compose.onNodeWithText(text.substring(pageBoundary).trimStart().take(7), substring = true,
            useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, speech.start)
        assertEquals(text.length, speech.end)
        assertSame(pager, state.pagerState)
        assertTrue(following)
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().any { it.boundsInRoot.width > 0 }
        }
        compose.onNodeWithText(value, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun position(paragraph: Int): SpeechPosition {
        val start = text.indexOf("PARAGRAPH_%03d".format(paragraph))
        return SpeechPosition("book", "chapter", SpeechChapter("book", "chapter", "", "", text).fingerprint, start, start + 13)
    }

    @Test fun bookmarkRestoresItsCharacterAfterResizeAndDoesNotFollowSpeech() {
        mount()
        awaitText("PARAGRAPH_040")
        val saved = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertTrue(saved.offset > 0)
        compose.runOnIdle { following = false; height = 180.dp }
        compose.waitForIdle()
        compose.onNodeWithTag("viewport").performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(10f, 0f)) }
        compose.runOnIdle { bookmarks.pending = saved }
        compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
        val actual = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertEquals(saved.offset, actual.offset)
        assertFalse(following)
        val page = state.pagerState.currentPage
        compose.runOnIdle { bookmarks.pending = saved.copy(fingerprint = "b".repeat(64)) }
        compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
        assertEquals(page, state.pagerState.currentPage)
        assertEquals(indi.renakoni.nextvol.R.string.reader_bookmarks_changed, bookmarks.notice)
    }

    @Test
    @Config(qualifiers = "w1100dp-h800dp")
    fun speechMovesWithinBothLeavesBeforeTurningToTheNextSpread() {
        width = 1000.dp
        height = 600.dp
        text = (1..600).joinToString(" ") { "word%03d".format(it) }
        speech = SpeechPosition("book", "chapter", SpeechChapter("book", "chapter", "", "", text).fingerprint, 0, text.length)
        mount()
        compose.waitUntil(10_000) { compose.waitForIdle(); state.visibleLeafRange.count() == 2 }
        val pager = state.pagerState
        fun leafText(leaf: Int) = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and hasAnyAncestor(hasTestTag("reader-leaf-$leaf")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().config[SemanticsProperties.Text].single().text
        val left = leafText(0)
        val right = leafText(1)
        val rightStart = text.indexOf(right)
        assertTrue(rightStart >= left.length)
        compose.runOnIdle { speech = speech.copy(anchor = rightStart + 1) }
        compose.waitForIdle()
        assertEquals(0, pager.currentPage)
        assertEquals(0..1, state.visibleLeafRange)
        assertTrue(following)
        compose.runOnIdle { speech = speech.copy(anchor = rightStart + right.length) }
        compose.waitUntil(10_000) { compose.waitForIdle(); pager.settledPage == 1 }
        assertEquals(2..3, state.visibleLeafRange)
        assertSame(pager, state.pagerState)
        assertTrue(following)
        assertEquals(text.length, speech.end)
    }

    private fun mount() {
        val content = SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get())
        state.bookId = "book"
        state.readingChapterId = "chapter"
        state.readingChapterContent = Ok(ChapterContentUiState("chapter", "Chapter", listOf(content), null, null))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalReaderBookmarks provides bookmarks, LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme),
                        LocalSnackbarHost provides remember { SnackbarHostState() },
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings),
                        LocalReaderSpeechFollow provides ReaderSpeechFollow(speech, following, { following = false }, active)) {
                        Box(Modifier.width(width).height(height).testTag("viewport")) {
                            FlipPageContentComponent(Modifier, state, settings, PaddingValues(0.dp), {}, {}, {})
                        }
                    }
                }
            }
        }
    }
}
