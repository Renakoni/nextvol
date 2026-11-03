package indi.renakoni.nextvol.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechMarksTest {
    private val settings = SpeechSettings(text = SpeechTextSettings(separateEnglish = false))
    private fun chapter(text: String) = SpeechChapter("book", "one", "", "", text)

    @Test fun fullTextRulesInsertMarksAndMultipliersPersistUntilReplacedWithinTheChapter() = runBlocking {
        val configured = settings.copy(replacements = listOf(SpeechReplacementRule(source = "提示",
            target = "[[SPEED:1.5]][[SPEED:2]][[PITCH:0.8]][[VOLUME:0.5]][[PAUSE:120]]")))
        val plan = prepareSpeechPlan(chapter("提示甲\n乙[[SPEED:bad]][[UNKNOWN:3]]丙[[PAUSE]]"), configured) {}
        assertEquals(listOf(120, 500), plan.segments.mapNotNull { it.pauseMs })
        assertEquals("甲\n乙丙", plan.segments.joinToString("") { it.text })
        val speech = plan.segments.filter { it.pauseMs == null }
        assertEquals(SpeechAudioParameters(2f, 0.8f, 0.5f), speech.first().audioParameters)
        assertEquals(SpeechAudioParameters(1f, 0.8f, 0.5f), speech.last().audioParameters)
        assertEquals(0, plan.startIndex(null))
        assertEquals(0, plan.segments.first().start)
        assertEquals(speech.last().end, plan.segments.last().end)
        val next = prepareSpeechPlan(chapter("下章").copy(id = "two"), configured, context = plan.nextContext) {}
        assertEquals(SpeechAudioParameters(), next.segments.single().audioParameters)
    }

    @Test fun originalParsingDefaultsAndNumericValuesReachTheExistingProcessorWithoutClamping() {
        val marks = SpeechMarks()
        assertEquals(500, marks.accept("[[ pause : 20 ]]")) // Integer parsing does not trim the value.
        assertEquals(0, marks.accept("[[PAUSE:0]]"))
        assertNull(marks.accept("[[not-a-command]]"))
        marks.accept("[[SPEED:3.5:ignored]]")
        marks.accept("[[PITCH:0]]")
        marks.accept("[[VOLUME:-1]]")
        assertEquals(SpeechAudioParameters(3.5f, 0f, -1f), marks.parameters)
        marks.accept("[[PITCH]]")
        assertEquals(1f, marks.parameters.pitch)
        val effective = SpeechAudioParameters(1.2f, 1.1f, 0.8f) * SpeechAudioParameters(1.5f, 1f, 0.5f)
        assertEquals(1.8f, effective.speed, 0.000001f)
        assertEquals(1.1f, effective.pitch, 0f)
        assertEquals(0.4f, effective.volume, 0f)
    }

    @Test fun paragraphPauseUsesOriginalBoundariesDespiteSplitsRemovedNewlinesAndEmptyLines() = runBlocking {
        val input = chapter("甲".repeat(80) + "\n\n\n乙\n\n丙")
        val configured = settings.copy(text = settings.text.copy(paragraphPauseMs = 300),
            replacements = listOf(SpeechReplacementRule(source = "\n", target = "")))
        val plan = prepareSpeechPlan(input, configured, maxInputLength = 16) {}
        val pauses = plan.segments.filter { it.pauseMs != null }
        assertEquals(listOf(300, 300), pauses.map { it.pauseMs })
        assertEquals(listOf(80, 84), pauses.map { it.end })
        assertTrue(pauses.all { it.start == it.end })
        assertTrue(plan.segments.filter { it.pauseMs == null }.all { it.text.length <= 16 })
        val off = prepareSpeechPlan(input, configured.copy(text = settings.text), maxInputLength = 16) {}
        assertTrue(off.segments.none { it.pauseMs != null })
    }

    @Test fun negativePauseReportsAudioFailure() {
        val failure = assertThrows(SpeechException::class.java) {
            runBlocking { prepareSpeechPlan(chapter("[[PAUSE:-20]]甲"), settings) {} }
        }
        assertEquals(SpeechError.AudioProcessing, failure.error)
    }
}
