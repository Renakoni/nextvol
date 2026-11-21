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
        assertTrue(plan.segments.size > 2)
        val second = plan.segments[1]
        val saved = plan.bookmark(second.start + 1)
        assertEquals(1, plan.resumeAt(saved).index)
        assertEquals(0, plan.resumeAt(saved.copy(fingerprint = "old text")).index)
        assertEquals(plan.segments.size, plan.resumeAt(plan.bookmark(plan.segments.last().end)).index)
        val finalChapter = prepareSpeechPlan(chapter.copy(nextId = null), SpeechSettings()) {}
        assertEquals(finalChapter.segments.size, finalChapter.resumeAt(saved.copy(offset = chapter.text.length)).index)
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
