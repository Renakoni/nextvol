package indi.renakoni.nextvol.reader

import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.LocalReaderBookId
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderVolumeKeysEnabled
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.ui.book.reader.content.flip.*
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderSpreadInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderSpreadTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: NextVolDatabase
    private lateinit var baseSettings: SettingState
    private lateinit var image: File

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        baseSettings = SettingState(UserDataRepository(database.userDataDao()), scope)
        image = File.createTempFile("spread-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(60, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        for (x in 0 until bitmap.width) for (y in 0 until 20) {
            bitmap.setPixel(x, y, android.graphics.Color.RED)
            bitmap.setPixel(x, bitmap.height - 1 - y, android.graphics.Color.GREEN)
        }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @After fun close() { scope.cancel(); database.close(); image.delete() }

    @SdkSuppress(minSdkVersion = 26) // captureToImage uses PixelCopy.request(Window, ...).
    @Test fun portraitImageFitsWithoutCroppingEitherEnd() {
        Fixture()
        val node = compose.onNodeWithTag("reader-leaf-1", useUnmergedTree = true)
        compose.waitUntil(10_000) {
            val pixels = node.captureToImage().asAndroidBitmap()
            pixels.getPixel(pixels.width / 2, pixels.height / 2) == android.graphics.Color.BLUE
        }
        val pixels = node.captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.RED, pixels.getPixel(pixels.width / 2, 10))
        assertEquals(android.graphics.Color.GREEN, pixels.getPixel(pixels.width / 2, pixels.height - 11))
        assertNotEquals(android.graphics.Color.BLUE, pixels.getPixel(10, pixels.height / 2))
    }

    @Test fun imageFailureAndRetryKeepTheSameRealLeafSequence() {
        assertTrue(image.delete())
        val fixture = Fixture()
        val retry = hasText(context.getString(R.string.action_retry)) and
            hasAnyAncestor(hasTestTag("reader-leaf-1"))
        compose.waitUntil(10_000) { compose.onAllNodes(retry).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(retry).performClick()
        compose.waitForIdle()
        assertEquals(5, fixture.flip.realLeafCount)
        assertEquals(0..1, fixture.flip.visibleLeafRange)
        fixture.turn(true)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
    }

    @Test fun fiveLeavesNavigateAsThreeDisjointScreens() {
        val fixture = Fixture()
        assertEquals(5, fixture.flip.realLeafCount)
        assertEquals(3, fixture.flip.pagerState.pageCount)
        assertEquals(0..1, fixture.flip.visibleLeafRange)
        fixture.turn(true)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        fixture.turn(true)
        assertEquals(4..4, fixture.flip.visibleLeafRange)
        compose.onNodeWithTag("reader-leaf-5", useUnmergedTree = true).assertDoesNotExist()
        fixture.turn(false)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
    }

    @Test fun rtlChangesPhysicalSidesButNotLeafOrderOrOddEnding() {
        val fixture = Fixture(LayoutDirection.Rtl)
        val first = compose.onNodeWithTag("reader-leaf-0", useUnmergedTree = true).fetchSemanticsNode()
        val second = compose.onNodeWithTag("reader-leaf-1", useUnmergedTree = true).fetchSemanticsNode()
        val firstLeafLeft = first.boundsInRoot.left
        assertTrue(first.boundsInRoot.left > second.boundsInRoot.left)
        assertEquals(488f, first.boundsInRoot.width, 1f)
        assertEquals(first.boundsInRoot.width, second.boundsInRoot.width, 0f)
        assertEquals(0f, first.config[SemanticsProperties.TraversalIndex], 0f)
        assertEquals(1f, second.config[SemanticsProperties.TraversalIndex], 0f)
        fixture.turn(true); fixture.turn(true)
        val last = compose.onNodeWithTag("reader-leaf-4", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(firstLeafLeft, last.boundsInRoot.left, 0f)
        assertEquals(4..4, fixture.flip.visibleLeafRange)
    }

    @Test fun oddChaptersCommitTheirOwnMappingAndReverseToPreviousLastScreen() {
        val fixture = Fixture()
        fixture.turn(true); fixture.turn(true); fixture.turn(true)
        compose.waitUntil(10_000) { fixture.flip.readingChapterId == "two" }
        assertEquals(0..1, fixture.flip.visibleLeafRange)
        fixture.turn(false)
        compose.waitUntil(10_000) { fixture.flip.readingChapterId == "one" }
        assertEquals(4..4, fixture.flip.visibleLeafRange)
        assertEquals(2, fixture.commits)
    }

    @Test fun resizeRetainsTheSourceLeafInsteadOfTheOldScreenIndex() {
        val fixture = Fixture()
        fixture.turn(true)
        val previous = fixture.flip.pagerState
        compose.runOnIdle { fixture.width = 600.dp }
        compose.waitUntil(10_000) { fixture.flip.pagerState !== previous && fixture.flip.pagerState.pageCount == 5 }
        compose.waitForIdle()
        assertEquals(2..2, fixture.flip.visibleLeafRange)
        val narrow = fixture.flip.pagerState
        compose.runOnIdle { fixture.width = 1000.dp }
        compose.waitUntil(10_000) { fixture.flip.pagerState !== narrow && fixture.flip.pagerState.pageCount == 3 }
        compose.waitForIdle()
        assertEquals(2..3, fixture.flip.visibleLeafRange)
    }

    @Test fun animatedPageTurnsMoveWholeSpreads() {
        val fixture = Fixture(animated = true)
        fixture.turn(true)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        fixture.turn(false)
        assertEquals(0..1, fixture.flip.visibleLeafRange)
    }

    @Test fun reducedMotionDoesNotChangeTheStoredAnimationOrSpreadStep() {
        val fixture = Fixture(animated = true, reduced = true)
        fixture.turn(true)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        assertEquals("scroll", fixture.settings.flipAnime)
        assertFalse(fixture.settings.animatePageTurns)
    }

    @Test fun unverifiedComponentFallsBackForTheWholeChapter() {
        val fixture = Fixture(unsupported = true)
        assertEquals(5, fixture.flip.pagerState.pageCount)
        assertEquals(0..0, fixture.flip.visibleLeafRange)
        fixture.turn(true)
        assertEquals(1..1, fixture.flip.visibleLeafRange)
    }

    @Test fun doublePreferenceSurvivesUnsupportedContentAndInactiveRenderer() {
        runBlocking { baseSettings.pageLayoutUserData.set("double") }
        val fixture = Fixture()
        compose.waitUntil(15_000) { fixture.layoutResult.value?.geometry?.columns == 2 }
        val original = fixture.flip.readingChapterContent!!.get()!!
        val special = object : AbstractContentComponent<ImageComponentData>(ImageComponentData(Uri.EMPTY)) {
            override val id = ImageComponentData.id
            @Composable override fun Content(modifier: Modifier) { Text("SPECIAL", modifier) }
        }
        val unsupported = ChapterContentUiState(original.id, original.title, original.content + special,
            original.prevChapter, original.nextChapter)
        compose.runOnIdle { fixture.flip.readingChapterContent = Ok(unsupported) }
        compose.waitUntil(15_000) { fixture.layoutResult.value?.reason == ReaderLayoutReason.UnsupportedContent }
        assertEquals(1, fixture.layoutResult.value?.geometry?.columns)
        assertEquals(6, fixture.flip.pagerState.pageCount)
        assertEquals("double", fixture.settings.pageLayout)
        compose.runOnIdle { fixture.flip.readingChapterContent = Ok(original) }
        compose.waitUntil(15_000) { fixture.layoutResult.value?.geometry?.columns == 2 }
        assertNull(fixture.layoutResult.value?.reason)
        assertEquals(3, fixture.flip.pagerState.pageCount)
        assertEquals("double", fixture.settings.pageLayout)
        val activeLayout = fixture.layoutResult.value
        compose.runOnIdle { fixture.active = false; fixture.width = 600.dp }
        compose.waitForIdle()
        assertEquals(activeLayout, fixture.layoutResult.value)
    }

    @Test fun swipeWithoutAnimationTurnsExactlyOneSpread() = assertSwipe(false)
    @Test fun animatedSwipeTurnsExactlyOneSpread() = assertSwipe(true)

    private fun assertSwipe(animated: Boolean) {
        val fixture = Fixture(animated = animated)
        compose.onNodeWithTag("spread-host").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        compose.onNodeWithTag("spread-host").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0..1, fixture.flip.visibleLeafRange)
    }

    @Test fun accessiblePageActionsUseTheSameSpreadStep() = assertAccessible(LayoutDirection.Ltr)
    @Test fun accessibleRtlPageActionsReversePhysicalDirectionOnly() = assertAccessible(LayoutDirection.Rtl)

    private fun assertAccessible(direction: LayoutDirection) {
        val fixture = Fixture(direction)
        val action = if (direction == LayoutDirection.Ltr) SemanticsActions.PageRight else SemanticsActions.PageLeft
        compose.onNode(SemanticsMatcher.keyIsDefined(action)).performSemanticsAction(action) { assertTrue(it()) }
        compose.waitForIdle()
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(if (direction == LayoutDirection.Ltr) 1000f else -1000f, 0f))
        }
        compose.waitForIdle()
        assertEquals(4..4, fixture.flip.visibleLeafRange)
    }

    @Test fun ordinaryBookmarkUsesFirstRealLeafAndNeverTheEmptySlot() {
        val fixture = Fixture()
        assertEquals(0, compose.runOnIdle { fixture.bookmarks.capture!!()!!.anchor.componentIndex })
        fixture.turn(true)
        assertEquals(2, compose.runOnIdle { fixture.bookmarks.capture!!()!!.anchor.componentIndex })
        fixture.turn(true)
        assertEquals(4, compose.runOnIdle { fixture.bookmarks.capture!!()!!.anchor.componentIndex })
    }

    @Test fun outgoingRendererReleasesCaptureAndIgnoresTapSwipeAndReflow() {
        val fixture = Fixture(animated = true)
        val oldCapture = compose.runOnIdle { fixture.bookmarks.capture!! }
        val pager = fixture.flip.pagerState
        compose.runOnIdle { fixture.active = false }
        compose.waitForIdle()
        assertNull(fixture.bookmarks.capture)
        assertNull(compose.runOnIdle { oldCapture() })
        fixture.turn(true)
        compose.onNodeWithTag("spread-host").performTouchInput { swipeLeft() }
        compose.runOnIdle { fixture.width = 600.dp }
        compose.waitForIdle()
        assertSame(pager, fixture.flip.pagerState)
        assertEquals(0, pager.currentPage)
        compose.onNodeWithTag("reader-leaf-0").assertDoesNotExist()
    }

    @Test fun volumeTapAdvancesOneSpreadInRtlAndGoesBack() {
        val fixture = Fixture(direction = LayoutDirection.Rtl, volume = true)
        fun tap(code: Int) {
            assertTrue(compose.runOnIdle { compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) })
            assertTrue(compose.runOnIdle { compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) })
            compose.waitForIdle()
        }
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        tap(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(0..1, fixture.flip.visibleLeafRange)
    }

    @Test fun heldVolumeKeyRepeatsSpreadsAndStopsWhenRendererExits() {
        val fixture = Fixture(volume = true)
        compose.mainClock.autoAdvance = false
        assertTrue(compose.runOnIdle { compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) })
        compose.mainClock.advanceTimeBy(64)
        assertEquals(2..3, fixture.flip.visibleLeafRange)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(4..4, fixture.flip.visibleLeafRange)
        compose.runOnIdle { fixture.active = false }
        compose.mainClock.advanceTimeBy(800)
        assertEquals(4..4, fixture.flip.visibleLeafRange)
        assertEquals(0, fixture.commits)
        compose.mainClock.autoAdvance = true
    }

    private inner class Fixture(
        val direction: LayoutDirection = LayoutDirection.Ltr,
        animated: Boolean = false,
        reduced: Boolean = false,
        private val unsupported: Boolean = false,
        volume: Boolean = false,
    ) {
        val settings = object : ReaderSettings by baseSettings {
            override val isUsingFlipPage = true
            override val isUsingClickFlipPage = true
            override val fastChapterChange = true
            override val isUsingVolumeKeyFlip = volume
            override val volumeKeyContinuousFlipInterval = .2f
            override val flipAnime = if (animated) "scroll" else "none"
            override val reduceMotion = reduced
        }
        private val repository = UserDataRepository(database.userDataDao())
        private fun text(value: String) = SimpleTextComponent(SimpleTextComponentData(value), repository, context)
        private fun chapter(id: String, prev: String?, next: String?) = ChapterContentUiState(id, id, listOf(
            if (unsupported) object : AbstractContentComponent<ImageComponentData>(ImageComponentData(Uri.fromFile(image))) {
                override val id = ImageComponentData.id
                @Composable override fun Content(modifier: Modifier) { Text("SPECIAL", modifier) }
            } else text("$id first"),
            ImageComponent(ImageComponentData(Uri.fromFile(image))),
            text("$id middle"), ImageComponent(ImageComponentData(Uri.fromFile(image))), text("$id last"),
        ), prev, next)
        private val one = chapter("one", null, "two")
        private val two = chapter("two", "one", null)
        var width by mutableStateOf(1000.dp)
        var active by mutableStateOf(true)
        val bookmarks = ReaderBookmarkSession()
        val layoutResult = mutableStateOf<ReaderLayoutResult?>(null)
        var commits = 0
        val flip = MutableFlipPageContentUiState(
            loadNextChapter = { stage(two, ChapterEntry.Start) },
            loadPrevChapter = { stage(one, ChapterEntry.End) },
            changeChapter = {},
            updatePageState = { update(it) },
            commitPendingChapter = { pending, pager -> commit(pending, pager) },
            cancelPendingChapter = { cancel() },
        ).apply { bookId = "spread-book"; readingChapterId = one.id; readingChapterContent = Ok(one) }

        init {
            compose.setContent {
                val colors = lightColorScheme()
                val navController = rememberNavController()
                CompositionLocalProvider(
                    LocalReaderRendererActive provides active, LocalReaderBookmarks provides bookmarks,
                    LocalReaderLayoutResult provides layoutResult,
                    LocalReaderVolumeKeysEnabled provides volume,
                    LocalDensity provides Density(1f), LocalLayoutDirection provides direction,
                    LocalReaderBookId provides "spread-book", LocalNavController provides navController,
                ) {
                    MaterialTheme(colorScheme = colors, typography = AppTypography) {
                        CompositionLocalProvider(
                            LocalAppTheme provides AppTheme(false, colors),
                            LocalReaderTextLayout provides rememberReaderTextLayout(settings),
                        ) {
                            Box(Modifier.width(width).height(600.dp).testTag("spread-host")) {
                                FlipPageContentComponent(Modifier, flip, settings, PaddingValues(0.dp), {}, {}, {})
                            }
                        }
                    }
                }
            }
            compose.waitUntil(15_000) { flip.pagerState.pageCount > 0 }
            compose.waitForIdle()
        }

        private fun update(pager: PagerState) { flip.pagerState = pager }
        private fun stage(chapter: ChapterContentUiState, entry: ChapterEntry) {
            flip.pendingChapter = FlipChapterTransition(chapter.id, entry, Ok(chapter))
        }
        private fun cancel() { flip.pendingChapter = null }
        private fun commit(pending: FlipChapterTransition, pager: PagerState): Boolean {
            if (flip.pendingChapter !== pending) return false
            flip.readingChapterId = pending.chapterId
            flip.readingChapterContent = pending.result
            flip.pagerState = pager
            flip.pendingChapter = null
            commits++
            return true
        }
        fun turn(forward: Boolean) {
            compose.onNodeWithTag("spread-host").performTouchInput {
                val physicalRight = forward == (direction == LayoutDirection.Ltr)
                click(Offset(width * if (physicalRight) 0.96f else 0.04f, 4f))
            }
            compose.waitForIdle()
        }
    }
}
