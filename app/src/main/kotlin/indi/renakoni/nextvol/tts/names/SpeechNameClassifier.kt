package indi.renakoni.nextvol.tts.names

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechNameClassifier @Inject constructor(@ApplicationContext private val context: Context,
    private val executor: AndroidIsolatedExecutor, private val authority: ExecutionAuthority) {
    /** Model availability only; name rules also work on devices without the native runtime. */
    val available get() = NameModelNative.available

    // Built-in rules have the same process lifetime as this classifier; idle workers retire normally.
    private val ruleIdentity by lazy { authority.issue("speech-name-rules", "builtin", "1", namespace = "tts") }

    private val encoder by lazy {
        val assets = context.assets
        val vocabulary = assets.open("speech/names/ngr_cn_word.txt").bufferedReader().use {
            NgrEncoder.vocabulary(it.lineSequence())
        }
        val families = assets.open("speech/names/ngr_families.txt").bufferedReader().use { it.lineSequence().toSet() }
        NgrEncoder(vocabulary, families)
    }

    // Fixed packaged model, owned for the process lifetime like the original realtime Ngr instance.
    private val modelHandle by lazy {
        context.assets.open("speech/names/ngr_cn_model.onnx").use { NameModelNative.create(it.readBytes()) }
    }

    suspend fun classify(name: String, maleNameRegex: String = "", femaleNameRegex: String = ""): SpeechNameClassification =
        withContext(Dispatchers.Default) {
            if (name.isEmpty()) return@withContext SpeechNameClassification(SpeechNameHint.Unknown, SpeechNameHintBasis.None)
            if (available) {
                val input = encoder.encode(name)
                if (input.supported) {
                    ensureActive()
                    val handle = modelHandle
                    ensureActive()
                    val label = NameModelNative.predict(handle, input.features)
                    ensureActive()
                    return@withContext SpeechNameClassification(
                        if (label == 0) SpeechNameHint.Female else SpeechNameHint.Male, SpeechNameHintBasis.Model)
                }
            }
            ensureActive()
            // The original uses male full-match in its fallback; retaining the female pattern fixes its discarded value.
            when {
                matches(name, maleNameRegex, "maleNameRegex") -> SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.MaleRule)
                matches(name, femaleNameRegex, "femaleNameRegex") -> SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.FemaleRule)
                else -> SpeechNameClassification(SpeechNameHint.Unknown, SpeechNameHintBasis.None)
            }
        }

    private suspend fun matches(name: String, pattern: String, field: String): Boolean {
        if (pattern.isEmpty()) return false
        return when (val result = executor.execute(ruleIdentity, ExecutionTask.RegexMatch(name, pattern),
            ExecutionLimits(timeoutMillis = 2500, maxRequests = 0))) {
            is ExecutionResult.Success -> result.output.toBooleanStrict()
            is ExecutionResult.Failure -> {
                val error = result.ruleError
                if (error != null) throw PatternSyntaxException("$field: ${error.code}", pattern, error.location.offset)
                throw IllegalStateException("$field: ${result.code}")
            }
        }
    }
}
