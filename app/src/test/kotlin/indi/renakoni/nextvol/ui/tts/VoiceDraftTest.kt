package indi.renakoni.nextvol.ui.tts

import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.OfflineVoiceMetadata
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.editHttpVoice
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.tts.speakerData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VoiceDraftTest {
    @Test fun savedDraftRetainsItsJsonAndMapsToBothVoiceTypes() {
        val saved = """{
            "name":"Edited", "code":"speaker", "locale":"", "parameters":"raw parameters", "extensions":"[]",
            "speed":"1.40", "pitch":"0.80", "volume":"0.90", "sampleRate":"24000",
            "gender":"1", "type":"2", "note":"note", "description":"description", "avatar":" avatar.png ", "enabled":false
        }"""
        val draft = Json.decodeFromString<VoiceDraft>(saved)
        assertEquals(Json.parseToJsonElement(saved), Json.parseToJsonElement(Json.encodeToString(draft)))
        val fields = draft.fields()
        val original = InstalledOfflineVoice(OfflineVoiceRef("installed", "revision"), "Original", mapOf("model" to 10L),
            sourceKey = "package", configuration = OfflineVoiceConfig("sherpa", "original", parameters = "resolved",
                engineParameters = "engine"), packageAvatar = "package.png")
        val edited = fields.applyTo(original)
        assertEquals(original.copy(name = "Edited", enabled = false,
            configuration = original.configuration!!.copy(code = "speaker", locale = "en-US", rawParameters = "raw parameters",
                extendUI = "[]", speed = 1.4f, pitch = 0.8f, volume = 0.9f, sampleRate = 24000, gender = 1, type = 2),
            metadata = OfflineVoiceMetadata("note", "description", "avatar.png")), edited)

        val source = SavedHttpSpeechSource(previewHttpSpeech("""{
            "id":"http-voice", "name":"Original", "url":"https://example.org/tts"
        }""").sources.single(), listOf("https://example.org:443"))
        val http = editHttpVoice(source, fields.speakerFields(), fields.enabled, copy = false)
        val expected = Json.parseToJsonElement("""{
            "name":"Edited", "code":"speaker", "locale":"en-US", "param":"raw parameters", "extendUI":"[]",
            "speed":1.4, "pitch":0.8, "volume":0.9, "sampleRate":24000,
            "gender":1, "type":2, "note":"note", "desc":"description", "avatar":"avatar.png"
        }""")
        assertEquals(expected, http.speakerData())
        assertEquals(source.definition.id, http.definition.id)
        assertEquals(source.origins, http.origins)
        assertFalse(http.enabled)
        val normalized = draft.copy(locale = "en-US", speed = "1.4", pitch = "0.8", volume = "0.9", avatar = "avatar.png")
        assertEquals(normalized, VoiceDraft.from(edited))
        assertEquals(normalized, VoiceDraft.from(http, http.definition.id))
    }
}
