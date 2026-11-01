package indi.renakoni.nextvol.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpeechTextPipelineTest {
    @Test fun emojiUsesPackagedNamesSingletonPriorityAndOriginalCodePointRanges() {
        val input = SpeechMappedText.original("😀👍🏻🇨🇳")
        val output = SpeechEmojiNames.replace(input, {}, Locale.SIMPLIFIED_CHINESE)
        assertEquals("[嘿嘿][拇指向上][较浅肤色][旗: 中国]", output.text)
        assertEquals(0 to 2, output.range(0, 4))
        assertEquals(6 to 10, output.range(output.text.indexOf("[旗"), output.text.length))
        assertEquals("[grinning face]", SpeechEmojiNames.replace(SpeechMappedText.original("😀"), {}, Locale.ENGLISH).text)
    }

    @Test fun filteringHappensBeforeEmojiAndRemainsOptionalAfterReplacement() = runBlocking {
        val chapter = SpeechChapter("book", "one", "", "", "😀")
        val settings = SpeechSettings(text = SpeechTextSettings(replaceEmoji = true))
        assertTrue(prepareSpeechPlan(chapter, settings) {}.segments.isEmpty())
        assertTrue(prepareSpeechPlan(chapter, settings.copy(text = settings.text.copy(filterUnspoken = false))) {}
            .segments.single().text.startsWith("["))
        val punctuation = chapter.copy(text = "正文")
        val replacement = settings.copy(replacements = listOf(SpeechReplacementRule(source = "正文", target = "？！")))
        assertTrue(prepareSpeechPlan(punctuation, replacement) {}.segments.isEmpty())
        assertEquals("？！", prepareSpeechPlan(punctuation,
            replacement.copy(text = replacement.text.copy(filterUnspoken = false))) {}.segments.single().text)
    }

    @Test fun hardBackendLimitPreservesStructureAndChangingSettingsInvalidatesOrdinals() = runBlocking {
        val chapter = SpeechChapter("book", "one", "", "", "“<<人物>>" + "字😀".repeat(30) + "”")
        val settings = SpeechSettings(text = SpeechTextSettings(dialogue = true, filterUnspoken = false, maxSentenceLength = 256))
        val soft = prepareSpeechPlan(chapter, settings) {}
        assertEquals(1, soft.segments.size)
        val hard = prepareSpeechPlan(chapter, settings, maxInputLength = 24) {}
        assertTrue(hard.segments.size > 3)
        assertTrue(hard.segments.all { it.dialogue && it.speakerHint == "人物" && it.text.length <= 24 })
        assertEquals(chapter.text, hard.segments.joinToString("") { it.text })
        assertTrue(hard.segments.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
        val saved = hard.bookmark(hard.segments[1].start, 1)
        assertEquals(1, hard.startIndex(saved))
        assertEquals(0, soft.startIndex(saved))
        val changed = prepareSpeechPlan(chapter, settings.copy(text = settings.text.copy(dialogue = false))) {}
        assertTrue(changed.segments.none { it.dialogue })
        assertEquals(0, changed.startIndex(saved))
    }

    @Test fun localeChangeRebuildsEmojiSegmentsAndResumesAtOriginalOffset() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            val chapter = SpeechChapter("book", "one", "", "", "😀".repeat(10))
            val settings = SpeechSettings(text = SpeechTextSettings(replaceEmoji = true,
                filterUnspoken = false, maxSentenceLength = 32))
            Locale.setDefault(Locale.ENGLISH)
            val english = prepareSpeechPlan(chapter, settings) {}
            val saved = english.bookmark(english.segments[1].start, 1)
            assertEquals(1, english.startIndex(saved))
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val chinese = prepareSpeechPlan(chapter, settings) {}
            assertEquals("[嘿嘿]".repeat(10), chinese.segments.single().text)
            assertEquals(0, chinese.startIndex(saved))
            assertFalse(english.matches(chapter, settings, SpeechContext(), Int.MAX_VALUE))
        } finally { Locale.setDefault(previousLocale) }
    }
}
