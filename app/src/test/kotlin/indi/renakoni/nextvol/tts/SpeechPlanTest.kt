package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechPlanTest {
    @Test fun identityPlanResumesUsingOriginalUtf16RangesAndRejectsStaleBookmarks() = runBlocking {
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter",
            "  𠀀说：你好！\n\n" + "长句𠀁".repeat(100), nextId = "next")
        val plan = prepareSpeechPlan(chapter, SpeechSettings()) {}
        val second = plan.segments[1]
        val saved = plan.bookmark(second.start + 1)
        assertEquals(1, plan.resumeAt(saved).index)
        assertEquals(0, plan.resumeAt(saved.copy(fingerprint = "old text")).index)
        assertEquals(plan.segments.size, plan.resumeAt(plan.bookmark(plan.segments.last().end)).index)
    }

    @Test fun sovitsResumeOnClosingPunctuationAdvancesPastTheSilentSuffix() = runBlocking {
        val settings = SpeechSettings()
        val voice = settings.voiceReference()
        val chapter = SpeechChapter("book", "chapter", "", "", "“走吧！”")
        val plan = prepareSpeechPlan(chapter, settings, voices = mapOf(
            voice.key to SpeechResolvedVoice(voice, settings.forVoice(voice), 120, sovitsSpeed = 1f))) {}
        val segment = plan.segments.single()
        assertEquals(SpeechCursor(1), plan.resumeAt(plan.bookmark(chapter.text.indexOf('！'), 0, segment.text.indexOf('！'))))
    }

    @Test fun preparationRejectsOversizedChaptersAndCooperatesWithCancellationDuringSegmentation() {
        val oversized = SpeechChapter("book", "chapter", "", "", "字".repeat(1_000_001))
        val failure = assertThrows(SpeechException::class.java) { runBlocking { prepareSpeechPlan(oversized, SpeechSettings()) {} } }
        assertEquals(SpeechError.UnsupportedContent, failure.error)
        var checks = 0
        val chapter = oversized.copy(text = "下一句。\n".repeat(1000))
        assertThrows(CancellationException::class.java) {
            runBlocking { prepareSpeechPlan(chapter, SpeechSettings()) { if (++checks == 20) throw CancellationException("Stopped") } }
        }
        assertEquals(20, checks)
    }
}
