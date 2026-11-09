package indi.renakoni.nextvol.ui.localbook

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.localbook.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalBookRelinkUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val files = TemporaryFolder()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun state(match: LocalBookRelinkMatch): LocalBookRelinkState {
        val book = SourceBookId(LocalBookStore.SOURCE, "restored")
        val directory = files.newFolder()
        File(directory, "0.json").writeText(Json.encodeToString(LocalBookChapter("Chapter 1", blocks = listOf(LocalBookBlock.Text("Preview text")))))
        val parsed = PreparedLocalBook("Saved book", listOf(LocalChapterIndex("Chapter 1", "")), directory)
        val manifest = LocalBookFileManifest(book.storageKey, "TXT", "novel.txt", "a".repeat(64), "b".repeat(64))
        return LocalBookRelinkState(visible = true, fileName = "novel.txt", format = LocalBookFormat.TXT,
            preview = LocalBookRelinkPreview(book, parsed, match, LocalBookDraft(book, "novel.txt", LocalBookFormat.TXT, File("unused")),
                manifest, null, null, null, null))
    }
    @Test fun oldBackupNeedsExplicitConfirmationAndKeepsImportTitleEditingHidden() {
        var state by mutableStateOf(state(LocalBookRelinkMatch.Legacy))
        var linked = 0
        activity.get().setContent { NextVolTheme(darkMode = "Disabled", isDynamicColor = false, lightThemeName = "light_default",
            darkThemeName = "dark_default", appLocale = "en-US") {
            LocalBookImportDialog(LocalBookImportState(bookKey = "restored", title = "Saved book", preview = state.preview?.parsed),
                onDismiss = {}, onTitleChange = {}, onEncodingChange = {}, onRuleChange = {}, onImport = { linked++ },
                relinkState = state, onConfirmLegacy = { state = state.copy(confirmed = it) })
        } }
        compose.onNodeWithText("Relink").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(activity.get().getString(R.string.local_file_legacy_confirmation)).performClick()
        compose.onNodeWithText("Relink").assertIsEnabled().performClick()
        assertEquals(1, linked)
    }
    @Test fun differentFileShowsActionableMismatchAndDisablesConfirmation() {
        val state = state(LocalBookRelinkMatch.DifferentFile)
        activity.get().setContent { NextVolTheme(darkMode = "Disabled", isDynamicColor = false, lightThemeName = "light_default",
            darkThemeName = "dark_default", appLocale = "en-US") {
            LocalBookImportDialog(LocalBookImportState(title = "Saved book"),
                onDismiss = {}, onTitleChange = {}, onEncodingChange = {}, onRuleChange = {}, onImport = {}, relinkState = state)
        } }
        compose.onNodeWithText(activity.get().getString(R.string.local_file_match_different_file)).assertIsDisplayed()
        compose.onNodeWithText("Relink").assertIsNotEnabled()
    }
    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp")
    fun missingFileActionRemainsVisibleWithLargeTextAndDarkTheme() {
        var selected = false
        activity.get().setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                LocalBookMissingFile({ selected = true }, Modifier.width(360.dp))
            }
        } }
        compose.onNodeWithText("需要原文件").assertIsDisplayed()
        compose.onNodeWithText("重新关联原文件").assertIsDisplayed().performClick()
        assertTrue(selected)
    }
    @Test fun matchAndScopeMessagesResolveInAllSupportedLanguages() {
        val keys = listOf(R.string.local_file_backup_scope, R.string.local_file_missing_body,
            R.string.local_file_relink_preserves, R.string.local_file_match_exact, R.string.local_file_match_legacy,
            R.string.local_file_match_different_file, R.string.local_file_match_different_mapping,
            R.string.local_file_match_missing_mapping, R.string.local_file_legacy_confirmation)
        val context = RuntimeEnvironment.getApplication()
        val messages = listOf("en", "zh-CN", "zh-TW").associateWith { locale ->
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            keys.map { localized.getString(it) }
        }
        for (locale in listOf("zh-CN", "zh-TW")) keys.indices.forEach { index ->
            assertNotEquals(messages.getValue("en")[index], messages.getValue(locale)[index])
        }
    }
}
