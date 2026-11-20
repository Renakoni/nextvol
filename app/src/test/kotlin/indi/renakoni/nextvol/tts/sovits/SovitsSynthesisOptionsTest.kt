package indi.renakoni.nextvol.tts.sovits

import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.ui.tts.VoiceDraft
import indi.renakoni.nextvol.ui.tts.SovitsOptionsDraft
import indi.renakoni.nextvol.ui.tts.from
import org.junit.Assert.*
import org.junit.Test

class SovitsSynthesisOptionsTest {
    @Test fun readingRateMapsAroundNormalSpeedThenCombinesWithTheVoice() {
        val normal = SovitsSynthesisOptions()
        assertEquals(.6f, normal.withReadingRate(0f).speed, .0001f)
        assertEquals(1f, normal.withReadingRate(1f).speed, .0001f)
        assertEquals(1.65f, normal.withReadingRate(2f).speed, .0001f)
        assertEquals(.96f, normal.copy(speed = 1.2f).withReadingRate(.5f).speed, .0001f)
        assertEquals(.6f, normal.copy(speed = .6f).withReadingRate(0f).speed, .0001f)
        assertEquals(1.65f, normal.copy(speed = 1.65f).withReadingRate(2f).speed, .0001f)
    }

    @Test fun editorAcceptsGenieBoundariesAndRejectsInvalidSamplingValues() {
        val voice = InstalledOfflineVoice(OfflineVoiceRef("voice", "revision"), "Voice", emptyMap(),
            configuration = OfflineVoiceConfig("gpt-sovits", "v2Pro-1",
                parameters = """{"model":"v2Pro.voice"}""", sampleRate = 32000))
        val draft = VoiceDraft.from(voice)
        assertEquals(SovitsSynthesisOptions(.6f, 0f, 1, 0f, 1f),
            draft.copy(speed = ".6", sovits = SovitsOptionsDraft("0", "1", "0", "1")).fields().sovits)
        assertEquals(SovitsSynthesisOptions(1.65f, 1f, 100, 1f, 2f),
            draft.copy(speed = "1.65", sovits = SovitsOptionsDraft("1", "100", "1", "2")).fields().sovits)
        val options = requireNotNull(draft.sovits)
        listOf(
            draft.copy(speed = "0.59"), draft.copy(speed = "1.66"),
            draft.copy(sovits = options.copy(temperature = "NaN")),
            draft.copy(sovits = options.copy(topK = "1.5")),
            draft.copy(sovits = options.copy(topP = "1.01")),
            draft.copy(sovits = options.copy(repetitionPenalty = "0.99")),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid.fields() } }
    }

    @Test fun modelReferenceCannotEscapeItsResourceDirectory() {
        val traversal = OfflineVoiceConfig("gpt-sovits", "v2Pro-1", parameters = """{"model":"v2Pro.../outside"}""")
        assertThrows(IllegalArgumentException::class.java) {
            validateSovitsConfiguration(traversal, listOf("CharacterModels/v2Pro/../outside/prompt_wav.json"))
        }
    }
}
