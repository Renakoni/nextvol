package indi.renakoni.nextvol.ui.tts

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import hnovel.speech.previewSpeechPackage
import hnovel.speech.previewHttpSpeech
import hnovel.speech.previewHttpSpeechPackage
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechPackagePreviewScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun roleConfigurationIsReadableWithLargeTextAndOffersNoInstallOrAudition() {
        val file = temporary.newFile().apply { writeText("roles: [{name: Narrator}]") }
        val preview = previewSpeechPackage(file, "roles.yaml")
        var backed = false
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MaterialTheme(typography = AppTypography) { SpeechPackagePreviewScreen(preview) { backed = true } }
            }
        }
        compose.onNodeWithText("Package structure recognized").assertIsDisplayed()
        compose.onNodeWithTag("speech-package-preview").performScrollToNode(hasText("This configuration type is recognized; migration is not available yet."))
        compose.onNodeWithText("This configuration type is recognized; migration is not available yet.").assertIsDisplayed()
        compose.onNodeWithText("Import voices").assertDoesNotExist()
        compose.onNodeWithText("Preview voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test fun packageKindUsesUpdatedResourcesWithoutReplacingTheContext() {
        val file = temporary.newFile().apply { writeText("roles: []") }
        val preview = previewSpeechPackage(file, "roles.yaml")
        val resources = mutableStateOf(activity.get().resources)
        activity.get().setContent {
            CompositionLocalProvider(LocalResources provides resources.value) {
                MaterialTheme(typography = AppTypography) { SpeechPackagePreviewScreen(preview) {} }
            }
        }
        compose.onNodeWithText("Role configuration").assertIsDisplayed()
        compose.runOnIdle {
            resources.value = activity.get().createConfigurationContext(
                Configuration(resources.value.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            ).resources
        }
        compose.onNodeWithText("角色配置").assertIsDisplayed()
        compose.onNodeWithText("Role configuration").assertDoesNotExist()
    }

    @Test fun supportedHttpPackageOffersExplicitImportWithLargeText() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in listOf(
                "engines.yaml" to "engines: [{code: http_test, name: Test service, type: http, url: 'https://speech.example.invalid/?text={{java.encodeURI(speakText)}}'}]",
                "config.yaml" to "http_test: [{code: test_voice, name: Imported voice, speed: 1.3}]",
            )) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        val preview = previewSpeechPackage(file)
        val prepared = previewHttpSpeechPackage(preview.engines.single())
        var imported = emptyList<String>()
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MaterialTheme(typography = AppTypography) {
                    SpeechPackagePreviewScreen(preview, mapOf(0 to prepared),
                        onImport = { imported = it.map { source -> source.id } }, onBack = {})
                }
            }
        }
        compose.onNodeWithTag("speech-package-preview").performScrollToNode(hasText("Import voices"))
        compose.onNodeWithText("Import voices").assertIsDisplayed().performClick()
        assertEquals(prepared.sources.map { it.id }, imported)
    }

    @Test fun packageVoicesShareTheServiceCategoryAndKeepTheirOwnActions() {
        val imported = SavedHttpSpeechSource(previewHttpSpeech("""{"name":"Imported voice","url":"https://speech.example.invalid/audio",
            "multiTts":{"engine":{"code":"http_test","name":"Test service"}}}""").sources.single(), listOf("https://speech.example.invalid:443"))
        val builtIn = SavedHttpSpeechSource(previewHttpSpeech(
            """{"name":"Built-in voice","url":"https://speech.example.invalid/audio"}""").sources.single(), imported.origins, group = "Test service")
        var selected: String? = null
        var auditioned: String? = null
        var removed: String? = null
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(sources = listOf(builtIn, imported)), {}, {},
                    onSelect = { selected = it }, onDelete = { removed = it.definition.id }, onSites = { _, _ -> },
                    onConfirmImport = {}, onDismissImport = {}, onEdit = { _, _, _, _ -> }, onPreview = { auditioned = it })
            }
        }
        compose.onAllNodesWithText("Test service").assertCountEquals(1)
        compose.onNodeWithText("Test service").performClick()
        compose.onNodeWithContentDescription("Preview Imported voice").performClick()
        assertEquals(imported.definition.id, auditioned)
        assertEquals(null, selected)
        compose.onNodeWithText("Imported voice").performClick()
        assertEquals(imported.definition.id, selected)
        compose.onAllNodesWithContentDescription("Voice options").assertCountEquals(1)
        compose.onNodeWithContentDescription("Voice options").performClick()
        compose.onNodeWithText("Remove voice").performClick()
        assertEquals(imported.definition.id, removed)
    }

    @Test fun packagePreviewAndExistingJsonImportHaveSeparateWorkingEntries() {
        var imported = 0
        var previewed = 0
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(), {}, { imported++ }, {}, {}, { _, _ -> }, {}, {}, { _, _, _, _ -> },
                    onPackagePreview = { previewed++ })
            }
        }
        compose.onNodeWithContentDescription("Library options").performClick()
        compose.onNodeWithText("Import voices").assertIsDisplayed()
        compose.onNodeWithText("Preview ZIP or YAML package").performClick()
        assertEquals(1, previewed)
        assertEquals(0, imported)
        compose.onNodeWithContentDescription("Library options").performClick()
        compose.onNodeWithText("Import voices").performClick()
        assertEquals(1, imported)
    }
}
