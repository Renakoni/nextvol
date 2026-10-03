package indi.renakoni.nextvol.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class RollingNumberTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val number = mutableIntStateOf(0)
    private val animated = mutableStateOf(true)

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    private fun mount(length: Int? = 3, separator: Boolean = false) {
        activity.get().setContent {
            MaterialTheme {
                LookaheadScope {
                    RollingNumber(number.intValue, Modifier.testTag("number"), length = length,
                        separator = separator, animationEnabled = animated.value)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun progressDigitSlotsSurviveRepeatedChapterBoundaries() {
        mount()
        val original = digitIds()
        val width = compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot.width
        for (destination in listOf(100, 99, 0, 100, 9, 10, 0)) {
            compose.runOnIdle { number.intValue = destination }
            compose.waitForIdle()
            assertEquals("Crossing a chapter must reuse the prepared digit nodes", original, digitIds())
            assertEquals(width, compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot.width, 0.1f)
            assertEquals(destination.toString(), displayedText())
        }
        compose.runOnIdle { animated.value = false; number.intValue = 100 }
        compose.waitForIdle()
        assertEquals("100", displayedText())
        assertEquals(original, digitIds())
    }

    @Test fun paddingDoesNotDisplayLeadingZerosOrSeparators() {
        animated.value = false
        mount(length = 6, separator = true)
        for (destination in listOf(0, 12, 1234, -1234, -12, 0)) {
            compose.runOnIdle { number.intValue = destination }
            compose.waitForIdle()
            val expected = when (destination) { 1234 -> "1,234"; -1234 -> "-1,234"; else -> destination.toString() }
            assertEquals(expected, displayedText())
        }
    }

    @Test fun variableLengthNumbersStillGrowAndShrink() {
        animated.value = false
        mount(length = null)
        for (destination in listOf(1, 100, -12, 0)) {
            compose.runOnIdle { number.intValue = destination }
            compose.waitForIdle()
            assertEquals(destination.toString(), displayedText())
        }
    }

    private fun digitIds() = compose.onAllNodes(hasText("8"), useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.id }.toSet().also { assertTrue(it.isNotEmpty()) }

    private fun displayedText(): String = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        .sortedBy { it.boundsInRoot.left }
        .joinToString("") { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
}
