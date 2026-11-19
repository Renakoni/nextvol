package indi.renakoni.nextvol.reader

import android.net.Uri
import android.view.KeyEvent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.ui.home.settings.theme.ThemeScreen
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.componet.*
import indi.renakoni.nextvol.ui.book.reader.loadReaderFontFamilySafe
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderLayoutInstrumentedTest {
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

    @Test fun softBreakAtPageBoundaryKeepsMeasuredHeightAndSourceOffsets() {
        val source = ReaderTextSource(0, "A soft\u2028line break and a final paragraph.\n")
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1.5f), LayoutDirection.Ltr)
        val style = ReaderLayoutSettings.from(settings).textStyle(FontFamily.Default, LocaleList("zh-CN,en-US"))
        val pages = layoutReaderText(listOf(source), 90, 100, 13, style, measurer)
        val fragments = pages.flatten()
        assertEquals(source.text, fragments.joinToString("") { source.text.substring(it.start, it.end) })
        pages.forEach { page ->
            val actualHeight = page.sumOf { fragment ->
                val actual = measurer.measure(fragment.text, style, constraints = Constraints.fixedWidth(90))
                assertEquals("fragment=${fragment.text}", fragment.height, actual.size.height)
                actual.size.height + fragment.spacingBefore
            }
            assertTrue("page overflow: $actualHeight", actualHeight <= 100)
        }
    }

    @Test fun missingAndInvalidCustomFontsFallBack() {
        val invalid = File(context.cacheDir, "invalid-reader-font.ttf")
        try {
            invalid.writeText("not a font")
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(invalid)))
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(File(context.cacheDir, "missing-reader-font.ttf"))))
        } finally {
            invalid.delete()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun paperSubpageReturnsToReadingSettingsBeforeClosingTheSheet() {
        var dismissed = false
        compose.setContent {
            NextVolTheme("Disabled", false, "light_default", "dark_default", "zh-CN") {
                SettingsBottomSheet(
                    rememberBottomSheetState(initialValue = SheetValue.Expanded),
                    onDismissRequest = { dismissed = true }, settingState = settings, onClickThemeSettings = {},
                )
            }
        }
        compose.onNodeWithTag("reader-paper-sage").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.paper_settings)).performClick()
        compose.onNodeWithTag("reader-paper-sage").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.paperId == "sage" }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(context.getString(R.string.appearance_settings)).assertIsDisplayed()
        assertFalse(dismissed)
        compose.onNodeWithTag("reader-paper-sage").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.paper_settings)).performClick()
        compose.onNodeWithTag("reader-paper-sage").assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.appearance_settings)).assertIsDisplayed()
    }

    @Test fun externalPaperSubpagePreservesSelectionAndParentScrollPosition() {
        var leftTheme = false
        compose.setContent {
            NextVolTheme("Disabled", false, "light_default", "dark_default", "zh-CN") {
                ThemeScreen(settings, settings, onClickBack = { leftTheme = true },
                    onClickChangeTextColor = {}, onClickChangeBackgroundColor = {})
            }
        }
        compose.onNodeWithTag("reader-paper-sage").assertDoesNotExist()
        val paperEntry = hasText(context.getString(R.string.paper_settings)) and hasClickAction()
        compose.onNodeWithTag("theme-settings-list").performScrollToNode(paperEntry)
        compose.onNode(paperEntry).performClick()
        compose.onNodeWithTag("reader-paper-sage").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings.paperId == "sage" }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNode(paperEntry).assertIsDisplayed()
        assertFalse(leftTheme)
        compose.onNodeWithTag("reader-paper-sage").assertDoesNotExist()
        compose.onNode(paperEntry).performClick()
        compose.onNodeWithTag("reader-paper-sage").assertIsSelected()
    }
}
