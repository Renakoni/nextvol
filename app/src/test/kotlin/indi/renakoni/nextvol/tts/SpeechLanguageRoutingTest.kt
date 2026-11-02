package indi.renakoni.nextvol.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpeechLanguageRoutingTest {
    private fun rule(code: String, id: String = code) = SpeechRoleRule(id = id, name = id,
        regex = "lang:$code", mode = 1, speaker = SpeechVoiceRef(httpSource = id))

    @Test fun languageRulesUseLastEnabledExactThenLatinAndBaseMatches() = runBlocking {
        val rules = listOf(rule("en", "old"), rule("en", "english"),
            rule("en", "disabled").copy(activate = false), rule("zh"), rule("zh-TW"),
            rule("ja-Latn").copy(mode = 2), rule("de").copy(mode = 0))
        val routing = SpeechLanguageRouting(rules, true) { text, candidates ->
            assertEquals(setOf("en", "zh", "zh-TW", "ja-Latn"), candidates)
            text
        }
        assertEquals("english", routing.route("en")?.role?.ruleId)
        assertEquals("zh-TW", routing.route("zh-TW")?.role?.ruleId)
        assertEquals("zh", routing.route("zh-CN")?.role?.ruleId)
        assertEquals("ja-Latn", routing.route("ja-Latn")?.role?.ruleId)
        val latin = routing.route("ru-Latn")!!
        assertEquals("english", latin.role?.ruleId)
        assertEquals("default", latin.role?.person)
        val unmatched = routing.route("fr")
        assertEquals("fr", unmatched?.language)
        assertNull(unmatched?.role)
    }

    @Test fun disabledAndEmptyRulesSkipDetection() = runBlocking {
        val mustNotDetect: suspend (String, Set<String>) -> String? = { _, _ -> error("Detector must stay unloaded") }
        assertNull(SpeechLanguageRouting(listOf(rule("en")), false, mustNotDetect).route("Hello"))
        assertNull(SpeechLanguageRouting(emptyList(), true, mustNotDetect).route("Hello"))
    }

    @Test fun detectionUnitsCoalesceWithoutLosingPunctuationOrOriginalOffsets() = runBlocking {
        val calls = mutableListOf<String>()
        val routing = SpeechLanguageRouting(listOf(rule("en"), rule("zh-CN")), true) { text, _ ->
            calls += text
            if (text.first().isAsciiLetter()) "en" else "zh-CN"
        }
        val text = "!Hello!Again!中文。接着说。😀😀"
        val groups = routing.split(SpeechMappedText.original(text), Locale.CHINA) {}
        assertEquals(listOf("Hello!", "Again!", "中文。", "接着说。", "😀😀"), calls)
        assertEquals(listOf("!Hello!Again!", "中文。接着说。😀😀"), groups.map { it.first.text })
        assertEquals(listOf("en", "zh-CN"), groups.map { it.second?.language })
        assertEquals(0 to 13, groups.first().first.range())
        assertEquals(13 to text.length, groups.last().first.range())
    }

    @Test fun candidatesAndVoiceFilterFollowTheSystemLocale() {
        assertEquals(setOf("en", "zh-CN"), optimaizeLanguageCandidates(setOf("unsupported"), Locale.US))
        assertEquals(setOf("ja", "zh-TW"), optimaizeLanguageCandidates(setOf("ja"), Locale.TAIWAN))
        val mediaPipe = mediaPipeLanguageCandidates(setOf("en", "ja"), Locale.CHINA)
        assertTrue(mediaPipe.containsAll(listOf("en", "ja", "zh", "ru-Latn", "zh-Latn")))
        assertTrue(speechVoiceLanguageMatches("en-GB", Locale.CHINA))
        assertTrue(speechVoiceLanguageMatches("zh-TW", Locale.CHINA))
        assertTrue(speechVoiceLanguageMatches("fr-CA", Locale.CANADA))
        assertTrue(speechVoiceLanguageMatches("", Locale.CHINA))
        assertFalse(speechVoiceLanguageMatches("ja-JP", Locale.CHINA))
    }

    private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'
}
