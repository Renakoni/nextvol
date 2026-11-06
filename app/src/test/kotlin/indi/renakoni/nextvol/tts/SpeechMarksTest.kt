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
        val plan = prepareSpeechPlan(chapter("提示甲\n乙[[SPEED:bad]][[UNKNOWN:3]]丙[[PAUSE: 20 ]]" +
            "[[SPEED:3.5:ignored]][[PITCH:0]][[VOLUME:-1]]丁"), configured) {}
        assertEquals(listOf(120, 500), plan.segments.mapNotNull { it.pauseMs })
        assertEquals("甲\n乙丙丁", plan.segments.joinToString("") { it.text })
        val speech = plan.segments.filter { it.pauseMs == null }
        assertEquals(SpeechAudioParameters(2f, 0.8f, 0.5f), speech.first().audioParameters)
        assertEquals(SpeechAudioParameters(1f, 0.8f, 0.5f), speech[1].audioParameters)
        assertEquals(SpeechAudioParameters(3.5f, 0f, -1f), speech.last().audioParameters)
        assertEquals(0, plan.resumeAt(null).second)
        assertEquals(0, plan.segments.first().start)
        assertEquals(speech[1].end, plan.segments.last { it.pauseMs != null }.end)
        val next = prepareSpeechPlan(chapter("下章").copy(id = "two"), configured, context = plan.nextContext) {}
        assertEquals(SpeechAudioParameters(), next.segments.single().audioParameters)
    }

    @Test fun paragraphPauseSurvivesReplacedAndRemovedNewlines() = runBlocking {
        val input = chapter("甲\n乙\n\n丙")
        val configured = settings.copy(text = settings.text.copy(paragraphPauseMs = 300),
            replacements = listOf(SpeechReplacementRule(source = "\n\n", target = ""),
                SpeechReplacementRule(source = "\n", target = "，")))
        val plan = prepareSpeechPlan(input, configured) {}
        val pauses = plan.segments.filter { it.pauseMs != null }
        assertEquals(listOf(300, 300), pauses.map { it.pauseMs })
        assertTrue(pauses.all { it.start == it.end })
    }
}
