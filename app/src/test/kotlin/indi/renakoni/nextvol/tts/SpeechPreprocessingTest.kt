package indi.renakoni.nextvol.tts

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SpeechPreprocessingTest {
    @Test fun normalizationAndTrimKeepOriginalCodePointRanges() {
        val input = SpeechMappedText.original(" \te\u0301\u1100\u1161 𠀀\n")
        val normalized = speechNormalize(input)
        assertEquals("é가 𠀀", normalized.text)
        assertEquals(2 to 4, normalized.range(0, 1))
        assertEquals(4 to 6, normalized.range(1, 2))
        assertEquals(7 to 9, normalized.range(3, 5))
        assertEquals(2 to 9, normalized.range())
        assertEquals("\u00a0a\u00a0", speechTrim(SpeechMappedText.original(" \u00a0a\u00a0 ")).text)
    }

    @Test fun unspokenMatchesOriginalCategoriesIncludingSymbolsButNotCombiningMarks() {
        assertTrue(speechIsUnspoken(" \u0000！？￥😀"))
        assertTrue(speechIsUnspoken(""))
        assertFalse(speechIsUnspoken("\u0301"))
        assertFalse(speechIsUnspoken("1"))
        assertFalse(speechIsUnspoken("你好😀"))
    }

    @Test fun latinCandidatesIncludeAccentsAndAdjacentPunctuationAndExcludeControlMarks() {
        val input = SpeechMappedText.original("中，café 42！文[[English voice]] end")
        val parts = speechLatinSegments(input, true)
        assertEquals(listOf("中", "，café 42！", "文[[English voice]]", " end"),
            parts.map { it.content.text })
        assertEquals(listOf(null, "lang:latin", null, "lang:latin"), parts.map { it.language })
        assertEquals(input.text, parts.joinToString("") { it.content.text })
        assertTrue(parts.all { it.content.text == input.text.substring(it.content.range().first, it.content.range().second) })
        assertTrue(speechLatinSegments(input, false).all { it.language == null })
        assertNull(speechLatinSegments(SpeechMappedText.original("123 !"), true).single().language)
        val marked = SpeechMappedText.original("“<<Alice>>崇庆”")
        assertEquals(marked.text, speechLatinSegments(marked, true).single().content.text)
        assertEquals(marked.text, speechLatinSegments(marked, false).single().content.text)
    }

    @Test fun sentenceTargetIsSoftUntilTwiceItsLengthAndBackendLimitRemainsHard() {
        val input = SpeechMappedText.original("甲".repeat(9))
        val soft = speechSentenceChunks(input, 5, Locale.CHINESE)
        assertEquals(listOf(input.text), soft.map { it.text })
        assertEquals(listOf(5, 4), soft.flatMap { speechHardChunks(it, 5) }.map { it.text.length })
        assertEquals(listOf(5, 5), speechSentenceChunks(
            SpeechMappedText.original("甲".repeat(10)), 5, Locale.CHINESE).map { it.text.length })
        assertEquals(listOf("甲乙。丙丁。", "戊己。"), speechSentenceChunks(
            SpeechMappedText.original("甲乙。丙丁。戊己。"), 6, Locale.CHINESE).map { it.text })
    }

    @Test fun longWhitespaceGroupingUsesOriginalRangesWithoutAddingSpaces() {
        val input = SpeechMappedText.original("alpha  beta gamma")
        val chunks = speechSentenceChunks(input, 6, Locale.ENGLISH)
        assertEquals(listOf("alpha", "beta", "gamma"), chunks.map { it.text })
        assertEquals(7 to 11, chunks[1].range(0, 4))
    }

    @Test fun decimalPointIsNotASentenceBoundaryButSentenceFinalDigitCanEndOne() {
        val text = "甲".repeat(58) + "3.14万元。"
        assertEquals(listOf(text), speechSentenceChunks(SpeechMappedText.original(text), 60, Locale.CHINESE).map { it.text })
        assertEquals(listOf("编号是3.", " 下一句"), speechSentenceChunks(
            SpeechMappedText.original("编号是3. 下一句"), 6, Locale.CHINESE).map { it.text })
    }

    @Test fun fullLengthWordsKeepTheirOriginalRangesAndLongWordsStillRespectHardLimit() {
        val text = "a".repeat(32) + " " + "b".repeat(32)
        val chunks = speechSentenceChunks(SpeechMappedText.original(text), 32, Locale.ENGLISH)
        assertEquals(listOf("a".repeat(32), "b".repeat(32)), chunks.map { it.text })
        assertEquals(listOf(0 to 32, 33 to 65), chunks.map { it.range() })
        val longWord = SpeechMappedText.original("a".repeat(65))
        val hard = speechHardChunks(longWord, 32)
        assertEquals(listOf(32, 32, 1), hard.map { it.text.length })
        assertEquals(longWord.text, hard.joinToString("") { it.text })
    }

    @Test fun sentenceAndHardBoundariesNeverSplitMarkersOrSurrogatePairs() {
        val marked = "<<甲。" + "乙".repeat(60) + ">>"
        assertEquals(listOf(marked), speechSentenceChunks(SpeechMappedText.original(marked), 60, Locale.CHINESE).map { it.text })
        val text = "ab<<角色>>cd[[en]]𠀀x"
        val chunks = speechHardChunks(SpeechMappedText.original(text), 6)
        assertEquals(listOf("ab", "<<角色>>", "cd", "[[en]]", "𠀀x"), chunks.map { it.text })
        assertEquals(text, chunks.joinToString("") { it.text })
        val quoted = "a𠀀“甲乙丙丁”"
        val sentences = speechSentenceChunks(SpeechMappedText.original(quoted), 3, Locale.CHINESE)
        assertEquals(quoted, sentences.joinToString("") { it.text })
        assertTrue(sentences.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
        assertEquals(listOf("A", "<<人物姓名>>", "B"), speechSentenceChunks(
            SpeechMappedText.original("A<<人物姓名>>B"), 4, Locale.CHINESE).map { it.text })
    }

    @Test fun markerLongerThanBackendLimitFailsWithoutSplittingOrLooping() {
        val error = assertThrows(SpeechException::class.java) {
            speechHardChunks(SpeechMappedText.original("a[[long marker]]b"), 6)
        }
        assertEquals(SpeechError.UnsupportedContent, error.error)
    }
}
