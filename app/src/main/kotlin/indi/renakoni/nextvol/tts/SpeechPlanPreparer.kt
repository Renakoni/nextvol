package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.FailureCode
import hnovel.rules.RuleStage
import hnovel.rules.ReplacementPart
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import indi.renakoni.nextvol.tts.names.SpeechNameRecognizer
import indi.renakoni.nextvol.tts.names.SpeechNameClassifier
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
import indi.renakoni.nextvol.tts.names.SpeechNewWordSession
import indi.renakoni.nextvol.tts.names.SpeechNewWordScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Android ICU ignores CharSequence checks; the existing isolated worker enforces a hard deadline. */
class SpeechPlanPreparer @Inject constructor(private val executor: AndroidIsolatedExecutor,
    private val authority: ExecutionAuthority, private val voices: SpeechVoiceCatalog,
    private val pairs: SpeechVoicePoolRepository, private val recognizer: SpeechNameRecognizer,
    private val discovery: SpeechNewWordDiscovery, private val classifier: SpeechNameClassifier) {
    private var discoveryKey: String? = null
    private var discoverySession: SpeechNewWordSession? = null

    fun reset() { discoveryKey = null; discoverySession = null }

    suspend fun prepare(chapter: SpeechChapter, settings: SpeechSettings, context: SpeechContext = SpeechContext(),
        maxInputLength: Int = Int.MAX_VALUE): SpeechPlan = withRegexWorker { replace ->
        withContext(Dispatchers.Default) {
            val task = currentCoroutineContext()
            val preview = chapter.bookId.isEmpty()
            val pairRevision = pairs.revisions.value
            val saved = if (preview) mutableListOf() else pairs.pairs(chapter.bookId).toMutableList()
            val catalog = voices.resolve(settings, saved)
            val previewPool = if (preview) SpeechVoicePool() else null
            suspend fun existing(name: String, groups: List<String>): SpeechVoicePair? =
                if (previewPool != null) previewPool.existing(chapter.bookId, name, groups, saved, catalog, settings.maxCacheVoiceNum)
                else pairs.existing(chapter.bookId, name, groups, saved, pairRevision, catalog, settings.maxCacheVoiceNum)
            val key = chapter.bookId + "\u0000" + chapter.id + "\u0000" + settings.textProcessingKey(maxInputLength)
            if (!preview && (discoveryKey != key || !settings.autoAssignRole || settings.text.nameDiscoveryAlgorithm != 100)) {
                reset()
                discoveryKey = key
            }
            var session = if (preview) null else discoverySession
            val characters = if (settings.hasVoiceRouting || saved.isNotEmpty()) SpeechCharacterAssignment(settings, chapter, saved, catalog,
                recognize = { input -> recognizer.recognize(input).map { it.name } },
                newWords = { input, unitKey, reverse ->
                    val active = session ?: discovery.newSession().also {
                        session = it
                        if (!preview) discoverySession = it
                    }
                    active.submit(SpeechNewWordScope(chapter.bookId, chapter.id), unitKey, input,
                        settings.text.personNameRegex ?: discovery.defaultNamePattern(), reverse).map { it.name }
                },
                classify = { classifier.classify(it, settings.text.maleNameRegex, settings.text.femaleNameRegex) },
                existing = ::existing,
                assign = assign@{ name, group ->
                    existing(name, listOf(group))?.let { return@assign it }
                    val pair = if (previewPool != null) previewPool.assign(chapter.bookId, name, group, saved,
                        settings.voiceItems, catalog, settings.maxCacheVoiceNum)
                    else pairs.assign(chapter.bookId, name, group, settings.voiceItems, catalog,
                        settings.maxCacheVoiceNum, chapter.bookTitle)
                    pair?.also { value ->
                        saved.removeAll { it.id == value.id }
                        saved += value
                    }
                }, checkCancelled = { task.ensureActive() }) else null
            prepareSpeechPlan(chapter, settings, replace, context, maxInputLength, catalog, characters) { task.ensureActive() }
        }
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
