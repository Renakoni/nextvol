package indi.renakoni.nextvol.tts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class SpeechTextSettings(
    val dialogue: Boolean = false,
    val recordVoice: Boolean = false,
    val recordText: Boolean = false,
    val continueDialogue: Boolean = false,
    val replaceEmoji: Boolean = false,
    val separateEnglish: Boolean = true,
    val filterUnspoken: Boolean = true,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val maxSentenceLength: Int = when (Locale.getDefault().language) {
        "zh" -> 60
        "tr" -> 200
        else -> 100
    },
) {
    fun validated() = copy(maxSentenceLength = maxSentenceLength.coerceIn(32, 256))
}
