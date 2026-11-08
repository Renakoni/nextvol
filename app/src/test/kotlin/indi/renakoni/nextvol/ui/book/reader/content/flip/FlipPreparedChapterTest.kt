package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import indi.renakoni.nextvol.ui.book.reader.ReaderFlipAnimation
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose pagination and controller commit; only source/storage timing is controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class FlipPreparedChapterTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val env = ModeTestEnvironment()
    private val saved = mutableListOf<Pair<String, Float>>()
    private var immediateProgress = false
    private val mode by lazy {
        val scope = if (immediateProgress) CoroutineScope(env.scope.coroutineContext + UnconfinedTestDispatcher(env.scheduler)) else env.scope
        FlipReaderController(env.loader, env.records, scope, { id, p -> saved += id to p }, env.dispatcher)
    }
    private val content = mutableMapOf<String, List<AbstractContentComponent<*>>>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var viewportHeight by mutableStateOf(320.dp)
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { flipAnime } returns ReaderFlipAnimation.ScrollWithoutShadow
        every { fastChapterChange } returns true
        every { isUsingFlipPage } returns true
        every { isUsingClickFlipPage } returns true
        every { fontFamilyUri } returns Uri.EMPTY
    }

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { gates.forEach { it.complete(Unit) }; activity.pause().stop().destroy(); env.close() }

    @Test fun oldPageStaysVisibleUntilNextPaginationFinishesThenFirstPageIsDisplayed() {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().also { gates += it }
        content["4"] = listOf(Split { _, _ -> started.complete(Unit); gate.await(); List(10) { Page("BODY_4_${it + 1}") } })
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        compose.onNodeWithText("BODY_3").assertIsDisplayed()
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        await { started.isCompleted }
        compose.onNodeWithText("BODY_3").assertIsDisplayed()
        assertEquals("3", mode.uiState.readingChapterId)
        assertEquals("3", env.records.data.lastReadChapterId)
        gate.complete(Unit)
        await { mode.uiState.readingChapterId == "4" && mode.uiState.pagerState.pageCount == 10 }
        compose.onNodeWithText("BODY_4_1").assertIsDisplayed()
        assertEquals(0, mode.uiState.pagerState.currentPage)
        assertEquals(0.1f, mode.uiState.readingProgress)
        assertEquals("4", env.records.data.lastReadChapterId)
        assertTrue(saved.any { it == "4" to 0.1f })
        assertFalse(saved.any { it.first == "4" && it.second != 0.1f })
    }

    @Test fun previousPaginationDisplaysItsLastPageInsteadOfRestoringHistory() {
        content["2"] = List(3) { Page("BODY_2_${it + 1}") }
        mount()
        compose.onRoot().performTouchInput { swipeRight() }
        env.runCurrent()
        env.emit("2", Ok(env.chapter("2", "1", "3")))
        await { mode.uiState.readingChapterId == "2" }
        compose.onNodeWithText("BODY_2_3").assertIsDisplayed()
        assertEquals(2, mode.uiState.pagerState.currentPage)
        assertEquals(1f, mode.uiState.readingProgress)
    }

    @Test fun refreshingAfterPreviousChapterCommitKeepsTheLastPageAndProgressObservation() {
        immediateProgress = true
        content["2"] = List(3) { Page("CACHED_2_${it + 1}") }
        mount()
        compose.onRoot().performTouchInput { swipeRight() }
        env.runCurrent()
        env.emit("2", Ok(env.chapter("2", "1", "3")))
        await { mode.uiState.readingChapterId == "2" }
        compose.onNodeWithText("CACHED_2_3").assertIsDisplayed()
        val originalPager = mode.uiState.pagerState
        content["2_fresh"] = List(3) { Page("FRESH_2_${it + 1}") }
        env.emit("2", Ok(env.chapter("2", "1", "3").copy(content = env.chapter("2_fresh").content)))
        await { mode.uiState.pagerState !== originalPager && mode.uiState.pagerState.pageCount == 3 }
        compose.onNodeWithText("FRESH_2_3").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        env.runCurrent()
        compose.onNodeWithText("FRESH_2_2").assertIsDisplayed()
        assertEquals(2f / 3, mode.uiState.readingProgress)
    }

    @Test fun failedNextKeepsBodyAndRetryActionLoadsThenCommitsTheSameTarget() {
        content["4"] = listOf(Page("BODY_4"))
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Err(WebRequestError("Offline", "Reconnect")))
        compose.onNodeWithText("BODY_3").assertIsDisplayed()
        compose.onNodeWithText("Offline: Reconnect").assertIsDisplayed()
        assertEquals("3", env.records.data.lastReadChapterId)
        compose.onNodeWithText(activity.get().getString(R.string.action_retry)).performClick()
        env.runCurrent()
        assertEquals(2, env.chapters.requests.count { it.chapterId == "4" })
        env.emit("4", Ok(env.chapter("4")))
        await { mode.uiState.readingChapterId == "4" }
        compose.onNodeWithText("BODY_4").assertIsDisplayed()
        assertEquals("4", env.records.data.lastReadChapterId)
    }

    @Test fun resizedViewportInvalidatesLatePreparedPages() {
        val started = CompletableDeferred<Unit>()
        val oldGate = CompletableDeferred<Unit>().also { gates += it }
        val calls = AtomicInteger()
        content["4"] = listOf(Split { _, _ ->
            if (calls.incrementAndGet() == 1) {
                started.complete(Unit)
                withContext(NonCancellable) { oldGate.await() }
                listOf(Page("OLD_LAYOUT"))
            } else listOf(Page("NEW_LAYOUT"))
        })
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        await { started.isCompleted }
        compose.runOnIdle { viewportHeight = 440.dp }
        await { mode.uiState.readingChapterId == "4" }
        oldGate.complete(Unit)
        compose.waitForIdle()
        env.runCurrent()
        compose.onNodeWithText("NEW_LAYOUT").assertIsDisplayed()
        compose.onNodeWithText("OLD_LAYOUT").assertDoesNotExist()
        assertEquals("4", env.records.data.lastReadChapterId)
    }

    @Test fun emptyPreparedChapterShowsFailureAndKeepsCurrentBody() {
        content["4"] = emptyList()
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        await { mode.uiState.pendingChapter?.result?.isErr == true }
        compose.onNodeWithText("BODY_3").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.reader_empty_chapter), substring = true).assertIsDisplayed()
        assertEquals("3", env.records.data.lastReadChapterId)
    }

    @Test fun paginationFailureOffersRetryWithoutCommittingTheTarget() {
        content["4"] = listOf(Split { _, _ -> error("Fixture pagination failure") })
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        await { mode.uiState.pendingChapter?.result?.isErr == true }
        compose.onNodeWithText("BODY_3").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.reader_pagination_failed), substring = true).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.action_retry)).assertIsDisplayed()
        assertEquals("3", env.records.data.lastReadChapterId)
    }

    @Test fun draggingBackIntoCurrentChapterCancelsThePendingBoundaryRequest() {
        mount(currentPages = 2)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        env.runCurrent()
        assertEquals(1, mode.uiState.pagerState.currentPage)
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        assertNotNull(mode.uiState.pendingChapter)
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        env.runCurrent()
        assertNull(mode.uiState.pendingChapter)
        assertEquals(0, mode.uiState.pagerState.currentPage)
        assertEquals("3", mode.uiState.readingChapterId)
        assertEquals("3", env.records.data.lastReadChapterId)
    }

    @Test fun returningToTheOriginalSizeAfterCrossingChaptersReflowsTheVisiblePages() {
        val heights = java.util.Collections.synchronizedList(mutableListOf<Int>())
        content["4"] = listOf(Split { height, _ ->
            heights += height
            listOf(Page("HEIGHT_$height"))
        })
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        await { mode.uiState.readingChapterId == "4" }
        val originalHeight = heights.first()
        compose.runOnIdle { viewportHeight = 440.dp }
        await { heights.size >= 2 && mode.uiState.pagerState.pageCount == 1 }
        compose.onNodeWithText("HEIGHT_${heights.last()}").assertIsDisplayed()
        compose.runOnIdle { viewportHeight = 320.dp }
        compose.waitForIdle()
        env.runCurrent()
        await { heights.size >= 3 && mode.uiState.pagerState.pageCount == 1 }
        compose.onNodeWithText("HEIGHT_$originalHeight").assertIsDisplayed()
    }

    @Test fun inwardDragCancelsLoadingBeforeTheFingerIsReleased() {
        content["4"] = listOf(Page("BODY_4"))
        mount(currentPages = 2)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        env.runCurrent()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        assertNotNull(mode.uiState.pendingChapter)
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(width * 0.25f, 0f), delayMillis = 300)
        }
        compose.waitForIdle()
        env.runCurrent()
        assertNull(mode.uiState.pendingChapter)
        env.emit("4", Ok(env.chapter("4")))
        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()
        env.runCurrent()
        assertEquals("3", mode.uiState.readingChapterId)
        assertEquals("3", env.records.data.lastReadChapterId)
    }

    @Test fun oppositeBoundarySwipeReplacesAFailedNextRequestInASinglePageChapter() {
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        env.runCurrent()
        env.emit("4", Err(WebRequestError("Offline", "Reconnect")))
        compose.onNodeWithText("Offline: Reconnect").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeRight() }
        env.runCurrent()
        assertEquals("2", mode.uiState.pendingChapter?.chapterId)
        assertEquals(ChapterEntry.End, mode.uiState.pendingChapter?.entry)
        assertEquals("3", mode.uiState.readingChapterId)
        compose.onNodeWithText("Offline: Reconnect").assertDoesNotExist()
    }

    private fun mount(currentPages: Int = 1) {
        content["3"] = List(currentPages) { Page(if (currentPages == 1) "BODY_3" else "BODY_3_${it + 1}") }
        every { env.renderer.getContentDataFromJson(any()) } answers {
            ContentData(content.getValue(firstArg<JsonObject>().getValue("token").jsonPrimitive.content))
        }
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("2" to 0.2f, "4" to 0.8f))
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("3")
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", "2", "4")))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    val snackbar = remember { SnackbarHostState() }
                    CompositionLocalProvider(LocalSnackbarHost provides snackbar) {
                        Box(Modifier.fillMaxWidth().height(viewportHeight)) {
                            FlipPageContentComponent(Modifier, mode.uiState, settings, PaddingValues(0.dp), {}, mode::loadPrevChapter, mode::loadNextChapter)
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        await { mode.uiState.pagerState.pageCount == currentPages }
        compose.onNodeWithText(if (currentPages == 1) "BODY_3" else "BODY_3_1").assertIsDisplayed()
        saved.clear()
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            env.runCurrent()
            condition()
        }
        compose.waitForIdle()
        env.runCurrent()
    }

    private class Page(private val body: String) : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) { Text(body, modifier) }
    }

    private class Split(private val paginate: suspend (Int, Int) -> List<Page>) :
        AbstractDivisibleContentComponent<Page, AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) = Unit
        override suspend fun split(height: Int, width: Int) = paginate(height, width)
    }
}
