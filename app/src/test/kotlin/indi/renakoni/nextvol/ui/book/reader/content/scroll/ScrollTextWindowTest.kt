package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.tts.SpeechPosition
import indi.renakoni.nextvol.tts.SpeechChapter
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
class ScrollTextWindowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val bookmarks = ReaderBookmarkSession()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var scope: CoroutineScope
    private var speech by mutableStateOf<SpeechPosition?>(null)
    private var following by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private var componentMeasurements = 0
    private var fontSize by mutableStateOf(15f)
    private val chapterText = (1..200).joinToString("\n") { "PARAGRAPH_%03d".format(it) }
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } answers { this@ScrollTextWindowTest.fontSize }
        every { fontLineHeight } returns 7f
        every { fontWeigh } returns 500f
        every { paragraphSpacing } returns 4f
        every { reduceMotion } returns true
    }
    private val state: MutableScrollContentUiSate = MutableScrollContentUiSate({}, {}, {}, {}, {},
        onProgressRestored = { state.isRestoringProgress = false })

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun speechWinsInitialRestoreAndManualScrollStaysDetachedUntilExplicitReturn() {
        every { settings.isUsingContinuousScrolling } returns true
        speech = position(100)
        mount(0.9f)
        awaitBody()
        compose.onNodeWithText("PARAGRAPH_100", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(following)
        assertBoundedText()
        val height = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size

        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertFalse(following)
        val manualOffset = state.lazyListState.firstVisibleItemScrollOffset
        compose.runOnIdle { speech = position(180) }
        compose.waitForIdle()
        assertEquals(manualOffset, state.lazyListState.firstVisibleItemScrollOffset)

        compose.runOnIdle { following = true }
        compose.waitForIdle()
        compose.onNodeWithText("PARAGRAPH_180", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(height, state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size)
        assertBoundedText()
    }

    @Test fun reflowKeepsSpeechVisibleWithoutChangingTheSourceAnchor() {
        speech = position(100)
        mount(0f, splitComponents = true)
        awaitBody()
        val oldHeight = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        compose.runOnIdle { fontSize = 24f }
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            val node = compose.onAllNodes(hasText("PARAGRAPH_100"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            val newHeight = state.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "chapter" }?.size ?: 0
            newHeight > oldHeight && node != null && node.boundsInRoot.top >= 0 &&
                node.boundsInRoot.bottom <= 320 * activity.get().resources.displayMetrics.density
        }
        compose.onNodeWithText("PARAGRAPH_100", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { speech = position(180) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_180"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("PARAGRAPH_180", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(following)
        assertBoundedText()
    }

    @Test fun entryUsesSpeechBeforeResumeButBackgroundDoesNotKeepScrolling() {
        active = false
        speech = position(100)
        mount(0.9f)
        awaitBody()
        compose.onNodeWithText("PARAGRAPH_100", useUnmergedTree = true).assertIsDisplayed()
        val initialOffset = state.lazyListState.firstVisibleItemScrollOffset
        compose.runOnIdle { speech = position(180) }
        compose.waitForIdle()
        assertEquals(initialOffset, state.lazyListState.firstVisibleItemScrollOffset)
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.onNodeWithText("PARAGRAPH_180", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun speechEntryPassesACachedPreviousChapterWhileCurrentTextIsBeingLaidOut() {
        every { settings.isUsingContinuousScrolling } returns true
        speech = position(100)
        mount(0f, previousText = "Previous short chapter")
        awaitBody()
        compose.onNodeWithText("PARAGRAPH_100", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(state.isRestoringProgress)
    }

    private fun position(paragraph: Int): SpeechPosition {
        val start = chapterText.indexOf("PARAGRAPH_%03d".format(paragraph))
        return SpeechPosition("book", "chapter", SpeechChapter("book", "chapter", "", "", chapterText).fingerprint, start, start + 13)
    }

    @Test fun restoredLongChapterOnlyComposesNearbyTextAndKeepsItsFullGeometry() {
        mount(0.5f)
        awaitBody()
        val info = state.lazyListState.layoutInfo
        val item = info.visibleItemsInfo.single { it.key == "chapter" }
        assertTrue(item.size > info.viewportSize.height * 10)
        assertEquals(0.5f, (-item.offset + info.viewportSize.height).toFloat() / item.size, 0.01f)
        assertBoundedText()
        compose.onNodeWithText("PARAGRAPH_001", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("PARAGRAPH_200", useUnmergedTree = true).assertDoesNotExist()

        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, 0) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_001"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("PARAGRAPH_001", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("PARAGRAPH_200", useUnmergedTree = true).assertDoesNotExist()
        assertBoundedText()
    }

    @Test fun scrollingToTheEndAndBackDoesNotLeaveAnEmptyTextWindow() {
        mount(0f)
        awaitBody()
        val height = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, height) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_200"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertBoundedText()
        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, height / 2) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_100"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertBoundedText()
    }

    @Test fun oneVeryLongParagraphIsAlsoLimitedToNearbyFragments() {
        val text = (1..4_000).joinToString(" ") { "word$it" }
        mount(0.5f, text)
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !state.isRestoringProgress
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("word", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val visibleText = compose.onAllNodes(hasText("word", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString("") { it.text }
        assertTrue(visibleText.length in 1 until text.length / 3)
        val info = state.lazyListState.layoutInfo
        assertTrue(info.visibleItemsInfo.single { it.key == "chapter" }.size > info.viewportSize.height * 10)
        assertFalse(visibleText.contains("word1 "))
        assertFalse(visibleText.contains("word4000"))
    }

    @Test fun reopeningTheSameChapterWithNewContentRestoresUsingItsNewHeight() {
        mount(0.5f)
        awaitBody()
        val oldHeight = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        val replacement = SimpleTextComponent(SimpleTextComponentData(
            (1..400).joinToString("\n") { "PARAGRAPH_%03d".format(it) }), mockk(relaxed = true), activity.get())
        compose.runOnIdle {
            state.contentList[1] = "chapter" to Ok(ChapterContentUiState("chapter", "Chapter", listOf(replacement), null, null))
            state.readingProgress = 0.5f
            state.isRestoringProgress = true
            state.lazyListState = LazyListState()
        }
        awaitBody()
        val info = state.lazyListState.layoutInfo
        val item = info.visibleItemsInfo.single { it.key == "chapter" }
        assertTrue(item.size > oldHeight * 1.9f)
        assertEquals(0.5f, (-item.offset + info.viewportSize.height).toFloat() / item.size, 0.01f)
        assertBoundedText()
    }

    @Test fun thousandIndependentComponentsBoundLayoutWorkAndRestoreTheirOriginalBookmark() {
        following = false
        val text = (1..1097).joinToString("\n") { "PARAGRAPH_%04d".format(it) }
        mount(0.5f, text, splitComponents = true)
        awaitBody()
        assertBoundedText()
        assertTrue("Initial component measurements must be bounded: $componentMeasurements",
            componentMeasurements < 80)
        val saved = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertTrue("Retain the source component, not a merged-text index", saved.componentIndex > 400)
        val height = state.lazyListState.layoutInfo.visibleItemsInfo.single { it.key == "chapter" }.size
        val measured = componentMeasurements
        repeat(10) {
            compose.runOnIdle { state.lazyListState.dispatchRawDelta(1f) }
            compose.waitForIdle()
        }
        assertTrue("Steady scrolling must not measure every source component",
            componentMeasurements - measured < 40)
        compose.runOnIdle { scope.launch { state.lazyListState.scrollToItem(1, height) } }
        compose.waitForIdle()
        compose.onNodeWithText("PARAGRAPH_1097", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            scope.launch { state.lazyListState.scrollToItem(1, 0) }
            fontSize = 24f
        }
        compose.waitForIdle()
        compose.runOnIdle { bookmarks.pending = saved }
        compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
        compose.onNodeWithText(saved.preview, useUnmergedTree = true).assertIsDisplayed()
        val actual = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertEquals(saved.componentIndex, actual.componentIndex)
        assertEquals(saved.offset, actual.offset)
        assertBoundedText()
    }

    @Test fun bookmarkAfterAResizedOpaqueComponentUsesItsNewPlacedOrigin() {
        following = false
        var componentHeight by mutableStateOf(80.dp)
        val resizable = object : AbstractContentComponent<ImageComponentData>(
            ImageComponentData(Uri.parse("file:///fixture.png"))) {
            override val id = ImageComponentData.id
            @Composable override fun Content(modifier: Modifier) {
                Spacer(modifier.height(componentHeight))
            }
        }
        val components = chapterText.split('\n').map(::textComponent).toMutableList<AbstractContentComponent<*>>()
        components.add(2, resizable)
        mount(0.5f, sourceComponents = components)
        awaitBody()
        val saved = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertTrue(saved.componentIndex > 2)
        fun restore(): Int {
            compose.runOnIdle { bookmarks.pending = saved }
            compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
            compose.onNodeWithText(saved.preview, useUnmergedTree = true).assertIsDisplayed()
            val actual = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
            assertEquals(saved.componentIndex, actual.componentIndex)
            assertEquals(saved.offset, actual.offset)
            return state.lazyListState.firstVisibleItemScrollOffset
        }
        val before = restore()
        compose.runOnIdle { componentHeight = 280.dp }
        compose.waitForIdle()
        assertEquals(200 * activity.get().resources.displayMetrics.density,
            (restore() - before).toFloat(), 1f)
    }

    private fun assertBoundedText() {
        val count = compose.onAllNodes(hasText("PARAGRAPH_", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("Only the viewport and nearby text should be composed, found $count", count in 1..65)
    }

    private fun awaitBody() {
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            !state.isRestoringProgress
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("PARAGRAPH_", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun bookmarkReturnsToTheSameParagraphAfterFontReflowAndRejectsChangedText() {
        following = false
        mount(0.5f)
        awaitBody()
        val saved = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertTrue(saved.offset > 0)
        val expected = saved.preview.substringBefore(' ')
        compose.runOnIdle {
            scope.launch { state.lazyListState.scrollToItem(1, 0) }
            fontSize = 24f
        }
        compose.waitForIdle()
        compose.runOnIdle { bookmarks.pending = saved }
        compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
        compose.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        val actual = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        assertEquals(saved.offset, actual.offset)
        compose.runOnIdle { bookmarks.pending = saved.copy(fingerprint = "b".repeat(64)) }
        compose.waitUntil(10_000) { compose.waitForIdle(); bookmarks.pending == null }
        assertEquals(indi.renakoni.nextvol.R.string.reader_bookmarks_changed, bookmarks.notice)
    }

    private fun textComponent(text: String) =
        SimpleTextComponent(SimpleTextComponentData(text), mockk(relaxed = true), activity.get())

    private fun mount(progress: Float, text: String = chapterText, previousText: String? = null,
        splitComponents: Boolean = false, sourceComponents: List<AbstractContentComponent<*>>? = null) {
        val components = sourceComponents ?: (if (splitComponents) text.split('\n') else listOf(text)).map(::textComponent)
        state.bookId = "book"
        state.readingChapterId = "chapter"
        state.readingProgress = progress
        state.isRestoringProgress = true
        if (previousText != null) {
            val previous = SimpleTextComponent(SimpleTextComponentData(previousText), mockk(relaxed = true), activity.get())
            state.contentList[0] = "previous" to Ok(ChapterContentUiState("previous", "Previous", listOf(previous), null, "chapter"))
        }
        state.contentList[1] = "chapter" to Ok(ChapterContentUiState("chapter", "Chapter", components,
            if (previousText == null) null else "previous", null))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    scope = rememberCoroutineScope()
                    CompositionLocalProvider(LocalReaderBookmarks provides bookmarks, LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme),
                        LocalReaderSpeechFollow provides ReaderSpeechFollow(speech, following, { following = false }, active),
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings)) {
                        Box(Modifier.width(320.dp).height(320.dp)) {
                            ScrollContentComponent(Modifier.layout { measurable, constraints ->
                                componentMeasurements++
                                val child = measurable.measure(constraints)
                                layout(child.width, child.height) { child.placeRelative(0, 0) }
                            }, state, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) }, PaddingValues(0.dp), {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
