package indi.renakoni.nextvol.ui.tts

import android.app.Application
import android.net.Uri
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
import indi.renakoni.nextvol.tts.sovits.SovitsImportDraft
import indi.renakoni.nextvol.tts.sovits.SovitsImportField
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
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
    }

    @Test fun replacingAWeightShowsItsErrorInTheMatchingRowAndBlocksImportUntilCorrected() {
        val file = SovitsImportFile(Uri.parse("content://test/file"), "selected file")
        val state = mutableStateOf(SovitsImportState(draft = SovitsImportDraft("Voice", file.copy(name = "voice.CKPT"), file.copy(name = "voice.pth"), file)))
        var replacement = file.copy(name = "wrong.bin")
        activity.get().setContent {
            MaterialTheme {
                SovitsImportForm(state.value, onEdit = { state.value = state.value.copy(draft = it) }, onAvatar = {},
                    onPick = { state.value = state.value.withPickedFile(it, replacement) }, onImport = {}, onCancel = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("sovits-import-confirm").assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.sovits_reference_text)).performTextInput("参考音频原文")
        val confirm = compose.onNodeWithTag("sovits-import-confirm")
        confirm.assertIsEnabled()
        for ((field, title) in listOf(SovitsImportField.Gpt to R.string.sovits_gpt_weight, SovitsImportField.Sovits to R.string.sovits_sovits_weight)) {
            val row = compose.onNodeWithText(label(title))
            replacement = file.copy(name = "wrong.bin")
            row.performClick()
            val error = activity.get().getString(R.string.sovits_choose_extension, ".${field.extension}")
            row.assert(hasText(error))
            confirm.assertIsNotEnabled()
            replacement = file.copy(name = "new.${field.extension!!.uppercase()}")
            row.performClick().assert(hasText(replacement.name))
            confirm.assertIsEnabled()
        }
    }
}
