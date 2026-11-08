package hnovel.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineSpeechTextTest {
    @Test fun sentenceAggregationRetainsPunctuationAndSupplementaryCharacters() {
        assertEquals(listOf("你好。世界。", "下一句。"), splitOnlineSpeechText("你好。世界。下一句。", 6))
        // S6.f.d/c cut at start+1 or a UTF-16 limit; neither may discard half a surrogate pair.
        assertEquals(listOf("123😀", "“12", "😀34", "5678", "9"),
            splitOnlineSpeechText("123😀“12😀3456789", 4))
    }
}
