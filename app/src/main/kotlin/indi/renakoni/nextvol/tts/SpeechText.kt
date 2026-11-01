package indi.renakoni.nextvol.tts

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

/** Offsets refer to the processed chapter text, before any speech-only transformations. */
data class SpeechSegment(val start: Int, val end: Int, val text: String,
    val dialogue: Boolean = false, val speaker: String? = null,
    val speakerHint: String? = null, val language: String? = null,
    val beforeText: List<String> = emptyList(), val afterText: List<String> = emptyList(),
    val rememberedText: List<String> = emptyList(), val previousVoice: String? = null,
    val changeVoice: Boolean = false,
    val selectedVoice: SpeechResolvedVoice? = null,
    val voiceReason: SpeechVoiceReason = SpeechVoiceReason.Narration)

internal fun speechDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Platform sentence rules handle punctuation/closing quotes; newlines are hard paragraph edges. */
internal fun speechSentences(text: String, checkCancelled: () -> Unit = {}): List<SpeechSegment> {
    val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
    val result = mutableListOf<SpeechSegment>()
    var paragraphStart = 0
    while (paragraphStart < text.length) {
        checkCancelled()
        val paragraphEnd = text.indexOf('\n', paragraphStart).let { if (it < 0) text.length else it }
        iterator.setText(text.substring(paragraphStart, paragraphEnd))
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            checkCancelled()
            var from = paragraphStart + start
            var to = paragraphStart + end
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (from < to) result += SpeechSegment(from, to, text.substring(from, to))
            start = end
            end = iterator.next()
        }
        paragraphStart = paragraphEnd + 1
    }
    return result
}
