package hnovel.speech

import java.util.Locale
import java.util.regex.Pattern

private const val onlineSpeechClosingPunctuation = ",.!?;:，。！？；：…”」»\"’')]}）】》"

/** S6.f.e/c: aggregate punctuation units, then split groups reaching twice the sentence target. */
internal fun splitOnlineSpeechText(text: String, limit: Int): List<String> {
    require(limit >= 2)
    if (text.length <= limit) return listOf(text)
    val pattern = if (Locale.getDefault().language in setOf("en", "zh"))
        "([,.!?;:”」»\\n，。！？；：…|](?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")"
    else "(([|\\n\\p{P}&&[^-_/+“「«\"’']])(?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")"
    val marks = onlineSpeechMarkRanges(text)
    var markIndex = 0
    val parts = mutableListOf<String>()
    val matcher = Pattern.compile(pattern).matcher(text)
    var start = 0
    while (matcher.find()) {
        while (markIndex < marks.size && marks[markIndex].last < matcher.start()) markIndex++
        if (markIndex < marks.size && matcher.start() in marks[markIndex]) continue
        if (text[matcher.start()] == '.' && matcher.start() > 0 && matcher.end() < text.length &&
            Character.isDigit(text.codePointBefore(matcher.start())) && Character.isDigit(text.codePointAt(matcher.end()))) continue
        // Use the complete match to retain supplementary characters before opening quotes.
        parts += text.substring(start, matcher.end())
        start = matcher.end()
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
        if (part.length + buffer.length > limit && part.length > 1 && buffer.isNotEmpty()) flush()
        buffer.append(part)
    }
    flush()
    return result
}

private fun onlineSpeechMarkRanges(text: String): List<IntRange> {
    val marks = Pattern.compile("<<.*?>>|\\[\\[.*?\\]\\]").matcher(text)
    return buildList { while (marks.find()) add(marks.start() until marks.end()) }
}

private fun splitLongOnlineSpeechText(text: String, limit: Int): List<String> {
    val marks = onlineSpeechMarkRanges(text)
    val whitespace = Pattern.compile("\\s+").matcher(text)
    val words = mutableListOf<Pair<Int, Int>>()
    var markIndex = 0
    var start = 0
    while (whitespace.find()) {
        while (markIndex < marks.size && marks[markIndex].last < whitespace.start()) markIndex++
        if (markIndex < marks.size && whitespace.start() in marks[markIndex]) continue
        words += start to whitespace.start()
        start = whitespace.end()
    }
    words += start to text.length
    val groups = mutableListOf<String>()
    var groupStart = -1
    var groupEnd = 0
    for ((from, to) in words) {
        if (from == to) continue
        if (groupStart >= 0 && to - groupStart > limit) {
            groups += text.substring(groupStart, groupEnd)
            groupStart = -1
        }
        if (groupStart < 0) groupStart = from
        groupEnd = to
    }
    if (groupStart >= 0) groups += text.substring(groupStart, groupEnd)
    return groups.flatMap { sentence ->
        val sentenceMarks = onlineSpeechMarkRanges(sentence)
        var index = 0
        buildList {
            var from = 0
            while (from < sentence.length) {
                var end = minOf(from + limit, sentence.length)
                if (end < sentence.length && sentence[end - 1].isHighSurrogate() && sentence[end].isLowSurrogate()) end--
                if (end < sentence.length && sentence[end] in onlineSpeechClosingPunctuation) {
                    var punctuationStart = end
                    var punctuationEnd = end
                    while (punctuationStart > from && sentence[punctuationStart - 1] in onlineSpeechClosingPunctuation) punctuationStart--
                    while (punctuationEnd < sentence.length && sentence[punctuationEnd] in onlineSpeechClosingPunctuation) punctuationEnd++
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
