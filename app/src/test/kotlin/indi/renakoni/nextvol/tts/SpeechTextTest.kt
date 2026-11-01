package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun highlightingSentencesDoNotCrossNewlinesOrSplitDecimals() {
        val text = "他说：你好！然后转身。\r\n  Price 3.14 is correct. Next sentence.\n第三段。"
        assertTrue(speechSentences(text).none { it.text.contains('\n') || it.text.endsWith("3.") })
    }

    @Test fun sentenceRangesRetainClosingQuotesAndExactSourceOffsets() {
        val text = "  “你好！”\n他说：再见。\u2028下一句。"
        val sentences = speechSentences(text)
        assertEquals("“你好！”", sentences.first().text)
        assertTrue(sentences.all { it.text == text.substring(it.start, it.end) })
        assertEquals(text.filterNot(Char::isWhitespace), sentences.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }
}
