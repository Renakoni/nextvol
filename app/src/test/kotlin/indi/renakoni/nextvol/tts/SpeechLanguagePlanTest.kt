package indi.renakoni.nextvol.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechLanguagePlanTest {
    private val narrator = SpeechVoiceRef(httpSource = "narrator")
    private val english = SpeechVoiceRef(httpSource = "english")
    private val person = SpeechVoiceRef(httpSource = "person")
    private val languageRule = SpeechRoleRule(id = "english", name = "English", mode = 1,
        regex = "lang:en", speaker = english)
    private val settings = SpeechSettings(voiceRoutingConfigured = true,
        narrationVoices = listOf(narrator), dialogueVoices = listOf(narrator), roleRules = listOf(languageRule),
        text = SpeechTextSettings(dialogue = true, separateEnglish = false, detectLanguage = true, maxSentenceLength = 256))

    @Test fun languageVoiceOverridesPersonRuleAndRetainsIdentityAndOriginalPositions() = runBlocking {
        val configured = settings.copy(roleRules = settings.roleRules + SpeechRoleRule(name = "甲", speaker = person),
            replacements = listOf(SpeechReplacementRule(source = "Hello", target = "Welcome")))
        val text = "“<<甲>>Hello!Again!”"
        val plan = prepareSpeechPlan(SpeechChapter("book", "one", "", "", text), configured,
            detectLanguage = { _, _ -> "en" }) {}
        val segment = plan.segments.single()
        assertEquals("甲", segment.speaker)
        assertEquals("lang:en", segment.language)
        assertEquals("english", segment.roleMatch?.ruleId)
        assertEquals(english.key, segment.selectedVoice?.reference?.key)
        assertEquals(SpeechVoiceReason.Language, segment.voiceReason)
        assertTrue(segment.text.contains("Welcome"))
        val offset = segment.text.indexOf("Welcome")
        val position = plan.position(0, offset, offset + "Welcome".length)
        assertEquals("Hello", text.substring(position.start, position.end))
        assertEquals(-1, plan.nextContext.dialogueIndex)
    }

    @Test fun textRulesAndExplicitBookAssignmentsBypassLanguageDetection() = runBlocking {
        val text = "固定句。"
        val configured = settings.copy(roleRules = settings.roleRules + SpeechRoleRule(name = text, mode = 0, speaker = person))
        val mustNotDetect: suspend (String, Set<String>) -> String? = { _, _ -> error("Explicit voice already selected") }
        val textPlan = prepareSpeechPlan(SpeechChapter("book", "one", "", "", text), configured,
            detectLanguage = mustNotDetect) {}
        assertEquals(person.key, textPlan.segments.single().selectedVoice?.reference?.key)
        val manual = settings.copy(speakerVoices = mapOf("甲" to person), speakerBookId = "book")
        val personPlan = prepareSpeechPlan(SpeechChapter("book", "two", "", "", "“<<甲>>Hello!”"), manual,
            detectLanguage = mustNotDetect) {}
        assertEquals(person.key, personPlan.segments.single().selectedVoice?.reference?.key)
        assertNull(personPlan.segments.single().language)
    }

    @Test fun unrecognizedLanguageUsesExistingNarratorButUnavailableRuleVoiceStaysVisible() = runBlocking {
        val voices = mapOf(english.key to SpeechResolvedVoice(english, settings.forVoice(english), 256,
            error = SpeechError.HttpSourceUnavailable))
        val plan = prepareSpeechPlan(SpeechChapter("book", "one", "", "", "Unknown!Hello!"), settings,
            voices = voices, detectLanguage = { text, _ -> if (text.startsWith("Hello")) "en" else null }) {}
        assertEquals(listOf(narrator.key, english.key), plan.segments.map { it.selectedVoice?.reference?.key })
        assertEquals(SpeechError.HttpSourceUnavailable, plan.segments.last().selectedVoice?.error)
        assertEquals("english", plan.segments.last().roleMatch?.ruleId)
        assertEquals(0, plan.nextContext.narrationIndex)
    }
}
