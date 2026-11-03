package indi.renakoni.nextvol.tts

import hnovel.rules.RegexReplacement
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechDiagnosticsTest {
    @Test fun tracingRecordsActualTwoStageReplacementsAndVoiceWithoutRepeatingRegexExecution() = runBlocking {
        val first = SpeechReplacementRule(name = "structure", source = "甲", target = "“甲”")
        val second = SpeechReplacementRule(name = "normal", source = "甲", target = "乙", regex = true)
        val settings = SpeechSettings(engine = "system", voice = "narrator", replacements = listOf(first, second),
            text = SpeechTextSettings(dialogue = true))
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter", "甲说你好。")
        var executions = 0
        val regex: suspend (String, String, String) -> List<hnovel.rules.ReplacementPart> = { input, source, target ->
            executions++
            RegexReplacement.replace(input, source, target)
        }
        val actual = prepareSpeechPlan(chapter, settings, replaceRegex = regex) {}
        val withoutTrace = executions
        executions = 0
        val records = mutableListOf<SpeechLogRecord>()
        var enabled = true
        val trace = SpeechPlanTrace(records::add, Int::toString, { enabled })
        val traced = prepareSpeechPlan(chapter, settings, replaceRegex = regex, trace = trace) {}
        assertEquals(actual.structuredText, traced.structuredText)
        assertEquals(actual.segments, traced.segments)
        assertEquals(withoutTrace, executions)
        val replacements = records.filter { record -> record.details.any { it.first == R.string.tts_log_replacement_stage } }
        assertEquals(listOf("structure", "normal"), replacements.map { it.subject })
        assertEquals(listOf("1", "2"), replacements.map { it.details.toMap()[R.string.tts_log_replacement_stage] })
        assertEquals(listOf("0..1", "0..1"), replacements.map { it.details.toMap()[R.string.tts_log_range] })
        assertEquals(listOf("甲", "甲"), replacements.map { it.details.toMap()[R.string.tts_log_before] })
        assertEquals(listOf("“甲”", "乙"), replacements.map { it.details.toMap()[R.string.tts_log_after] })
        assertEquals(chapter.text, records.first().details.toMap()[R.string.tts_log_original])
        val segments = records.filter { it.segment != null }
        assertEquals(traced.segments.map { it.text }, segments.map { it.details.toMap()[R.string.tts_log_final_text] })
        assertEquals(traced.segments.map { it.selectedVoice!!.reference }, segments.map { it.voice })
        assertTrue(records.all { it.processId == traced.logId })
        val size = records.size
        enabled = false
        trace.input(chapter)
        assertEquals(size, records.size)
        assertEquals(settings.synthesisSettings(), settings.copy(diagnosticLogging = true).synthesisSettings())
    }
}
