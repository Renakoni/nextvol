package indi.renakoni.nextvol.tts

import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.previewHttpSpeech
import hnovel.speech.previewHttpSpeechPackage
import hnovel.speech.previewSpeechPackage
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceDefaults
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import hnovel.speech.OfflineVoiceMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SpeechPackageReferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun standaloneVoiceConfigurationUsesTheInstalledEngineWithoutMakingRequests() {
        val file = temporary.newFile("config.yaml").apply { writeText("http_test: [{code: voice, name: Voice}]") }
        val preview = previewSpeechPackage(file).withInstalledEngines(listOf(
            SpeechEngineConfig("http_test", "Installed engine", "http", url = "https://example.invalid/{{speakText}}")))
        val imported = previewHttpSpeechPackage(preview.engines.single())
        assertNull(imported.error)
        assertEquals("Voice", imported.sources.single().name)
    }

    @Test fun offlineReferencesFollowCurrentCodeAndTypeAcrossRestartAndReinstallation() {
        val missing = SpeechVoiceRef(externalId = "audio_sound_1", label = "Original name")
        assertEquals(missing, missing.resolvePackageReference(emptyMap()))
        val original = OfflineVoiceConfig("audio", "sound", type = 1)
        val voice = InstalledOfflineVoice(OfflineVoiceRef("stable", "revision"), "Original name", emptyMap(),
            sourceKey = "audio\u0000sound", configuration = original,
            defaults = OfflineVoiceDefaults("Original name", original, OfflineVoiceMetadata()))
        val bound = missing.resolvePackageReference(speechPackageReferences(emptyList(), listOf(voice)))
        assertEquals(missing.key, bound.key)
        assertEquals(voice.reference, bound.offlineVoice)
        val edited = voice.copy(name = "User name", configuration = original.copy(code = "edited", type = 2))
        val references = speechPackageReferences(emptyList(), listOf(edited))
        assertEquals(setOf("audio_edited_2"), references.keys)
        assertEquals(missing, bound.resolvePackageReference(references))
        val selected = SpeechSettings(narrationVoices = listOf(bound), dialogueVoices = listOf(missing),
            speakerVoices = mapOf("Sound" to bound)).updateVoiceSelections(bound,
            references.getValue("audio_edited_2").copy(externalId = "audio_edited_2"))
        assertEquals("audio_edited_2", selected.narrationVoices.single().externalId)
        assertEquals(selected.narrationVoices, selected.dialogueVoices)
        assertEquals(bound, selected.speakerVoices.getValue("Sound"))
        val file = temporary.newFile("roles.yaml").apply { writeText("roles: [{name: Sound, speakerId: audio_edited_2}]") }
        val imported = previewSpeechConfiguration(previewSpeechPackage(file), references, "Default").roles!!.single().speaker!!
        val restored = Json.decodeFromString<SpeechVoiceRef>(Json.encodeToString(imported))
        assertEquals(edited.reference, restored.offlineVoice)
        assertEquals("User name", restored.label)
        val reinstalled = edited.copy(reference = OfflineVoiceRef("reinstalled", "new-revision"), sourceKey = "")
        val rebound = restored.resolvePackageReference(speechPackageReferences(emptyList(), listOf(reinstalled)))
        assertEquals(reinstalled.reference, rebound.offlineVoice)
        assertEquals(restored.key, rebound.key)
        assertEquals(SpeechVoiceRef(offlineVoice = reinstalled.reference).canonicalKey, rebound.canonicalKey)
    }

    @Test fun onlineReferencesUseCurrentFieldsForImportedNewAndCopiedVoicesWithoutChangingReimportIdentity() {
        val original = SavedHttpSpeechSource(previewHttpSpeech("""{
            "id":"stable", "name":"Same name", "url":"https://example.invalid/speech",
            "multiTts":{"engine":{"code":"http_test","type":"http","url":"https://example.invalid/speech"},
                "voice":{"code":"voice","name":"Same name","type":"0"}}
        }""").sources.single(), emptyList())
        val edited = editHttpVoice(original, buildJsonObject { put("code", "edited"); put("type", 2) }, true, false)
        val reimported = mergeHttpVoice(edited, original)
        assertEquals(original.packageIdentity(), reimported.packageIdentity())
        val copied = editHttpVoice(original, buildJsonObject { put("type", 1) }, true, true)
        val created = newHttpVoice(original.httpEngine()!!)
        val references = speechPackageReferences(listOf(reimported, copied, created), emptyList())
        assertEquals(setOf("http_test_edited_2", "http_test_voice_1", "http_test_speaker_1"), references.keys)
        assertEquals("stable", references.getValue("http_test_edited_2").httpSource)
        assertEquals(copied.definition.id, references.getValue("http_test_voice_1").httpSource)
        assertEquals(created.definition.id, references.getValue("http_test_speaker_1").httpSource)
        val stale = SpeechVoiceRef(httpSource = "stable", externalId = "http_test_voice")
        assertNull(stale.resolvePackageReference(references).httpSource)
        val direct = SpeechVoiceRef(httpSource = "stable")
        assertEquals(direct, direct.resolvePackageReference(references))
        val duplicate = editHttpVoice(copied, buildJsonObject {}, true, true)
        assertNull(speechPackageReferences(listOf(copied, duplicate), emptyList())["http_test_voice_1"])
    }

    @Test fun geminiSampleRateEditKeepsVoiceIdentityButRetiresTheOldProtocolParameters() {
        val original = SavedHttpSpeechSource(previewHttpSpeech("""{
            "id":"stable", "name":"Japanese", "url":"https://gemini.google.com/",
            "multiTts":{"engine":{"code":"gemini","type":"inner"},
                "voice":{"code":"ja","name":"Japanese","type":"1","locale":"ja-JP","sampleRate":"24000"}}
        }""").sources.single(), emptyList())
        val edited = editHttpVoice(original, buildJsonObject { put("sampleRate", "48000") }, enabled = true, copy = false)
        assertEquals(original.definition.id, edited.definition.id)
        assertEquals(original.packageIdentity(), edited.packageIdentity())
        assertFalse(original.sameExecution(edited))
    }
}
