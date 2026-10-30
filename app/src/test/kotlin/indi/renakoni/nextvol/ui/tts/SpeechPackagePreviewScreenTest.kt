package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import org.junit.After
import org.junit.Assert.assertEquals
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechPackagePreviewScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

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
}
