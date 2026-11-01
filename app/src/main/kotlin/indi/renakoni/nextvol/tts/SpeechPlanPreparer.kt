package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.FailureCode
import hnovel.rules.RuleStage
import hnovel.rules.ReplacementPart
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Android ICU ignores CharSequence checks; the existing isolated worker enforces a hard deadline. */
class SpeechPlanPreparer @Inject constructor(private val executor: AndroidIsolatedExecutor,
    private val authority: ExecutionAuthority, private val voices: SpeechVoiceCatalog) {
    suspend fun prepare(chapter: SpeechChapter, settings: SpeechSettings, context: SpeechContext = SpeechContext(),
        maxInputLength: Int = Int.MAX_VALUE): SpeechPlan = withRegexWorker { replace ->
        val task = currentCoroutineContext()
        prepareSpeechPlan(chapter, settings, replace, context, maxInputLength, voices.resolve(settings)) { task.ensureActive() }
    }

    suspend fun previewRule(text: String, rule: SpeechReplacementRule): String = withRegexWorker { replace ->
        val task = currentCoroutineContext()
        previewSpeechReplacement(text, rule, replace) { task.ensureActive() }
    }

    private suspend fun <T> withRegexWorker(block: suspend (suspend (String, String, String) -> List<ReplacementPart>) -> T): T {
        val identity = authority.issue("speech-replacements", "builtin", "1", namespace = "tts")
        try {
            return block { input, source, target ->
                when (val result = executor.execute(identity, ExecutionTask.RegexReplacement(input, source, target),
                    ExecutionLimits(timeoutMillis = 2000, maxOutputBytes = 16 * 1024 * 1024, maxRequests = 0))) {
                    is ExecutionResult.Success -> Json.decodeFromString<List<ReplacementPart>>(result.output)
                    is ExecutionResult.Failure -> throw SpeechRegexException(when {
                        result.ruleError?.stage == RuleStage.Parse -> SpeechReplacementProblem.Pattern
                        result.ruleError?.stage == RuleStage.Replace -> SpeechReplacementProblem.Replacement
                        result.code in setOf(FailureCode.Timeout, FailureCode.OutputLimit, FailureCode.InputLimit, FailureCode.ProcessExited) -> SpeechReplacementProblem.Budget
                        else -> throw SpeechException(SpeechError.ServiceUnavailable)
                    })
                }
            }
        } finally { authority.revoke(identity) }
    }
}
