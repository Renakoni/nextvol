package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SpeechPlanTest {
    @Test fun identityPlanResumesUsingOriginalUtf16RangesAndRejectsStaleBookmarks() {
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter",
            "  𠀀说：你好！\n\n" + "长句𠀁".repeat(100), nextId = "next")
        val plan = prepareSpeechPlan(chapter, SpeechSettings()) {}
        assertTrue(plan.segments.size > 2)
        assertTrue(plan.segments.all { it.text == chapter.text.substring(it.start, it.end) && it.text.length <= 200 })
        val second = plan.segments[1]
        val saved = plan.bookmark(second.start + 1)
        assertEquals(1, plan.startIndex(saved))
        assertEquals(0, plan.startIndex(saved.copy(fingerprint = "old text")))
        assertEquals(plan.segments.size, plan.startIndex(plan.bookmark(plan.segments.last().end)))
    }

    @Test fun synthesisIdentityIncludesOnlyTheActiveBackendsParametersAndResourceRevision() {
        val system = SpeechSettings(engine = "system", voice = "voice", rate = 1.2f, pitch = 0.8f)
        assertEquals(system, system.copy(playbackSpeed = 2f).synthesisSettings())
        assertNotEquals(system, system.copy(pitch = 1f).synthesisSettings())
        val http = system.copy(httpSource = "http")
        assertEquals(SpeechSettings(httpSource = "http", rate = 1.2f), http.synthesisSettings())
        assertNotEquals(http.synthesisSettings(), http.copy(httpSource = "other").synthesisSettings())
        val reference = OfflineVoiceRef("a".repeat(64), "b".repeat(64))
        val offline = http.copy(offlineVoice = reference, rate = null)
        val normalized = SpeechSettings(offlineVoice = reference, rate = 1f)
        assertEquals(normalized, offline.synthesisSettings())
        assertEquals(normalized, offline.copy(rate = 1f, pitch = 2f, voice = "unused", playbackSpeed = 2f).synthesisSettings())
        assertNotEquals(normalized, offline.copy(offlineVoice = reference.copy(revision = "c".repeat(64))).synthesisSettings())
        assertNotEquals(normalized, offline.copy(rate = 1.5f).synthesisSettings())
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
