package indi.renakoni.nextvol.tts.sovits

import hnovel.speech.OfflineVoiceConfig
import org.junit.Assert.*
import org.junit.Test

class SovitsSynthesisOptionsTest {
    @Test fun modelReferenceCannotEscapeItsResourceDirectory() {
        val traversal = OfflineVoiceConfig("gpt-sovits", "v2Pro-1", parameters = """{"model":"v2Pro.../outside"}""")
        assertThrows(IllegalArgumentException::class.java) {
            validateSovitsConfiguration(traversal, listOf("CharacterModels/v2Pro/../outside/prompt_wav.json"))
        }
    }
}
