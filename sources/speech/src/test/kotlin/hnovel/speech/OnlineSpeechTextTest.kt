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

    @Test fun guaguaTargetPreservesDecimalsWordsAndCompleteMarkers() {
        val decimal = "甲".repeat(48) + "3.14万元。"
        assertEquals(listOf(decimal), splitOnlineSpeechText(decimal, 50))
        assertEquals(listOf("编号是3.", " 下一句"), splitOnlineSpeechText("编号是3. 下一句", 6))
        val word = "a".repeat(50)
        assertEquals(listOf(word, word), splitOnlineSpeechText("$word $word", 50))
        val marked = "<<甲。" + "乙".repeat(60) + ">>"
        assertEquals(listOf(marked), splitOnlineSpeechText(marked, 50))
        val longMark = "[[甲。 " + "𠀀".repeat(50) + "]]"
        assertEquals(listOf("前", longMark, "后"), splitOnlineSpeechText("前${longMark}后", 50))
        val longText = "a".repeat(94) + " ……！？”』"
        val nextSentence = "乙".repeat(29) + "。"
        assertEquals(listOf("a".repeat(50), "a".repeat(43), "a ……！？”』", nextSentence),
            splitOnlineSpeechText(longText + nextSentence, 50))
    }
}
