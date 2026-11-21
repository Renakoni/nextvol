package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.SPEECH_SOVITS_INPUT_LIMIT
import indi.renakoni.nextvol.tts.SpeechMappedText
import indi.renakoni.nextvol.tts.speechIsClosingPunctuation
import indi.renakoni.nextvol.tts.speechIsUnspoken
import indi.renakoni.nextvol.tts.speechRequestChunks
import kotlin.math.roundToInt

/** Per active voice. Both observations include the common PCM processor, before queue backpressure. */
internal class SovitsSegmentation(speed: Float) {
    // Initial Mandarin estimate: five UTF-16 units per second at 1x, RTF 0.75.
    private var audioMsPerUnit = 200.0 / speed
    private var generationMsPerUnit = .75 * audioMsPerUnit

    fun nextEnd(text: String, start: Int, bufferedMs: Long): Int {
        val target = if (bufferedMs <= 0) 2000 / audioMsPerUnit else
            ((bufferedMs - 300) / generationMsPerUnit).coerceAtLeast(1000 / audioMsPerUnit)
        // With little audio left, repeatedly requesting one-second clips can spend more
        // time on per-request work than those clips add to the buffer.
        val budget = if (bufferedMs < 2000 && generationMsPerUnit >= audioMsPerUnit)
            target.coerceAtLeast(4000 / audioMsPerUnit) else target
        val limit = minOf(budget, 12_000 / audioMsPerUnit).roundToInt().coerceIn(2, SPEECH_SOVITS_INPUT_LIMIT)
        if (text.length - start <= limit) return text.length
        val remaining = text.substring(start)
        val chunks = speechRequestChunks(SpeechMappedText.original(remaining), limit, hard = false) {}
        // Punctuation or whitespace in isolation may have no phonemes. Carry it with spoken text.
        val spoken = chunks.filterNot { speechIsUnspoken(it.text) }
        val first = spoken.firstOrNull() ?: return text.length
        val head = if (singleHan(first.text)) spoken.getOrNull(1) ?: first else first
        var end = head.range()?.second ?: return text.length
        // A model duration target may fall inside a long English word or decimal in unpunctuated prose.
        wordOrNumber.findAll(remaining).firstOrNull { end in it.range && end > it.range.first }?.let { end = it.range.last + 1 }
        while (end < remaining.length && speechIsClosingPunctuation(remaining[end])) end++
        while (end < remaining.length && remaining[end].isWhitespace()) end++
        val tail = remaining.substring(end)
        if (speechIsUnspoken(tail) || singleHan(tail)) return text.length
        return start + end
    }

    fun record(units: Int, elapsedMs: Long, audioMs: Long) {
        if (units <= 0 || elapsedMs <= 0 || audioMs <= 0) return
        generationMsPerUnit = (generationMsPerUnit + elapsedMs.toDouble() / units) / 2
        audioMsPerUnit = (audioMsPerUnit + audioMs.toDouble() / units) / 2
    }

    private companion object {
        fun singleHan(text: String) = text.codePoints().anyMatch(Character::isIdeographic) &&
            text.codePoints().filter(Character::isLetterOrDigit).count() == 1L
        val wordOrNumber = Regex("""[\p{IsLatin}\p{M}\d]+(?:['’.\-][\p{IsLatin}\p{M}\d]+)*""")
    }
}
