package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SPEECH_POOL_FEMALE
import indi.renakoni.nextvol.tts.SPEECH_POOL_MALE
import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechVoiceItem
import indi.renakoni.nextvol.tts.SpeechVoicePair
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoicePoolSearchTest {
    private val voice = SpeechVoiceRef(httpSource = "voice-01", label = "Alice")
    private val label: (String) -> String = { if (it == SPEECH_POOL_FEMALE) "失配女" else it }

    @Test fun poolGroupsAndSearchUseOriginalLabelsWithoutDuplicatingDefaultPools() {
        val settings = SpeechSettings(roleRules = listOf(SpeechRoleRule(name = "失配女", isAbstract = true)))
        assertEquals(listOf(SPEECH_POOL_MALE, SPEECH_POOL_FEMALE), settings.poolGroups())
        val first = SpeechVoiceItem(SPEECH_POOL_FEMALE, voice)
        val second = first.copy(id = "second", activate = false, name = "Bob")
        val items = listOf(first, second)
        assertEquals(listOf(first), filterSpeechVoicePool(items, " group: 失配女\tvoice:ALICE ischecked:true ", label))
        assertEquals(listOf(second), filterSpeechVoicePool(items, "ischecked:other", label))
        assertEquals(items, filterSpeechVoicePool(items, "voice:voice-01", label))
        assertTrue(filterSpeechVoicePool(items, "voice-01", label).isEmpty())
    }

    @Test fun assignmentSearchSeparatesPersonVoiceAndAbstractRole() {
        val pair = SpeechVoicePair("book", "小明", SPEECH_POOL_FEMALE, voice)
        assertEquals(listOf(pair), filterSpeechVoicePairs(listOf(pair), "小明 group:失配女 voice:alice", label))
        assertTrue(filterSpeechVoicePairs(listOf(pair), "失配女", label).isEmpty())
        assertEquals(listOf(pair), filterSpeechVoicePairs(listOf(pair), "voice:voice-01", label))
    }
}
