package indi.renakoni.nextvol.tts.names

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.tts.SpeechMappedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SpeechNameRecognizer @Inject constructor(@ApplicationContext private val context: Context) {
    val available get() = NameModelNative.available

    suspend fun recognize(text: String): List<SpeechNameCandidate> = withContext(Dispatchers.Default) {
        check(available) { "NER requires the built-in arm64 runtime and Android 8 or later" }
        if (text.isBlank()) return@withContext emptyList()
        val assets = context.assets
        val vocabulary = assets.open("speech/names/cn_vocab.txt").bufferedReader().use {
            NerDecoder.vocabulary(it.lineSequence())
        }
        ensureActive()
        val handle = assets.open("speech/names/ner_cn_model.onnx").use { NameModelNative.create(it.readBytes()) }
        try {
            val decoder = NerDecoder(vocabulary, NameModelNative.transitions(handle))
            val result = mutableListOf<SpeechNameCandidate>()
            // Java Character.isWhitespace is the original Ner.a preparation; retain the removed prefix for mapping.
            val prepared = text.trim { Character.isWhitespace(it) }
            val origin = text.indexOf(prepared)
            for (range in NerDecoder.chunks(prepared)) {
                ensureActive()
                val chunk = prepared.substring(range)
                val emissions = NameModelNative.emissions(handle, decoder.encode(chunk))
                result += decoder.decode(chunk, emissions, origin + range.first)
            }
            ensureActive()
            result
        } finally {
            NameModelNative.destroy(handle)
        }
    }

    internal suspend fun recognize(text: SpeechMappedText): List<SpeechNameCandidate> = recognize(text.text).map {
        val (start, end) = text.range(it.start, it.end)
        it.copy(start = start, end = end)
    }
}
