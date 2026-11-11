package hnovel.speech

import java.util.Locale
import java.util.regex.Pattern

private const val onlineSpeechClosingPunctuation = ",.!?;:，。！？；：…”」»\"’')]}）】》"

private fun onlineSpeechIsClosingPunctuation(value: Char): Boolean = value in onlineSpeechClosingPunctuation ||
    when (Character.getType(value)) {
        Character.END_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt() -> true
        else -> false
    }

private val onlineChineseEnglishSentence = Pattern.compile(
    "([,.!?;:”」»\\n，。！？；：…|](?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")")
private val onlineOtherSentence = Pattern.compile(
    "(([|\\n\\p{P}&&[^-_/+“「«\"’']])(?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")")
private val onlineSpeechMarks = Pattern.compile("<<.*?>>|\\[\\[.*?\\]\\]")
private val onlineSpeechWhitespace = Pattern.compile("\\s+")

/** S6.f.e/c: aggregate punctuation units, then split groups reaching twice the sentence target. */
internal fun splitOnlineSpeechText(text: String, limit: Int): List<String> {
    require(limit >= 2)
    if (text.length <= limit) return listOf(text)
    val language = Locale.getDefault().language
    val pattern = if (language == "en" || language == "zh") onlineChineseEnglishSentence else onlineOtherSentence
    val marks = onlineSpeechMarkRanges(text)
    var markIndex = 0
    val parts = mutableListOf<String>()
    val matcher = pattern.matcher(text)
    var start = 0
    while (matcher.find()) {
        if (matcher.end() <= start) continue
        while (markIndex < marks.size && marks[markIndex].last < matcher.start()) markIndex++
        if (markIndex < marks.size && matcher.start() in marks[markIndex]) continue
        if (text[matcher.start()] == '.' && matcher.start() > 0 && matcher.end() < text.length &&
            Character.isDigit(text.codePointBefore(matcher.start())) && Character.isDigit(text.codePointAt(matcher.end()))) continue
        var end = matcher.end()
        while (end < text.length && onlineSpeechIsClosingPunctuation(text[end])) end++
        parts += text.substring(start, end)
        start = end
    }
    if (start < text.length) parts += text.substring(start)
    val result = mutableListOf<String>()
    val buffer = StringBuilder()
    fun flush() {
        val group = buffer.toString()
        buffer.setLength(0)
        if (group.length < limit * 2) result += group
        else result += splitLongOnlineSpeechText(group, limit)
    }
    for (part in parts) {
        if (part.length + buffer.length > limit && part.length > 1 && buffer.isNotEmpty() &&
            part.any { !onlineSpeechIsClosingPunctuation(it) && !Character.isWhitespace(it) }) flush()
        buffer.append(part)
    }
    flush()
    return result
}

private fun onlineSpeechMarkRanges(text: String): List<IntRange> {
    val marks = onlineSpeechMarks.matcher(text)
    return buildList { while (marks.find()) add(marks.start() until marks.end()) }
}

private fun splitLongOnlineSpeechText(text: String, limit: Int): List<String> {
    val marks = onlineSpeechMarkRanges(text)
    val whitespace = onlineSpeechWhitespace.matcher(text)
    val groups = mutableListOf<String>()
    var groupStart = -1
    var groupEnd = 0
    fun appendWord(from: Int, to: Int) {
        if (from == to) return
        if (groupStart >= 0 && to - groupStart > limit &&
            (from until to).any { !onlineSpeechIsClosingPunctuation(text[it]) }) {
            groups += text.substring(groupStart, groupEnd)
            groupStart = -1
        }
        if (groupStart < 0) groupStart = from
        groupEnd = to
    }
    var markIndex = 0
    var start = 0
    while (whitespace.find()) {
        while (markIndex < marks.size && marks[markIndex].last < whitespace.start()) markIndex++
        if (markIndex < marks.size && whitespace.start() in marks[markIndex]) continue
        appendWord(start, whitespace.start())
        start = whitespace.end()
    }
    appendWord(start, text.length)
    if (groupStart >= 0) groups += text.substring(groupStart, groupEnd)
    return groups.flatMap { sentence ->
        val sentenceMarks = onlineSpeechMarkRanges(sentence)
        var index = 0
        buildList {
            var from = 0
            while (from < sentence.length) {
                var end = minOf(from + limit, sentence.length)
                if (end < sentence.length && sentence[end - 1].isHighSurrogate() && sentence[end].isLowSurrogate()) end--
                val tailLimit = minOf(end + limit, sentence.length)
                var punctuationEnd = end
                while (punctuationEnd < tailLimit && Character.isWhitespace(sentence[punctuationEnd])) punctuationEnd++
                if (punctuationEnd < tailLimit && onlineSpeechIsClosingPunctuation(sentence[punctuationEnd])) {
                    var punctuationStart = end
                    while (punctuationStart > from && (onlineSpeechIsClosingPunctuation(sentence[punctuationStart - 1]) ||
                        Character.isWhitespace(sentence[punctuationStart - 1]))) punctuationStart--
                    while (punctuationEnd < tailLimit && (onlineSpeechIsClosingPunctuation(sentence[punctuationEnd]) ||
                        Character.isWhitespace(sentence[punctuationEnd]))) punctuationEnd++
                    if (punctuationStart > from) {
                        val before = punctuationStart - Character.charCount(sentence.codePointBefore(punctuationStart))
                        if (before > from && punctuationEnd - before <= limit) end = before
                    }
                }
                while (index < sentenceMarks.size && sentenceMarks[index].last < from) index++
                var next = index
                while (next < sentenceMarks.size && sentenceMarks[next].first < end) {
                    val mark = sentenceMarks[next]
                    if (end <= mark.last) {
                        end = if (mark.first > from) mark.first else mark.last + 1
                        break
                    }
                    next++
                }
                add(sentence.substring(from, end))
                from = end
            }
        }
    }
}
