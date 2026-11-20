package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
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
@Config(sdk = [29], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SovitsImportScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private fun label(id: Int) = activity.get().getString(id)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun installedRuntimePinsEmptyTabAfterSystemAndScopesWeightImportToThatTab() {
        val installed = mutableStateOf(false)
        val groups = mutableStateOf(mapOf("vendor" to "Vendor"))
        val voices = mutableStateOf(emptyList<InstalledOfflineVoice>())
        var weightImports = 0
        var sourceImports = 0
        activity.get().setContent {
            MaterialTheme {
                HttpSpeechSourcesScreen(HttpSpeechSourcesState(loaded = true), {}, { sourceImports++ },
                    {}, {}, {}, {}, { _, _, _, _ -> }, offlineGroups = groups.value,
                    offlineVoices = voices.value, offlineRow = { voice, _ -> Text(voice.name) },
                    sovitsInstalled = installed.value, onImportWeights = { weightImports++ })
            }
        }
        compose.onNodeWithText(label(R.string.tts_runtime_sovits)).assertDoesNotExist()
        compose.runOnIdle { installed.value = true }
        val tabs = hasAnyAncestor(hasTestTag("speech-source-tabs"))
        val system = compose.onNode(tabs and hasText(label(R.string.tts_system_speech)))
        val sovits = compose.onNode(tabs and hasText(label(R.string.tts_runtime_sovits)))
        val vendor = compose.onNode(tabs and hasText("Vendor"))
        assertTrue(system.fetchSemanticsNode().boundsInRoot.left < sovits.fetchSemanticsNode().boundsInRoot.left)
        assertTrue(sovits.fetchSemanticsNode().boundsInRoot.left < vendor.fetchSemanticsNode().boundsInRoot.left)
        sovits.performClick()
        compose.onNodeWithText(label(R.string.sovits_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.tts_library_actions)).performClick()
        compose.onNodeWithText(label(R.string.tts_import_sources)).assertDoesNotExist()
        compose.onAllNodesWithText(label(R.string.sovits_import_weights)).onLast().performClick()
        assertEquals(1, weightImports)
        compose.runOnIdle { groups.value = emptyMap() }
        sovits.assertIsSelected()
        compose.onNodeWithText(label(R.string.sovits_empty)).assertIsDisplayed()
        compose.runOnIdle {
            voices.value = listOf(InstalledOfflineVoice(OfflineVoiceRef("voice", "revision"), "Imported voice", emptyMap(),
                configuration = OfflineVoiceConfig("gpt-sovits", "v2Pro.voice")))
        }
        compose.onNodeWithText("Imported voice").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.sovits_empty)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.sovits_import_weights)).assertDoesNotExist()
        system.performClick()
        compose.onNodeWithContentDescription(label(R.string.tts_library_actions)).performClick()
        compose.onNodeWithText(label(R.string.tts_import_sources)).performClick()
        assertEquals(1, sourceImports)
        compose.runOnIdle {
            groups.value = mapOf("gpt-sovits" to "GPT-SoVITS")
            installed.value = false
        }
        compose.onNode(tabs and hasText(label(R.string.tts_runtime_sovits))).assertDoesNotExist()
    }
}
