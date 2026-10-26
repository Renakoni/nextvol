package indi.renakoni.nextvol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.theme.DefaultDarkColorScheme
import indi.renakoni.nextvol.theme.DefaultLightColorScheme
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class TypographyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()

    @Test fun sharedTypographyDefinesTheDocumentedPageAndSheetRoles() {
        assertEquals(22.sp, AppTypography.displayLarge.fontSize)
        assertEquals(28.sp, AppTypography.displayLarge.lineHeight)
        assertEquals(19.sp, AppTypography.displayMedium.fontSize)
        assertEquals(26.sp, AppTypography.displayMedium.lineHeight)
        assertTrue(AppTypography.displayLarge.fontWeight!!.weight >= 600)
        assertTrue(AppTypography.displayMedium.fontWeight!!.weight >= 600)
    }

    @Test fun wrappedHeadingsAndSettingsRowsKeepRoomForEveryLine() {
        var scale by mutableStateOf(1f)
        var dark by mutableStateOf(false)
        var chinese by mutableStateOf(false)
        var role by mutableStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(typography = AppTypography,
                    colorScheme = if (dark) DefaultDarkColorScheme else DefaultLightColorScheme) {
                    Surface(Modifier.width(240.dp)) {
                        val title = if (chinese) "中文标题\n第二行文字" else "Reading settings\nTypography gyp"
                        when (role) {
                            0 -> Text(title, style = MaterialTheme.typography.displayLarge)
                            1 -> Text(title, style = MaterialTheme.typography.displayMedium)
                            2 -> SectionHeader(text = title)
                            else -> SettingsClickableEntry(title = title,
                                description = if (chinese) "说明文字\n行距保持清晰" else "Description\nLegible spacing", onClick = {})
                        }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.3f, 1.5f, 2f)) for (isDark in listOf(false, true)) {
            for (isChinese in listOf(false, true)) for (textRole in 0..3) {
                compose.runOnIdle { scale = fontScale; dark = isDark; chinese = isChinese; role = textRole }
                val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                assertTrue(nodes.fetchSemanticsNodes().isNotEmpty())
                for (index in nodes.fetchSemanticsNodes().indices) {
                    val result = layout(nodes[index])
                    val label = "scale=$scale dark=$dark chinese=$chinese role=$role"
                    assertTrue("text has measurable height: $label", result.size.height > 0)
                    assertTrue("fixture must wrap: $label", result.lineCount >= 2)
                    val fontPixels = with(result.layoutInput.density) { result.layoutInput.style.fontSize.toPx() }
                    for (line in 1 until result.lineCount) {
                        assertTrue("lines must not squeeze glyphs: $label",
                            result.getLineBaseline(line) - result.getLineBaseline(line - 1) >= fontPixels)
                    }
                    assertTrue("last line fits: $label", result.getLineBottom(result.lineCount - 1) <= result.size.height + 1)
                }
            }
        }
    }

    @Test fun pageTitlesShareHierarchyAndStayInsideTheirToolbars() {
        var scale by mutableStateOf(1f)
        var title by mutableStateOf("Title")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(typography = AppTypography) {
                    Column(Modifier.width(320.dp)) {
                        Column(Modifier.fillMaxWidth().testTag("settings")) {
                            SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.settings_text_formatting, {})
                        }
                        Column(Modifier.fillMaxWidth().testTag("discovery")) {
                            DiscoveryTopBar(title, "Wenku8", {}, {}, {}, onFilters = {}, filterCount = 0)
                        }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.3f, 1.5f, 2f)) {
            for (text in listOf("Title", "这是一个需要省略显示的很长的页面标题", "A very long discovery page title with gyp")) {
                compose.runOnIdle { scale = fontScale; title = text }
                val settings = compose.onNodeWithText(compose.activity.getString(R.string.settings_text_formatting), useUnmergedTree = true)
                val discovery = compose.onNodeWithText(text, useUnmergedTree = true)
                val first = layout(settings)
                val second = layout(discovery)
                assertEquals(first.layoutInput.style.fontSize, second.layoutInput.style.fontSize)
                assertEquals(first.layoutInput.style.lineHeight, second.layoutInput.style.lineHeight)
                assertEquals(first.layoutInput.style.fontWeight, second.layoutInput.style.fontWeight)
                for ((node, bar) in listOf(settings to "settings", discovery to "discovery")) {
                    node.assertIsDisplayed()
                    val result = layout(node)
                    assertEquals(1, result.lineCount)
                    assertTrue("title has measurable height at $fontScale: $bar", result.size.height > 0)
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    val parent = compose.onNodeWithTag(bar).fetchSemanticsNode().boundsInRoot
                    assertTrue("visible title width at $fontScale", bounds.width > 0)
                    assertTrue("top fits at $fontScale", bounds.top >= parent.top)
                    assertTrue("bottom fits at $fontScale", bounds.bottom <= parent.bottom)
                    assertTrue("layout is not clipped at $fontScale", bounds.height >= result.size.height - 1)
                }
            }
        }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
