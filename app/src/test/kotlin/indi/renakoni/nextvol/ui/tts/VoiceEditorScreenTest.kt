package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.editHttpVoice
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.tts.sovits.SovitsSynthesisOptions
import kotlinx.serialization.json.*
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
@Config(sdk = [29], application = Application::class, qualifiers = "en-rUS-w400dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class VoiceEditorScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private fun label(id: Int) = activity.get().getString(id)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun sovitsEditorSavesAllFiveControlsAndKeepsUnsupportedControlsUnavailable() {
        val voice = InstalledOfflineVoice(OfflineVoiceRef("voice", "revision"), "Voice", emptyMap(),
            configuration = OfflineVoiceConfig("gpt-sovits", "v2Pro-1",
                parameters = """{"model":"v2Pro.voice"}""", sampleRate = 32000))
        val draft = mutableStateOf(VoiceDraft.from(voice))
        var saved: VoiceFields? = null
        activity.get().setContent { MaterialTheme {
            VoiceEditorScreen(VoiceEditorState(draft = draft.value), false, { draft.value = it },
                { saved = draft.value.fields() }, {}, {}, showParameters = true, showExtensions = true)
        } }
        compose.onNodeWithText(label(R.string.tts_voice_sample_rate)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("32kHz").assertExists()
        compose.onNodeWithText(label(R.string.tts_voice_volume)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.tts_pitch)).assertDoesNotExist()
        listOf(R.string.tts_voice_speed to "0.85", R.string.sovits_temperature to "0.5",
            R.string.sovits_top_k to "23", R.string.sovits_top_p to "0.7", R.string.sovits_repetition_penalty to "1.6")
            .forEach { (field, value) -> compose.onNodeWithText(label(field)).performScrollTo().performTextReplacement(value) }
        compose.onNodeWithTag("offline-voice-editor").performScrollToNode(hasText(label(R.string.tts_engine_note)))
        compose.onNodeWithText(label(R.string.tts_voice_parameters)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.tts_voice_extensions)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.tts_engine_save)).performClick()
        assertEquals(SovitsSynthesisOptions(.85f, .5f, 23, .7f, 1.6f), saved!!.sovits)
    }

    @Test fun savingABuiltInVoiceDoesNotExposeItsCodeAndOnlyDescriptionAppearsOnTheCard() {
        val entry = activity.get().assets.open("speech/builtin-voices.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray.first().jsonObject
        }
        val definition = previewHttpSpeech(entry.getValue("definition").toString()).sources.single()
        val source = mutableStateOf(SavedHttpSpeechSource(definition, emptyList(), group = entry.getValue("group").jsonPrimitive.content))
        activity.get().setContent { MaterialTheme {
            HttpSpeechSourcesScreen(HttpSpeechSourcesState(sources = listOf(source.value), loaded = true), {}, {}, {}, {}, {}, {},
                { _, _, _, _ -> }, focusVoiceKey = "http:${definition.id}")
        } }
        compose.onNodeWithText(definition.name).assertIsDisplayed()
        compose.onNodeWithText(definition.id).assertDoesNotExist()
        compose.runOnIdle {
            val fields = VoiceDraft.from(source.value, definition.id).fields().speakerFields()
            source.value = editHttpVoice(source.value, fields, enabled = true, copy = false)
        }
        compose.onNodeWithText(definition.name).assertIsDisplayed()
        compose.onNodeWithText(definition.id).assertDoesNotExist()
        compose.runOnIdle {
            val fields = VoiceDraft.from(source.value, definition.id).copy(description = "Voice description").fields().speakerFields()
            source.value = editHttpVoice(source.value, fields, enabled = true, copy = false)
        }
        compose.onNodeWithText("Voice description").assertIsDisplayed()
        compose.onNodeWithText(definition.id).assertDoesNotExist()
    }
}
