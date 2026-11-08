package indi.renakoni.nextvol.tts

import java.util.Locale

/** MultiTTS R6.p.c: each command replaces one multiplier for the remaining tagged request. */
internal class SpeechMarks {
    var parameters = SpeechAudioParameters()
        private set

    fun accept(mark: String): Int? {
        val fields = mark.substring(2, mark.length - 2).split(':')
        val value = fields.getOrNull(1)
        when (fields[0].trim().uppercase(Locale.ROOT)) {
            "SPEED" -> parameters = parameters.copy(speed = value?.toFloatOrNull() ?: 1f)
            "PITCH" -> parameters = parameters.copy(pitch = value?.toFloatOrNull() ?: 1f)
            "VOLUME" -> parameters = parameters.copy(volume = value?.toFloatOrNull() ?: 1f)
            "PAUSE" -> return value?.toIntOrNull() ?: 500
        }
        return null
    }
}

internal operator fun SpeechAudioParameters.times(other: SpeechAudioParameters) =
    SpeechAudioParameters(speed * other.speed, pitch * other.pitch, volume * other.volume)

internal fun speechParagraphIndex(paragraphEnds: List<Int>, offset: Int) =
    paragraphEnds.binarySearch(offset).let { if (it >= 0) it else -it - 1 }

/** Split only when the original paragraph changes, even if replacement removed its newline. */
internal fun speechParagraphChunks(input: SpeechMappedText, paragraphEnds: List<Int>): List<SpeechMappedText> {
    if (input.text.isEmpty()) return emptyList()
    fun paragraph(index: Int) = speechParagraphIndex(paragraphEnds, input.anchor(index))
    val result = mutableListOf<SpeechMappedText>()
    var start = 0
    var previous = paragraph(0)
    for (index in 1 until input.text.length) {
        val current = paragraph(index)
        if (current != previous) {
            result += speechTrim(input.slice(start, index))
            start = index
            previous = current
        }
    }
    result += speechTrim(input.slice(start, input.text.length))
    return result
}
