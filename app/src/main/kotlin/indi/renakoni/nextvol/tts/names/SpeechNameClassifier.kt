package indi.renakoni.nextvol.tts.names

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechNameClassifier @Inject constructor(@ApplicationContext private val context: Context) {
    /** Model availability only; name rules also work on devices without the native runtime. */
    val available get() = NameModelNative.available

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
            classifyNameRules(name, maleNameRegex, femaleNameRegex)
        }
}
