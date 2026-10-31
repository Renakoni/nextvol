package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SpeechPlanTest {
    @Test fun identityPlanResumesUsingOriginalUtf16RangesAndRejectsStaleBookmarks() {
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter",
            "  𠀀说：你好！\n\n" + "长句𠀁".repeat(100), nextId = "next")
        val plan = prepareSpeechPlan(chapter, SpeechSettings()) {}
        assertTrue(plan.segments.size > 2)
        val second = plan.segments[1]
        val saved = plan.bookmark(second.start + 1)
        assertEquals(1, plan.startIndex(saved))
        assertEquals(0, plan.startIndex(saved.copy(fingerprint = "old text")))
        assertEquals(plan.segments.size, plan.startIndex(plan.bookmark(plan.segments.last().end)))
        val finalChapter = prepareSpeechPlan(chapter.copy(nextId = null), SpeechSettings()) {}
        assertEquals(finalChapter.segments.size, finalChapter.startIndex(saved.copy(offset = chapter.text.length)))
    }

    @Test fun systemAndHttpSynthesisSettingsKeepOnlyEffectiveParameters() {
        val system = SpeechSettings(engine = "system", voice = "voice", rate = 1.2f, pitch = 0.8f)
        assertEquals(system, system.copy(playbackSpeed = 2f).synthesisSettings())
        assertNotEquals(system, system.copy(pitch = 1f).synthesisSettings())
        val http = system.copy(httpSource = "http")
        assertEquals(SpeechSettings(httpSource = "http", rate = 1.2f), http.synthesisSettings())
        assertNotEquals(http.synthesisSettings(), http.copy(httpSource = "other").synthesisSettings())
    }

    @Test fun preparationRejectsOversizedChaptersAndCooperatesWithCancellationDuringSegmentation() {
        val oversized = SpeechChapter("book", "chapter", "", "", "字".repeat(1_000_001))
        val failure = assertThrows(SpeechException::class.java) { prepareSpeechPlan(oversized, SpeechSettings()) {} }
        assertEquals(SpeechError.UnsupportedContent, failure.error)
        var checks = 0
        val chapter = oversized.copy(text = "下一句。\n".repeat(1000))
        assertThrows(CancellationException::class.java) {
            prepareSpeechPlan(chapter, SpeechSettings()) { if (++checks == 20) throw CancellationException("Stopped") }
        }
        assertEquals(20, checks)
    }
}
