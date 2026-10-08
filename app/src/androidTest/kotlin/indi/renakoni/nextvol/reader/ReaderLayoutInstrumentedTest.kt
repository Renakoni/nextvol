package indi.renakoni.nextvol.reader

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.home.settings.theme.ThemeScreen
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.*
import indi.renakoni.nextvol.ui.book.reader.content.componet.*
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderPage
import indi.renakoni.nextvol.ui.book.reader.content.flip.paginateReaderComponents
import indi.renakoni.nextvol.utils.loadReaderFontFamilySafe
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
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
    private val importedFiles = mutableListOf<File>()

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).build()
        settings = SettingState(UserDataRepository(database.userDataDao()), scope)
    }

    @After fun close() { scope.cancel(); database.close(); importedFiles.forEach { it.delete() } }

    @Test fun nativeFontsKeepPageFragmentsWithinMeasuredHeightAndRetainAllSourceOffsets() {
        val source = ReaderTextSource(0, "\n午后的阳光落在书页上。Mixed English and 中文 text, with punctuation! 𠮷🙂 ".repeat(8) +
            "\r\n\nA soft\u2028line break and a final paragraph.\n")
        for (fontScale in listOf(1f, 1.8f)) for (font in ReaderFont.entries.map { it.family() }) {
            val density = Density(1.5f, fontScale)
            val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
            val style = ReaderLayoutSettings.from(settings).textStyle(font, LocaleList("zh-CN,en-US"))
            for (width in listOf(90, 240, 620)) for (height in listOf(1, 100, 500)) {
                val pages = layoutReaderText(listOf(source), width, height, 13, style, measurer)
                val fragments = pages.flatten()
                assertEquals(source.text, fragments.joinToString("") { source.text.substring(it.start, it.end) })
                pages.forEach { page ->
                    assertEquals(0, page.first().spacingBefore)
                    val actualHeight = page.sumOf { fragment ->
                        val actual = measurer.measure(fragment.text, style, constraints = Constraints(maxWidth = width))
                        assertEquals("fragment reflow: width=$width height=$height text=${fragment.text}", fragment.height, actual.size.height)
                        actual.size.height + fragment.spacingBefore
                    }
                    assertTrue("page overflow: $actualHeight > $height", actualHeight <= height || page.size == 1)
                }
            }
        }
    }

    @Test fun changingFontsAndWidthKeepsTheOriginalCharacterAnchor() = runBlocking {
        val text = (1..80).joinToString("\n") { "第 $it 段：午后的阳光落在书页上。A quiet afternoon in the library." }
        val components = listOf(SimpleTextComponent(SimpleTextComponentData(text), UserDataRepository(database.userDataDao()), context))
        val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1.5f), LayoutDirection.Ltr)
        val initial = ReaderLayoutSettings.from(settings)
        val original = paginateReaderComponents(components, 400, 300,
            ReaderTextLayoutInput(initial, initial.textStyle(FontFamily.Default, LocaleList("zh-CN")), measurer, 0))
        val anchor = (original[original.size / 2] as ReaderPage).anchor
        for (font in ReaderFont.entries) {
            val next = initial.copy(fontSize = 22f, paragraphSpacing = 9f, fontUri = font.uri)
            val pages = paginateReaderComponents(components, 360, 230,
                ReaderTextLayoutInput(next, next.textStyle(font.family(), LocaleList("zh-CN")), measurer, 14))
            val containing = pages.filterIsInstance<ReaderPage>().filter { it.contains(anchor) }
            assertEquals(1, containing.size)
            assertTrue(containing.single().ranges.any { anchor.offset in it.start until it.end })
        }
    }

    @Test fun presetsPersistInTheExistingFontKeyAndMissingCustomFontFallsBack() = runBlocking {
        for (font in ReaderFont.entries) {
            settings.fontFamilyUriUserData.set(font.uri)
            assertEquals(font.uri, settings.fontFamilyUriUserData.get())
            val family = loadReaderFontFamilySafe(font.uri)
            if (font != ReaderFont.System) assertNotNull(family)
        }
        val invalid = File(context.cacheDir, "invalid-reader-font.ttf")
        try {
            invalid.writeText("not a font")
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(invalid)))
            assertNull(loadReaderFontFamilySafe(Uri.fromFile(File(context.cacheDir, "missing-reader-font.ttf"))))
        } finally {
            invalid.delete()
        }
    }

    @Test fun layoutControlsPreviewWhileDraggingAndPersistIndependentSpacing() {
        val imported = File.createTempFile("reader-font-test-", ".otf", context.filesDir).also(importedFiles::add)
        context.resources.openRawResource(R.font.source_han_serif_regular).use { input ->
            imported.outputStream().use { input.copyTo(it) }
        }
        runBlocking { settings.fontFamilyUriUserData.set(Uri.fromFile(imported)) }
        compose.waitUntil(5_000) { settings.fontFamilyUri == Uri.fromFile(imported) }
        var dark by mutableStateOf(false)
        compose.setContent {
            val colors = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                CompositionLocalProvider(
                    LocalAppTheme provides AppTheme(dark, colors),
                    LocalReaderStyle provides ReaderStyle(15f, 7f, 500f, Color.Black, Color.White),
                ) {
                    ReaderLayoutSettingsPage(settings)
                }
            }
        }
        compose.onNodeWithTag("reader-font-list").performScrollTo()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reader-font-${Uri.fromFile(imported)}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reader-font-list").performScrollToIndex(0)
        compose.onNodeWithText(context.getString(R.string.reader_font_system)).performClick()
        compose.waitUntil(5_000) { settings.fontFamilyUri == Uri.EMPTY && imported.exists() }
        val fontSlider = compose.onNodeWithContentDescription(context.getString(R.string.settings_reader_font_size))
        fontSlider.performScrollTo().performTouchInput { down(center); moveTo(centerRight) }
        val previewLayouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node -> node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(previewLayouts) }
        assertTrue(previewLayouts.any { it.layoutInput.style.fontSize.value > 15f && it.layoutInput.text.text == "Welcome to Nextvol" })
        assertEquals(15f, settings.fontSize)
        fontSlider.performTouchInput { up() }
        compose.waitUntil(5_000) { settings.fontSize > 15f }
        val paragraph = compose.onNodeWithContentDescription(context.getString(R.string.reader_paragraph_spacing))
        paragraph.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.waitUntil(5_000) { settings.paragraphSpacing == 8f }
        assertEquals(7f, settings.fontLineHeight)
        runBlocking {
            assertEquals(8f, settings.paragraphSpacingUserData.get())
            settings.fontSizeUserData.set(18f)
        }
        compose.waitUntil(5_000) { settings.fontSize == 18f }
        compose.onNodeWithTag("reader-font-list").performScrollTo()
            .performScrollToNode(hasText(context.getString(R.string.reader_font_wenkai)))
        compose.onNodeWithText(context.getString(R.string.reader_font_wenkai)).performClick()
        compose.waitUntil(5_000) { settings.fontFamilyUri == ReaderFont.WenKai.uri }
        saveScreenshot("reader-layout-light.png")
        compose.runOnIdle { dark = true }
        saveScreenshot("reader-layout-dark.png")
    }

    @Test fun compactLayoutKeepsSpacingAndMarginControlsReachable() {
        runBlocking { settings.autoPaddingUserData.set(false) }
        compose.waitUntil(5_000) { !settings.autoPadding }
        compose.setContent {
            val colors = lightColorScheme()
            MaterialTheme(colorScheme = colors, typography = AppTypography) {
                CompositionLocalProvider(LocalAppTheme provides AppTheme(false, colors)) {
                    Box(Modifier.width(600.dp).height(250.dp)) { ReaderLayoutSettingsPage(settings) }
                }
            }
        }
        val paragraph = compose.onNodeWithContentDescription(context.getString(R.string.reader_paragraph_spacing))
            .performScrollTo().assertIsDisplayed()
        val density = context.resources.displayMetrics.density
        fun assertFullyVisible(slider: SemanticsNodeInteraction) {
            val visible = slider.fetchSemanticsNode().boundsInRoot
            val unclipped = slider.getUnclippedBoundsInRoot()
            val fullHeight = (unclipped.bottom - unclipped.top).value * density
            assertTrue("The complete slider must be visible: $visible, full height=$fullHeight", visible.height + 1f >= fullHeight)
        }
        saveScreenshot("reader-layout-compact.png")
        assertFullyVisible(paragraph)
        paragraph.performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.waitUntil(5_000) { settings.paragraphSpacing == 9f }
        val bottom = compose.onNodeWithContentDescription(context.getString(R.string.settings_reader_bottom_margin))
            .performScrollTo().assertIsDisplayed()
        assertFullyVisible(bottom)
        bottom.performSemanticsAction(SemanticsActions.SetProgress) { it(23f) }
        compose.waitUntil(5_000) { settings.bottomPadding == 23f }
        assertEquals(7f, settings.fontLineHeight)
        saveScreenshot("reader-layout-compact.png")
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
        compose.onNodeWithText(context.getString(R.string.reader_settings)).assertIsDisplayed()
        assertFalse(dismissed)
        compose.onNodeWithTag("reader-paper-sage").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.paper_settings)).performClick()
        compose.onNodeWithTag("reader-paper-sage").assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.sources_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.reader_settings)).assertIsDisplayed()
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

    // Re-enable when #353 has a reproducible cause and regression for the initial font-list update.
    @Ignore("Intermittent font-list timeout on API 24/35: https://github.com/Renakoni/nextvol/issues/353")
    @Test fun tenImportedFontsRemainSelectableAfterSwitchingAndReopening() = runBlocking {
        val source = File(context.cacheDir, "appearance-font.otf")
        val files = mutableListOf<File>()
        try {
            context.resources.openRawResource(R.font.source_han_serif_regular).use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            repeat(10) {
                importReaderFont(context, Uri.fromFile(source), settings)
                files += File(requireNotNull(requireNotNull(settings.fontFamilyUriUserData.get()).path)).also(importedFiles::add)
            }
            val selected = Uri.fromFile(files.last())
            compose.waitUntil(5_000) { settings.fontFamilyUri == selected }
            var showing by mutableStateOf(true)
            compose.setContent { MaterialTheme { if (showing) ReaderFontEntry(settings) } }
            val last = files.last().path
            try {
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-font-$selected").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: ComposeTimeoutException) {
                throw AssertionError("Selected=$selected observed=${settings.fontFamilyUri} fonts=${importedReaderFonts(context, selected)}\n${compose.onRoot().printToString()}", failure)
            }
            compose.onNodeWithTag("reader-font-$selected").assertIsSelected().assertIsDisplayed()
            compose.onNodeWithTag("reader-font-list").performScrollToIndex(0)
            compose.onNodeWithText(context.getString(R.string.reader_font_system)).performClick()
            compose.waitUntil(5_000) { settings.fontFamilyUri == Uri.EMPTY }
            assertTrue(files.all { it.exists() })
            compose.runOnIdle { showing = false }
            compose.runOnIdle { showing = true }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithTag("reader-font-list").performScrollToKey(last) }.isSuccess
            }
            compose.onNodeWithTag("reader-font-$selected").performClick()
            compose.waitUntil(5_000) { settings.fontFamilyUri == selected }
            source.writeText("not a font")
            assertTrue(runCatching { importReaderFont(context, Uri.fromFile(source), settings) }.isFailure)
            assertEquals(selected, settings.fontFamilyUriUserData.get())
            assertTrue(importedReaderFonts(context, selected).containsAll(files))
        } finally {
            source.delete()
        }
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        fun clearFocus(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.isAccessibilityFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            for (index in 0 until node.childCount) clearFocus(node.getChild(index))
        }
        // Preserve device accessibility settings; clear only the transient focus decoration.
        clearFocus(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        // Compose's window PixelCopy overload is only available from Android 8.
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            compose.onRoot().captureToImage().asAndroidBitmap()
        } else {
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        }
        bitmap.let {
            File(context.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
