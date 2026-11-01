package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechVoiceSelectionTest {
    private val first = SpeechVoiceRef(httpSource = "first")
    private val second = SpeechVoiceRef(httpSource = "second")
    private val third = SpeechVoiceRef(httpSource = "third")

    @Test fun singleSelectionTogglesAndChangingCategoryFirstRemovesTheOldAssignment() {
        var settings = SpeechSettings(httpSource = "first")
        assertFalse(settings.allowMultipleVoices)
        assertEquals(SpeechVoiceSelection.Narration, settings.voiceSelection(first))
        settings = settings.toggleVoice(false, first)
        assertTrue(settings.voiceRoutingConfigured)
        assertTrue(settings.narrationVoices.isEmpty())
        settings = settings.toggleVoice(false, first).toggleVoice(false, second)
        assertEquals(listOf(second), settings.narrationVoices)
        settings = settings.toggleVoice(true, second)
        assertTrue(settings.narrationVoices.isEmpty())
        assertTrue(settings.dialogueVoices.isEmpty())
        settings = settings.toggleVoice(true, second).toggleVoice(true, first)
        assertEquals(listOf(first), settings.dialogueVoices)
        settings = settings.toggleVoice(false, first)
        assertTrue(settings.dialogueVoices.isEmpty())
        assertTrue(settings.narrationVoices.isEmpty())
    }

    @Test fun multipleSelectionIsIndependentForEachCategoryAndDisablingItDoesNotPruneSelections() {
        var settings = SpeechSettings(voiceRoutingConfigured = true, allowMultipleVoices = true)
            .toggleVoice(false, first).toggleVoice(false, second).toggleVoice(true, third)
        assertEquals(listOf(first, second), settings.narrationVoices)
        assertEquals(listOf(third), settings.dialogueVoices)
        val synthesis = settings.synthesisSettings()
        settings = settings.copy(allowMultipleVoices = false)
        assertEquals(synthesis, settings.synthesisSettings())
        assertEquals(listOf(first, second), settings.narrationVoices)
        settings = settings.toggleVoice(false, first)
        assertEquals(listOf(second), settings.narrationVoices)
        settings = settings.toggleVoice(false, first)
        assertEquals(listOf(first), settings.narrationVoices)
        settings = settings.toggleVoice(true, third)
        assertTrue(settings.dialogueVoices.isEmpty())
    }
}
