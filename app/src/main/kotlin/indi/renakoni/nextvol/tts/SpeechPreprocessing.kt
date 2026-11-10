package indi.renakoni.nextvol.tts

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

private val unspokenText = Pattern.compile("^[\\s\\p{C}\\p{P}\\p{Z}\\p{S}]+$")
private val latinText = Pattern.compile("[\\p{script=Latin}\\p{P}0-9\\s]+")
private val latinLetter = Pattern.compile("\\p{script=Latin}")
private val atomicMarks = Pattern.compile("<<.*?>>|\\[\\[.*?\\]\\]")
private const val speechClosingPunctuation = ",.!?;:，。！？；：…”」»\"’')]}）】》"

private fun speechIsClosingPunctuation(value: Char): Boolean = value in speechClosingPunctuation ||
    when (Character.getType(value)) {
        Character.END_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt() -> true
        else -> false
    }

private val wordWhitespace = Pattern.compile("\\s+")
private val chineseEnglishSentence = Pattern.compile(
    "([,.!?;:”」»\\n，。！？；：…|](?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")")
private val otherSentence = Pattern.compile(
    "(([|\\n\\p{P}&&[^-_/+“「«\"’']])(?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")")

internal fun speechIsUnspoken(text: String): Boolean =
    text.isEmpty() || unspokenText.matcher(text).matches()

internal fun speechTrim(input: SpeechMappedText): SpeechMappedText {
    var start = 0
    var end = input.text.length
    while (start < end && Character.isWhitespace(input.text.codePointAt(start))) {
        start += Character.charCount(input.text.codePointAt(start))
    }
    while (end > start && Character.isWhitespace(input.text.codePointBefore(end))) {
        end -= Character.charCount(input.text.codePointBefore(end))
    }
    return input.slice(start, end)
}

internal fun speechNormalize(input: SpeechMappedText, checkCancelled: () -> Unit = {}): SpeechMappedText {
    checkCancelled()
    if (Normalizer.isNormalized(input.text, Normalizer.Form.NFC)) return speechTrim(input)
    val output = SpeechMappedText.Builder(input.text.length)
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
    characters.setText(input.text)
    var start = characters.first()
    var end = characters.next()
    while (end != BreakIterator.DONE) {
        checkCancelled()
        val source = input.text.substring(start, end)
        val normalized = Normalizer.normalize(source, Normalizer.Form.NFC)
        if (normalized == source) output.append(input, start, end)
        else output.append(normalized, input.range(start, end))
        start = end
        end = characters.next()
    }
    return speechTrim(output.build(input))
}

internal data class SpeechLanguageSegment(val content: SpeechMappedText, val language: String?)

internal fun speechLatinSegments(
    input: SpeechMappedText, enabled: Boolean, checkCancelled: () -> Unit = {},
): List<SpeechLanguageSegment> {
    checkCancelled()
    if (input.text.isEmpty()) return emptyList()
    if (!enabled) return listOf(SpeechLanguageSegment(input, null))
    val result = mutableListOf<SpeechLanguageSegment>()
    val letters = latinLetter.matcher(input.text)
    var copied = 0
    fun add(start: Int, end: Int, language: String?) {
        if (start < end) result += SpeechLanguageSegment(input.slice(start, end), language)
    }
    fun findLatin(start: Int, end: Int) {
        val matches = latinText.matcher(input.text).region(start, end)
        while (matches.find()) {
            checkCancelled()
            // Scan each candidate run once; a long digit-only run must not retry every suffix.
            if (!letters.region(matches.start(), matches.end()).find()) continue
            add(copied, matches.start(), null)
            add(matches.start(), matches.end(), "lang:latin")
            copied = matches.end()
        }
    }
    val marks = atomicMarks.matcher(input.text)
    var start = 0
    while (marks.find()) {
        checkCancelled()
        findLatin(start, marks.start())
        start = marks.end()
    }
    findLatin(start, input.text.length)
    add(copied, input.text.length, null)
    return result
}

internal fun speechReplaceEmoji(
    input: SpeechMappedText, single: (Int) -> String?, pair: (Int, Int) -> String?,
    checkCancelled: () -> Unit = {},
): SpeechMappedText {
    val output = SpeechMappedText.Builder(input.text.length)
    var start = 0
    while (start < input.text.length) {
        checkCancelled()
        val first = input.text.codePointAt(start)
        var end = start + Character.charCount(first)
        var name = single(first)
        if (name == null && end < input.text.length) {
            val second = input.text.codePointAt(end)
            name = pair(first, second)
            if (name != null) end += Character.charCount(second)
        }
        if (name == null) output.append(input, start, end)
        else output.append("[$name]", input.range(start, end))
        start = end
    }
    return output.build(input)
}

/** MultiTTS groups at the target, but only hard-splits a group at twice that length. */
internal fun speechSentenceChunks(
    input: SpeechMappedText, limit: Int,
    locale: Locale = Locale.getDefault(), checkCancelled: () -> Unit = {},
): List<SpeechMappedText> {
    require(limit >= 2)
    checkCancelled()
    if (input.text.length <= limit) return listOf(input)
    val result = mutableListOf<SpeechMappedText>()
    fun flush(start: Int, end: Int) {
        val group = input.slice(start, end)
        val chunks = if (group.text.length < limit * 2) listOf(group)
            else speechLongSentence(group, limit, checkCancelled)
        result += chunks
    }
    val pattern = if (locale.language == "en" || locale.language == "zh") chineseEnglishSentence else otherSentence
    val marks = speechMarkRanges(input.text)
    val matches = pattern.matcher(input.text)
    var markIndex = 0
    var groupStart = 0
    var partStart = 0
    var partEnd = 0
    fun append(end: Int) {
        if (end - groupStart > limit && end - partStart > 1 && partStart != groupStart &&
            (partStart until end).any { !speechIsClosingPunctuation(input.text[it]) && !Character.isWhitespace(input.text[it]) }) {
            flush(groupStart, partStart)
            groupStart = partStart
        }
        partStart = end
        partEnd = end
    }
    while (matches.find()) {
        checkCancelled()
        while (markIndex < marks.size && marks[markIndex].last < matches.start()) markIndex++
        if (markIndex < marks.size && matches.start() in marks[markIndex]) continue
        if (input.text[matches.start()] == '.' && matches.start() > 0 && matches.end() < input.text.length &&
            Character.isDigit(input.text.codePointBefore(matches.start())) && Character.isDigit(input.text.codePointAt(matches.end()))) continue
        // The original start + 1 loses a low surrogate before an opening quote.
        append(matches.end())
    }
    if (partEnd < input.text.length) append(input.text.length)
    flush(groupStart, input.text.length)
    return result
}

/** S6.f.d supplies each punctuation-delimited unit to language detection before aggregation. */
internal fun speechLanguageSentences(input: SpeechMappedText, locale: Locale, checkCancelled: () -> Unit): List<SpeechMappedText> {
    val pattern = if (locale.language == "en" || locale.language == "zh") chineseEnglishSentence else otherSentence
    val marks = speechMarkRanges(input.text)
    val matches = pattern.matcher(input.text)
    val result = mutableListOf<SpeechMappedText>()
    var start = 0
    var markIndex = 0
    while (matches.find()) {
        checkCancelled()
        while (markIndex < marks.size && marks[markIndex].last < matches.start()) markIndex++
        if (markIndex < marks.size && matches.start() in marks[markIndex]) continue
        if (start < matches.end()) result += input.slice(start, matches.end())
        start = matches.end()
    }
    if (start < input.text.length) result += input.slice(start, input.text.length)
    return result
}

private fun speechLongSentence(
    input: SpeechMappedText, limit: Int, checkCancelled: () -> Unit,
): List<SpeechMappedText> {
    val marks = speechMarkRanges(input.text)
    val whitespace = wordWhitespace.matcher(input.text)
    val result = mutableListOf<SpeechMappedText>()
    var groupStart = -1
    var groupEnd = 0
    fun flush() {
        result += speechBoundedChunks(input.slice(groupStart, groupEnd), limit, false, checkCancelled)
    }
    fun appendWord(from: Int, to: Int) {
        if (from == to) return
        if (groupStart >= 0 && to - groupStart > limit &&
            (from until to).any { !speechIsClosingPunctuation(input.text[it]) }) {
            flush()
            groupStart = -1
        }
        if (groupStart < 0) groupStart = from
        groupEnd = to
    }
    var markIndex = 0
    var start = 0
    while (whitespace.find()) {
        checkCancelled()
        while (markIndex < marks.size && marks[markIndex].last < whitespace.start()) markIndex++
        if (markIndex < marks.size && whitespace.start() in marks[markIndex]) continue
        appendWord(start, whitespace.start())
        start = whitespace.end()
    }
    appendWord(start, input.text.length)
    if (groupStart >= 0) flush()
    return result
}

/** Backend limits are independent of the user's softer sentence target. */
internal fun speechHardChunks(
    input: SpeechMappedText, limit: Int, checkCancelled: () -> Unit = {},
): List<SpeechMappedText> = speechBoundedChunks(input, limit, true, checkCancelled)

private fun speechMarkRanges(text: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    val marks = atomicMarks.matcher(text)
    while (marks.find()) result += marks.start() until marks.end()
    return result
}

private fun speechBoundedChunks(
    input: SpeechMappedText, limit: Int, hard: Boolean, checkCancelled: () -> Unit,
): List<SpeechMappedText> {
    require(limit >= 2)
    checkCancelled()
    if (input.text.length <= limit) return listOf(input)
    val result = mutableListOf<SpeechMappedText>()
    val marks = speechMarkRanges(input.text)
    var markIndex = 0
    var start = 0
    while (start < input.text.length) {
        checkCancelled()
        var end = minOf(start + limit, input.text.length)
        if (end < input.text.length && input.text[end - 1].isHighSurrogate() && input.text[end].isLowSurrogate()) end--
        val tailLimit = minOf(end + limit, input.text.length)
        var punctuationEnd = end
        while (punctuationEnd < tailLimit && Character.isWhitespace(input.text[punctuationEnd])) punctuationEnd++
        if (punctuationEnd < tailLimit && speechIsClosingPunctuation(input.text[punctuationEnd])) {
            var punctuationStart = end
            while (punctuationStart > start && (speechIsClosingPunctuation(input.text[punctuationStart - 1]) ||
                Character.isWhitespace(input.text[punctuationStart - 1]))) punctuationStart--
            while (punctuationEnd < tailLimit && (speechIsClosingPunctuation(input.text[punctuationEnd]) ||
                Character.isWhitespace(input.text[punctuationEnd]))) punctuationEnd++
            if (punctuationStart > start) {
                val before = punctuationStart - Character.charCount(input.text.codePointBefore(punctuationStart))
                if (before > start && punctuationEnd - before <= limit) end = before
            }
        }
        while (markIndex < marks.size && marks[markIndex].last < start) markIndex++
        var index = markIndex
        while (index < marks.size && marks[index].first < end) {
            val mark = marks[index]
            if (end <= mark.last) {
                if (mark.first > start) end = mark.first
                else {
                    if (hard && mark.last + 1 - mark.first > limit) throw SpeechException(SpeechError.UnsupportedContent)
                    end = mark.last + 1
                }
                break
            }
            index++
        }
        result += input.slice(start, end)
        start = end
    }
    return result
}
