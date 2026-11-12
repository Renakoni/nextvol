package indi.renakoni.nextvol.reader

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.*
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.MutableFlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderPage
import indi.renakoni.nextvol.ui.book.reader.content.flip.paginateReaderComponents
import indi.renakoni.nextvol.ui.book.reader.content.scroll.MutableScrollContentUiSate
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSpacingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var database: NextVolDatabase
    private lateinit var settings: SettingState

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        settings = SettingState(UserDataRepository(database.userDataDao()), scope)
    }

    @After fun close() { scope.cancel(); database.close() }

    @Test fun imageBoundariesStayIndependentWhileAdjacentTextSharesAPage() = runBlocking {
        val repository = UserDataRepository(database.userDataDao())
        fun text(value: String) = SimpleTextComponent(SimpleTextComponentData(value), repository, context)
        val image = ImageComponent(ImageComponentData(Uri.parse("file:///fixture-image.png")))
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
        val layout = ReaderLayoutSettings.from(settings)
        val pages = paginateReaderComponents(listOf(text("A"), text("B"), image, text("C")), 400, 300,
            ReaderTextLayoutInput(layout, layout.textStyle(FontFamily.Default, LocaleList("en-US")), measurer, 12))
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1), (pages[0] as ReaderPage).ranges.map { it.componentIndex })
        assertSame(image.data, pages[1].data)
        assertEquals(3, (pages[2] as ReaderPage).anchor.componentIndex)
    }

    @Test fun renderedTextUsesTheSameStyleLineBreaksAndHeightAsPagination() {
        val density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val style = ReaderLayoutSettings.from(settings).textStyle(FontFamily.Default, LocaleList("en-US"))
        val width = with(density) { 220.dp.roundToPx() }
        val fragments = layoutReaderText(listOf(ReaderTextSource(0,
            "A paragraph long enough to wrap across several lines in the reader. ".repeat(3) + "\nShort paragraph.")),
            width, 300, 12, style, measurer).first()
        compose.setContent {
            MaterialTheme(typography = AppTypography) {
                Box(Modifier.width(220.dp)) { ReaderTextFragments(fragments, style, Color.Black, Modifier.fillMaxWidth()) }
            }
        }
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertEquals(fragments.size, nodes.fetchSemanticsNodes().size)
        fragments.forEachIndexed { index, fragment ->
            val rendered = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(rendered) }
            val result = rendered.single()
            assertEquals(fragment.text, result.layoutInput.text.text)
            assertEquals(fragment.height, result.size.height)
            val expected = measurer.measure(fragment.text, style, constraints = Constraints(maxWidth = width))
            assertEquals(expected.lineCount, result.lineCount)
            for (line in 0 until result.lineCount) assertEquals(expected.getLineStart(line), result.getLineStart(line))
        }
    }

    @Test fun scrollAppliesParagraphSpacingOnceAcrossTextComponentsWithoutChangingLineHeight() {
        runBlocking { settings.isUsingContinuousScrollingUserData.set(false) }
        compose.waitUntil(5_000) { !settings.isUsingContinuousScrolling }
        val repository = UserDataRepository(database.userDataDao())
        fun text(value: String) = SimpleTextComponent(SimpleTextComponentData(value), repository, context)
        val first = "The first paragraph wraps across several lines in the reading area. ".repeat(3)
        val second = "Second paragraph."
        val third = "Third paragraph."
        val chapter = ChapterContentUiState("spacing", "Spacing", listOf(text("$first\n$second"), text(""), text(third)), null, null)
        val scroll = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
            readingChapterId = chapter.id
            contentList[1] = chapter.id to Ok(chapter)
        }
        compose.setContent {
            val colors = lightColorScheme()
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                CompositionLocalProvider(
                    LocalAppTheme provides AppTheme(false, colors),
                    LocalReaderTextLayout provides rememberReaderTextLayout(settings),
                ) {
                    Box(Modifier.width(320.dp).height(500.dp)) {
                        ScrollContentComponent(Modifier, scroll, settings,
                            UserDataReaderFontFamilySettings(settings.fontFamilyUriUserData), PaddingValues(0.dp), {}, {}, {})
                    }
                }
            }
        }
        fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val initialHeight = bounds(first).height
        runBlocking { settings.paragraphSpacingUserData.set(8f) }
        compose.waitUntil(5_000) { settings.paragraphSpacing == 8f }
        val expectedGap = with(Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)) {
            8.sp.roundToPx().toFloat()
        }
        assertEquals(initialHeight, bounds(first).height, 1f)
        assertEquals(expectedGap, bounds(second).top - bounds(first).bottom, 1f)
        assertEquals(expectedGap, bounds(third).top - bounds(second).bottom, 1f)
        runBlocking { settings.fontLineHeightUserData.set(12f) }
        compose.waitUntil(5_000) { settings.fontLineHeight == 12f }
        assertTrue(bounds(first).height > initialHeight)
        assertEquals(expectedGap, bounds(second).top - bounds(first).bottom, 1f)
        assertEquals(expectedGap, bounds(third).top - bounds(second).bottom, 1f)
    }

    @Test fun realPagerUsesTheRoundedAsymmetricBodyWidthAtFractionalDensity() {
        val fixture = ReflowFixture(PaddingValues(start = 7.3.dp, end = 12.7.dp, top = 9.4.dp, bottom = 15.2.dp), Density(1.3f))
        fun assertBodyWidth() {
            compose.waitForIdle()
            // The window can constrain the requested 320dp host at this test density.
            assertTrue(fixture.measuredHostSize.width > 0)
            val expectedWidth = with(Density(1.3f)) {
                fixture.measuredHostSize.width - 7.3.dp.roundToPx() - 12.7.dp.roundToPx()
            }
            val layouts = visibleTextLayouts()
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertEquals(expectedWidth, it.layoutInput.constraints.maxWidth) }
        }
        assertBodyWidth()
        val previousPager = fixture.flip.pagerState
        compose.runOnIdle { fixture.width = 240.dp }
        compose.waitUntil(15_000) { fixture.flip.pagerState !== previousPager && fixture.flip.pagerState.pageCount > 0 }
        assertBodyWidth()
    }

    @Test fun zeroHeightWaitsWithoutPublishingAnEmptyPagerAndRestoresTheAnchor() {
        val fixture = ReflowFixture()
        fixture.moveToMiddle()
        val anchor = fixture.visibleAnchor()
        val pager = fixture.flip.pagerState
        val updates = fixture.pagerUpdates
        compose.runOnIdle { fixture.height = 0.dp }
        compose.waitForIdle()
        assertSame(pager, fixture.flip.pagerState)
        assertEquals(updates, fixture.pagerUpdates)
        fixture.reflow("restore measured body", anchor) {
            compose.runOnIdle { fixture.width = 280.dp; fixture.height = 420.dp }
        }
    }

    private inner class ReflowFixture(
        private val padding: PaddingValues = PaddingValues(0.dp),
        private val testDensity: Density = Density(context.resources.displayMetrics.density),
    ) {
        val text = (1..100).joinToString("\n") { paragraph ->
            (1..5).joinToString(" ") { sentence -> "Sentence $paragraph.$sentence is part of the reading position." }
        }
        private val component = SimpleTextComponent(SimpleTextComponentData(text), UserDataRepository(database.userDataDao()), context)
        val chapter = ChapterContentUiState("sequential-reflow", "Reflow", listOf(component), null, null)
        val flip = MutableFlipPageContentUiState({}, {}, {}, { updatePager(it) }).apply {
            readingChapterId = chapter.id
            readingChapterContent = Ok(chapter)
        }
        var width by mutableStateOf(320.dp)
        var height by mutableStateOf(420.dp)
        var measuredHostSize = IntSize.Zero
            private set
        var pagerUpdates = 0

        init {
            compose.setContent {
                val colors = lightColorScheme()
                CompositionLocalProvider(LocalDensity provides testDensity) {
                  MaterialTheme(colorScheme = colors, typography = AppTypography) {
                    CompositionLocalProvider(
                        LocalAppTheme provides AppTheme(false, colors),
                        LocalReaderTextLayout provides rememberReaderTextLayout(settings),
                    ) {
                        Box(Modifier.width(width).height(height).onSizeChanged { measuredHostSize = it }) {
                            FlipPageContentComponent(Modifier, flip, settings, padding, {}, {}, {})
                        }
                    }
                  }
                }
            }
            compose.waitUntil(15_000) { flip.pagerState.pageCount > 5 }
        }

        private fun updatePager(pager: androidx.compose.foundation.pager.PagerState) {
            pagerUpdates++
            flip.pagerState = pager
        }

        fun moveToMiddle() {
            compose.runOnIdle { scope.launch { flip.pagerState.scrollToPage(flip.pagerState.pageCount / 2) } }
            compose.waitForIdle()
        }

        fun visibleAnchor() = text.indexOf(visibleTextLayouts().first().layoutInput.text.text)

        fun reflow(change: String, anchor: Int, apply: () -> Unit) {
            val previous = flip.pagerState
            apply()
            compose.waitUntil(15_000) { flip.pagerState !== previous && flip.pagerState.pageCount > 0 }
            compose.waitForIdle()
            val ranges = visibleTextLayouts().map { result ->
                val displayed = result.layoutInput.text.text
                val start = text.indexOf(displayed)
                start until start + displayed.length
            }
            assertTrue("$change: anchor=$anchor, visible=$ranges, page=${flip.pagerState.currentPage}/${flip.pagerState.pageCount}",
                ranges.any { anchor in it })
        }
    }

    private fun visibleTextLayouts(): List<TextLayoutResult> {
        val layouts = mutableListOf<TextLayoutResult>()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            if (nodes[index].isDisplayed()) {
                nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            }
        }
        return layouts
    }
}
