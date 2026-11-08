package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.content.componet.ErrorContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BuiltInContentRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After
    fun tearDown() { activity.pause().stop().destroy() }

    @Test
    fun textComponentForwardsModifierAndTracksReaderStyleAndThemeFallbacks() {
        val component = SimpleTextComponent(SimpleTextComponentData("Reader body"), mockk(relaxed = true), activity.get())
        every { component.fontFamilyUriUserData.getFlowWithDefault(Uri.EMPTY) } returns flowOf(Uri.EMPTY)
        var style by mutableStateOf(ReaderStyle(18f, 4f, 600f, Color.Unspecified, Color.Unspecified))
        var theme by mutableStateOf(AppTheme(false, lightColorScheme(onSurface = Color.Green)))
        setContent {
            MaterialTheme(typography = AppTypography) {
                CompositionLocalProvider(LocalReaderStyle provides style, LocalAppTheme provides theme) {
                    Box(Modifier.size(200.dp)) { component.Content(Modifier.testTag("body")) }
                }
            }
        }
        fun assertStyle(expectedColor: Color, expectedSize: Int, expectedLineHeight: Int, expectedWeight: Int) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val input = layouts.single().layoutInput
            assertEquals("Reader body", input.text.text)
            assertEquals(expectedColor, input.style.color)
            assertEquals(expectedSize.sp, input.style.fontSize)
            assertEquals(expectedLineHeight.sp, input.style.lineHeight)
            assertEquals(FontWeight(expectedWeight), input.style.fontWeight)
        }
        assertStyle(Color.Green, 18, 22, 600)
        compose.runOnIdle { theme = AppTheme(true, darkColorScheme(onSurface = Color.Blue)) }
        assertStyle(Color.Blue, 18, 22, 600)
        compose.runOnIdle { style = ReaderStyle(20f, 6f, 400f, Color.Magenta, Color.Red) }
        assertStyle(Color.Red, 20, 26, 400)
        compose.runOnIdle { theme = AppTheme(false, lightColorScheme(onSurface = Color.Green)) }
        assertStyle(Color.Magenta, 20, 26, 400)
    }

    @Test
    fun papersScopeBodyAndMenuColorsWithoutRecreatingContentOrChangingTheAppTheme() {
        val component = SimpleTextComponent(SimpleTextComponentData("Paper body"), mockk(relaxed = true), activity.get())
        every { component.fontFamilyUriUserData.getFlowWithDefault(Uri.EMPTY) } returns flowOf(Uri.EMPTY)
        var choice by mutableStateOf("default")
        var dark by mutableStateOf(false)
        val settings = object : ReaderSettings by mockk<ReaderSettings>(relaxed = true) {
            override val paperId get() = choice
        }
        var readerIdentity: Any? = null
        var readerDark = false
        var speechHighlight = Color.Unspecified
        val light = lightColorScheme(onSurface = Color.Green)
        val night = darkColorScheme(onSurface = Color.Cyan)
        val style = ReaderStyle(18f, 4f, 600f, Color.Magenta, Color.Red)
        setContent {
            val appColors = if (dark) night else light
            MaterialTheme(colorScheme = appColors, typography = AppTypography) {
                CompositionLocalProvider(LocalAppTheme provides AppTheme(dark, appColors), LocalReaderStyle provides style) {
                    Column {
                        Text("Library", color = MaterialTheme.colorScheme.onSurface)
                        ReaderPaperTheme(settings) {
                            val identity = remember { Any() }
                            val paperDark = LocalAppTheme.current.isDark
                            val highlight = LocalReaderSpeechHighlight.current
                            SideEffect { readerIdentity = identity; readerDark = paperDark; speechHighlight = highlight }
                            Column {
                                Text("Paper menu", color = MaterialTheme.colorScheme.onSurface)
                                component.Content(Modifier.testTag("paper-body"))
                            }
                        }
                    }
                }
            }
        }
        fun textColor(text: String): Color {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.color
        }
        compose.waitForIdle()
        val initialIdentity = readerIdentity
        for (paper in ReaderPaper.entries + ReaderPaper.Default) {
            compose.runOnIdle { choice = paper.id }
            assertEquals(paper.colors?.text ?: Color.Magenta, textColor("Paper body"))
            assertEquals(paper.colors?.text ?: Color.Green, textColor("Paper menu"))
            assertEquals(Color.Green, textColor("Library"))
            assertSame(initialIdentity, readerIdentity)
            assertEquals(paper.colors?.isDark ?: false, readerDark)
            paper.colors?.let { colors ->
                val body = textColor("Paper body").luminance()
                val markedPaper = speechHighlight.compositeOver(colors.background).luminance()
                assertTrue("${paper.id}: live paper highlight", speechHighlight.alpha in 0.01f..0.3f)
                assertTrue("${paper.id}: live highlighted body",
                    (maxOf(body, markedPaper) + 0.05f) / (minOf(body, markedPaper) + 0.05f) >= 7f)
            }
        }
        compose.runOnIdle { choice = ReaderPaper.Sepia.id; dark = true }
        assertEquals(ReaderPaper.Sepia.colors!!.text, textColor("Paper body"))
        assertEquals(Color.Cyan, textColor("Library"))
        compose.runOnIdle { choice = ReaderPaper.Default.id }
        assertEquals(Color.Red, textColor("Paper body"))
        assertEquals(Color.Cyan, textColor("Paper menu"))
        assertSame(initialIdentity, readerIdentity)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun readerMenuKeepsChapterAndCenterActionsReachableWithLargeEnglishText() {
        val clicked = mutableListOf<String>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(colorScheme = ReaderPaper.Sepia.colors!!.colorScheme(), typography = AppTypography) {
                    Column(Modifier.width(320.dp)) {
                        Box(Modifier.testTag("reader-menu")) {
                            ReaderBottomBar(true, true,
                                { clicked += "previous" }, { clicked += "next" },
                                { clicked += "settings" }, { clicked += "contents" })
                        }
                        TabsRow(listOf(TabItem("Appearance", R.drawable.filled_menu_book_24px),
                            TabItem("Controls", R.drawable.settings_applications_24px),
                            TabItem("Layout", R.drawable.aspect_ratio_24px)), 0, {})
                    }
                }
            }
        }
        val previous = compose.onNodeWithText(activity.get().getString(R.string.previous_chapter))
        val next = compose.onNodeWithText(activity.get().getString(R.string.next_chapter))
        val contents = compose.onNodeWithContentDescription(activity.get().getString(R.string.detail_contents))
        val settings = compose.onNodeWithContentDescription(activity.get().getString(R.string.settings))
        val menu = compose.onNodeWithTag("reader-menu").fetchSemanticsNode().boundsInRoot
        for (node in listOf(previous, contents, settings, next)) {
            node.assertIsDisplayed().performClick()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("Control must stay inside the menu: $bounds / $menu",
                bounds.left >= menu.left && bounds.right <= menu.right && bounds.bottom <= menu.bottom)
        }
        assertTrue(previous.fetchSemanticsNode().boundsInRoot.right <= contents.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(settings.fetchSemanticsNode().boundsInRoot.right <= next.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(listOf("previous", "contents", "settings", "next"), clicked)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Appearance").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Tab label must fit without clipping", !layouts.single().hasVisualOverflow)
    }

    @Test
    fun errorComponentStillDisplaysTheErrorLabelAndOriginalMessage() {
        val component = ErrorContentComponent.of("component class not found\nid=fixture:missing")
        setContent { component.Content(Modifier) }
        compose.onNodeWithText(activity.get().getString(R.string.reader_content_error)).assertIsDisplayed()
        compose.onNodeWithText("component class not found\nid=fixture:missing").assertIsDisplayed()
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { activity.get().setContent(content = content) }
    }
}
