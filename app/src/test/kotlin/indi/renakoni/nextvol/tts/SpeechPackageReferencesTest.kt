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
        assertTrue(imported.sources.single().hasScripts)
    }

    @Test fun missingReferencesSurviveRestartAndBindByOriginalIdentityAfterVoiceEdits() {
        val missing = SpeechVoiceRef(externalId = "audio_sound_1", label = "Original name")
        val restored = Json.decodeFromString<SpeechVoiceRef>(Json.encodeToString(missing))
        assertEquals(missing, restored.resolvePackageReference(emptyMap()))
        val original = OfflineVoiceConfig("audio", "sound", type = 1)
        val voice = InstalledOfflineVoice(OfflineVoiceRef("stable", "revision"), "User name", emptyMap(),
            sourceKey = "audio\u0000sound", configuration = original.copy(code = "edited"),
            defaults = OfflineVoiceDefaults("Original name", original, OfflineVoiceMetadata()))
        val references = speechPackageReferences(emptyList(), listOf(voice))
        val bound = restored.resolvePackageReference(references)
        assertEquals(restored.key, bound.key)
        assertEquals(voice.reference, bound.offlineVoice)
        assertEquals("User name", bound.label)
        assertNull(references["audio_sound"])
    }

    @Test fun matchingNamesAndUserCopiesDoNotReplaceTheOriginalOnlineVoice() {
        val original = SavedHttpSpeechSource(previewHttpSpeech("""{
            "id":"stable", "name":"Same name", "url":"https://example.invalid/speech",
            "multiTts":{"engine":{"code":"http_test","type":"http"},"voice":{"code":"voice","type":"0"}}
        }""").sources.single(), emptyList())
        val references = speechPackageReferences(listOf(original, original.copy(isVoiceCopy = true)), emptyList())
        assertEquals("stable", references["http_test_voice"]!!.httpSource)
        assertEquals(1, references.size)
    }
}
