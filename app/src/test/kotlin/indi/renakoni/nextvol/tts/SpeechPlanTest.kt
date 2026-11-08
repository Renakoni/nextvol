package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpeechPlanTest {
    @Test fun identityPlanResumesUsingOriginalUtf16RangesAndRejectsStaleBookmarks() = runBlocking {
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter",
            "  𠀀说：你好！\n\n" + "长句𠀁".repeat(100), nextId = "next")
        val plan = prepareSpeechPlan(chapter, SpeechSettings()) {}
        assertTrue(plan.segments.size > 2)
        val second = plan.segments[1]
        val saved = plan.bookmark(second.start + 1)
        assertEquals(1, plan.resumeAt(saved).second)
        assertEquals(0, plan.resumeAt(saved.copy(fingerprint = "old text")).second)
        assertEquals(plan.segments.size, plan.resumeAt(plan.bookmark(plan.segments.last().end)).second)
        val finalChapter = prepareSpeechPlan(chapter.copy(nextId = null), SpeechSettings()) {}
        assertEquals(finalChapter.segments.size, finalChapter.resumeAt(saved.copy(offset = chapter.text.length)).second)
    }

    @Test fun systemAndHttpSynthesisSettingsKeepOnlyEffectiveParameters() {
        val system = SpeechSettings(engine = "system", voice = "voice", rate = 1.2f, pitch = 0.8f)
        assertNotEquals(system, system.copy(volume = 0.5f).synthesisSettings())
        assertNotEquals(system, system.copy(pitch = 1f).synthesisSettings())
        val http = system.copy(httpSource = "http")
        assertEquals(SpeechSettings(httpSource = "http", rate = 1.2f, pitch = 0.8f), http.synthesisSettings())
        assertNotEquals(http.synthesisSettings(), http.copy(httpSource = "other").synthesisSettings())
    }

    @Test fun bookmarkTextIdentityKeepsItsV5EncodingWhilePlanReuseStillRequiresFullSynthesis() = runBlocking {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val settings = SpeechSettings(schemeId = "scheme", engine = "system", voice = "base",
                narrationVoices = listOf(SpeechVoiceRef(engine = "system", voice = "narrator")),
                dialogueVoices = listOf(SpeechVoiceRef(httpSource = "dialogue")),
                speakerVoices = mapOf("Alice" to SpeechVoiceRef(httpSource = "alice")), speakerBookId = "book",
                voiceRevision = "voices-v1", voiceRoutingConfigured = true, roleContextPattern = "__ROLE__",
                maxCacheVoiceNum = 7, text = SpeechTextSettings(dialogue = true, recordVoice = true, recordText = true,
                    maleNameRegex = "", femaleNameRegex = "", maxSentenceLength = 64)).synthesisSettings()
            val chapter = SpeechChapter("book", "chapter", "Book", "Chapter", "A short paragraph.")
            val plan = prepareSpeechPlan(chapter, settings, maxInputLength = 128) {}
            // Fixed pre-extraction v5 encoding, including JSON field order and the en-US input locale.
            val key = "5a20f45680e314e2759f2114a8ef4401927949ff4219d6d712a747281208b565"
            assertEquals(key, plan.bookmark(0).replacementKey)
            val displayEdit = settings.copy(schemeName = "Renamed", diagnosticLogging = true, allowMultipleVoices = true,
                backgroundMusic = SpeechSchemeMusic(true, setOf("track")), text = settings.text.copy(filterLanguage = true))
                .synthesisSettings()
            assertTrue(plan.matches(chapter, displayEdit, SpeechContext(), 128))
            val faster = settings.copy(rate = 1.5f).synthesisSettings()
            assertEquals(key, faster.textProcessingKey(128))
            assertFalse(plan.matches(chapter, faster, SpeechContext(), 128))
            assertNotEquals(key, settings.copy(voiceRevision = "voices-v2").textProcessingKey(128))
        } finally { Locale.setDefault(previousLocale) }
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
