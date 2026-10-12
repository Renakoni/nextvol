package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderLayoutPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val base = mockk<ReaderSettingsEditor>(relaxed = true) {
        every { paperId } returns "default"
        every { fontSize } returns 15f
        every { fontWeigh } returns 500f
        every { fontLineHeight } returns 7f
        every { fontFamilyUri } returns Uri.EMPTY
    }
    private var layout by mutableStateOf(ReaderLayoutSettings(15f, 500f, 7f, 0f, Uri.EMPTY, false, 12f, 12f, 16f, 16f))
    private var windowSize by mutableStateOf(IntSize(360, 640))
    private var previewSize by mutableStateOf(IntSize(360, 148))
    private var fontScale by mutableStateOf(1f)
    private var indicator by mutableStateOf(false)
    private var bodyPadding: PaddingValues = PaddingValues(0.dp)

    @Before fun setUp() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test fun eachManualMarginImmediatelyChangesItsOwnPreviewEdge() {
        showPreview()
        val initial = viewport()
        compose.runOnIdle { layout = layout.copy(start = 48f) }
        assertEquals(initial.left + 32, viewport().left, 1f)
        assertEquals(initial.right, viewport().right, 1f)
        compose.runOnIdle { layout = layout.copy(end = 40f) }
        assertEquals(initial.right - 24, viewport().right, 1f)
        compose.runOnIdle { layout = layout.copy(top = 52f) }
        assertEquals(initial.top + 40f * 148 / 640, viewport().top, 1f)
        compose.runOnIdle { layout = layout.copy(bottom = 60f) }
        assertEquals(initial.bottom - 48f * 148 / 640, viewport().bottom, 1f)
    }

    @Test fun welcomeIsCenteredInsideTheReadingViewportAcrossWindowSizes() {
        showPreview()
        fun assertCentered() {
            val first = compose.onNodeWithText("欢迎来到Nextvol").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithText("Welcome to Nextvol").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport().center.y, (first.top + last.bottom) / 2, 1f)
            assertEquals(viewport().center.x, first.center.x, 1f)
            assertEquals(viewport().center.x, last.center.x, 1f)
        }
        assertCentered()
        compose.runOnIdle {
            windowSize = IntSize(640, 360)
            previewSize = IntSize(320, 190)
            fontScale = 1.3f
            layout = layout.copy(paragraphSpacing = 6f)
        }
        assertCentered()
    }

    @Test fun automaticPaddingAndFooterUseTheBodySemanticsAtPreviewScale() {
        showPreview()
        compose.runOnIdle { layout = layout.copy(autoPadding = true) }
        val automatic = viewport()
        assertEquals(bodyPadding.calculateTopPadding().value * 148 / 640, automatic.top, 1f)
        assertEquals(148 - bodyPadding.calculateBottomPadding().value * 148 / 640, automatic.bottom, 1f)
        compose.runOnIdle { layout = layout.copy(top = 100f, bottom = 100f, start = 100f, end = 100f) }
        assertEquals(automatic, viewport())
        compose.runOnIdle { indicator = true }
        assertEquals(automatic.bottom - 40f * 148 / 640, viewport().bottom, 1f)
        compose.runOnIdle { layout = layout.copy(autoPadding = false) }
        assertEquals(100f, viewport().left, 1f)
        assertEquals(100f * 148 / 640, viewport().top, 1f)
    }

    @Test fun largeMarginsAndTypeKeepTheSampleScrollableAcrossWindowSizes() {
        layout = layout.copy(top = 128f, bottom = 128f, start = 128f, end = 128f, fontSize = 64f)
        fontScale = 1.8f
        indicator = true
        showPreview()
        assertTrue(viewport().height > 70)
        compose.onNodeWithText("Welcome to Nextvol").performScrollTo()
        compose.runOnIdle {
            windowSize = IntSize(640, 360)
            previewSize = IntSize(320, 90)
        }
        assertTrue(viewport().height >= 15)
        assertEquals(64f, viewport().left, 1f)
        compose.onNodeWithText("Welcome to Nextvol").performScrollTo()
    }

    @Test fun draggingAMarginUpdatesTheFixedPreviewBeforeCommittingOnRelease() {
        val topPaddingUserData = base.topPaddingUserData
        previewSize = IntSize(360, 640)
        showPreview(fullPage = true)
        val initial = viewport()
        val slider = compose.onNodeWithContentDescription("Top Margin").performScrollTo()
        compose.onNodeWithText("Page preview").assertIsDisplayed()
        slider.performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.7f, centerY), delayMillis = 100)
        }
        assertTrue(viewport().top > initial.top)
        verify(exactly = 0) { topPaddingUserData.asynchronousSet(any()) }
        slider.performTouchInput { up() }
        verify(exactly = 1) { topPaddingUserData.asynchronousSet(match { it > 12f && it <= 128f }) }
    }

    private fun viewport(): Rect = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Welcome to Nextvol")))
        .fetchSemanticsNode().boundsInRoot

    private fun showPreview(fullPage: Boolean = false) {
        compose.runOnUiThread {
            activity.get().setContent {
                val settings = object : ReaderSettingsEditor by base {
                    override val backgroundColor = Color.White
                    override val backgroundDarkColor = Color.Black
                    override val textColor = Color.Black
                    override val textDarkColor = Color.White
                    override val fontSize = layout.fontSize
                    override val autoPadding = layout.autoPadding
                    override val topPadding = layout.top
                    override val bottomPadding = layout.bottom
                    override val leftPadding = layout.start
                    override val rightPadding = layout.end
                    override val enableTimeIndicator = indicator
                }
                CompositionLocalProvider(
                    LocalAppTheme provides AppTheme(false, lightColorScheme()),
                    LocalDensity provides Density(1f, fontScale),
                    LocalWindowInfo provides object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = windowSize
                    },
                ) {
                    val padding = readerPadding(layout, if (indicator) 40.dp else 0.dp)
                    SideEffect { bodyPadding = padding }
                    MaterialTheme {
                        if (fullPage) ReaderLayoutSettingsPage(settings)
                        else ReaderLayoutPreview(settings, Modifier.size(previewSize.width.dp, previewSize.height.dp))
                    }
                }
            }
        }
    }
}
