package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
class ScrollChapterDrawTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val env = ModeTestEnvironment()
    private var viewportHeight = 0
    private val draws = mutableListOf<Draw>()
    private data class Draw(val body: String, val key: Any?, val height: Int?)
    private val state: MutableScrollContentUiSate = MutableScrollContentUiSate({}, {}, {},
        { viewportHeight = it.height }, {},
        onProgressRestoring = { state.isRestoringProgress = true },
        onProgressRestored = { state.isRestoringProgress = false },
    ).apply { bookId = "book"; readingChapterId = "current" }
    private val continuous = object : ContinuousScrollSettings {
        override fun getFlow() = flowOf(true)
        override suspend fun isEnabled() = true
    }
    private val window = ScrollChapterWindow(state, env.loader, env.records, continuous,
        env.scope, { viewportHeight }, env.dispatcher)
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { fontSize } returns 16f
        every { fontWeigh } returns 400f
        every { reduceMotion } returns true
        every { isUsingContinuousScrolling } returns true
    }

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().window.decorView.viewTreeObserver.addOnPreDrawListener {
            drawFrame()
            true
        }
    }

    @After fun close() { env.close(); activity.pause().stop().destroy() }

    @Test fun forwardPromotionInsideLookaheadKeepsTheChapterBodyAndHeight() {
        assertPromotion(forward = true)
    }

    @Test fun backwardPromotionInsideLookaheadKeepsTheChapterBodyAndHeight() {
        assertPromotion(forward = false)
    }

    private fun assertPromotion(forward: Boolean) {
        state.contentList[0] = "previous" to Ok(chapter("previous", 1200, next = "current"))
        state.contentList[1] = "current" to Ok(chapter("current", 2400, "previous", "next"))
        state.contentList[2] = "next" to Ok(chapter("next", 4800, prev = "current"))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme)) {
                        LookaheadScope {
                            Box(Modifier.size(320.dp)) {
                                ScrollContentComponent(Modifier, state, settings,
                                    mockk { every { getFlow() } returns flowOf(Uri.EMPTY) },
                                    PaddingValues(0.dp), {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.waitForIdle(); !state.isRestoringProgress }
        val list = state.lazyListState
        val target = if (forward) "next" else "previous"
        var height = 0
        val offset = 500
        val delta = if (forward) 24 else -24
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            runBlocking { list.scrollToItem(if (forward) 2 else 0, offset) }
            height = list.layoutInfo.visibleItemsInfo.single { it.key == target }.size
            draws.clear()
            // Run the real window observer after layout, while the parent composition still
            // captures the old prepared array. A subsequent scroll/draw must stay consistent.
            window.startContinuousObservation()
            env.runCurrent()
            assertEquals(target, state.readingChapterId)
            Snapshot.sendApplyNotifications()
            list.dispatchRawDelta(delta.toFloat())
            assertEquals(offset + delta, list.firstVisibleItemScrollOffset)
            drawFrame()
            assertTrue("Must inspect draws before parent recomposition", draws.isNotEmpty())
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertSame(list, state.lazyListState)
        assertEquals(target, state.readingChapterId)
        assertEquals(offset + delta, list.firstVisibleItemScrollOffset)
        assertTrue("Wrong chapter body or height in a draw: $draws",
            draws.all { it.body == target && it.key == target && it.height == height })
    }

    private fun drawFrame() {
        val view = activity.get().window.decorView
        if (view.width == 0 || view.height == 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    private fun chapter(id: String, height: Int, prev: String? = null, next: String? = null) =
        ChapterContentUiState(id, id, listOf(Body(id, height)), prev, next)

    private inner class Body(private val chapter: String, private val height: Int) :
        AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) {
            val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
            Box(Modifier.fillMaxWidth().height(height.dp).onGloballyPositioned { coordinates[0] = it }
                .drawWithContent {
                    drawContent()
                    val top = coordinates[0]?.positionInRoot()?.y ?: return@drawWithContent
                    if (top < viewportHeight && top + size.height > 0) {
                        Snapshot.withoutReadObservation {
                            val item = state.lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()
                            draws += Draw(chapter, item?.key, item?.size)
                        }
                    }
                })
        }
    }
}
