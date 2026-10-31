package indi.renakoni.nextvol.tts

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

/** Offsets refer to the processed chapter text, before any speech-only transformations. */
data class SpeechSegment(val start: Int, val end: Int, val text: String)

internal fun speechDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Bounded UTF-16 input, preserving sentence boundaries and never splitting a surrogate pair. */
fun speechSegments(text: String, maxLength: Int = 200, firstLength: Int = 80, checkCancelled: () -> Unit = {}): List<SpeechSegment> {
    require(maxLength >= 2 && firstLength >= 2)
    val result = mutableListOf<SpeechSegment>()
    val sentences = speechSentences(text, checkCancelled)
    var sentenceIndex = 0
    var start = 0
    while (start < text.length) {
        checkCancelled()
        while (start < text.length && text[start].isWhitespace()) start++
        if (start == text.length) break
        val limit = if (result.isEmpty()) minOf(firstLength, maxLength) else maxLength
        var end = minOf(text.length, start + limit)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        val paragraphEnd = text.indexOf('\n', start).takeIf { it >= 0 && it < end }
        if (paragraphEnd != null) end = paragraphEnd + 1
        else {
            // Group short sentences around 80 characters; a short paragraph stays together.
            while (sentenceIndex < sentences.size && sentences[sentenceIndex].end <= start) sentenceIndex++
            var sentence: Int? = null
            var index = sentenceIndex
            while (index < sentences.size && sentences[index].end <= end) {
                sentence = sentences[index++].end
                if (sentence - start >= minOf(80, limit)) break
            }
            val whitespace = (start until end).lastOrNull { text[it].isWhitespace() }
            if (sentence != null) end = sentence
            else if (end < text.length) end = whitespace?.takeIf { it > start } ?: end
        }
        var spokenEnd = end
        while (spokenEnd > start && text[spokenEnd - 1].isWhitespace()) spokenEnd--
        val spoken = text.substring(start, spokenEnd)
        if (spoken.codePoints().anyMatch(Character::isLetterOrDigit)) {
            result += SpeechSegment(start, spokenEnd, spoken)
        }
        start = end
    }
    return result
}

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
