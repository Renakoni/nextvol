package indi.renakoni.nextvol.tts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.Locale
import indi.renakoni.nextvol.tts.names.SpeechNamePatterns

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SpeechTextSettings(
    val dialogue: Boolean = false,
    val recordVoice: Boolean = false,
    val recordText: Boolean = false,
    val continueDialogue: Boolean = false,
    val replaceEmoji: Boolean = false,
    val separateEnglish: Boolean = true,
    val filterUnspoken: Boolean = true,
    val detectLanguage: Boolean = false,
    val languageDetector: Int = 300,
    val filterLanguage: Boolean = false,
    val paragraphPauseMs: Int = 0,
    val nameDiscoveryAlgorithm: Int = 101,
    val personNameRegex: String? = null,
    @EncodeDefault
    val maleNameRegex: String = if (Locale.getDefault().language == "zh") SpeechNamePatterns.MALE else "",
    @EncodeDefault
    val femaleNameRegex: String = if (Locale.getDefault().language == "zh") SpeechNamePatterns.FEMALE else "",
    @EncodeDefault
    val maxSentenceLength: Int = when (Locale.getDefault().language) {
        "zh" -> 60
        "tr" -> 200
        else -> 100
    },
) {
    fun validated() = copy(maxSentenceLength = maxSentenceLength.coerceIn(32, 256))
}
