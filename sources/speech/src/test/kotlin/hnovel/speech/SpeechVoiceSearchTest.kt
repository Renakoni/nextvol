package hnovel.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechVoiceSearchTest {
    private val voice = SpeechSearchVoice("晓晓", "zh-CN-Xiaoxiao", "温柔 女声", "夜间", "zh-CN",
        "style=calm", "slider", gender = 0, type = 1, sampleRate = 24000, selected = true)

    @Test fun plainTermsMatchNameDescriptionAndCodeAndMustAllMatch() {
        for (query in listOf("晓晓", "温柔", "xiaoxiao", "晓晓 女声 xiaoxiao"))
            assertTrue(query, SpeechVoiceSearch(query).matches(voice))
        for (query in listOf("晓晓 男声", "夜间", "calm"))
            assertFalse(query, SpeechVoiceSearch(query).matches(voice))
    }

    @Test fun fieldTermsUseSpeakerMetadataAndIncompleteNumbersDoNotCrash() {
        assertTrue(SpeechVoiceSearch("desc:温柔 note:夜间 locale:cn code:xiao gender:female type:online " +
            "sampleRate:24k param:calm extendUI:slider ischecked:true").matches(voice))
        for (query in listOf("desc:晓晓", "gender:male", "type:offline", "ischecked:false", "samplerate:", "samplerate:bad"))
            assertFalse(query, SpeechVoiceSearch(query).matches(voice))
        assertTrue(SpeechVoiceSearch("unknown:word").matches(voice.copy(description = "unknownword")))
    }
}
