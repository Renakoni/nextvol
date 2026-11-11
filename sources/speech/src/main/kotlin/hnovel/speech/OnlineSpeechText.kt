package hnovel.speech

import java.util.Locale
import java.util.regex.Pattern

/** S6.f.e/c: aggregate punctuation units, then split groups reaching twice the sentence target. */
internal fun splitOnlineSpeechText(text: String, limit: Int): List<String> {
    require(limit >= 2)
    if (text.length <= limit) return listOf(text)
    val pattern = if (Locale.getDefault().language in setOf("en", "zh"))
        "([,.!?;:”」»\\n，。！？；：…|](?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")"
    else "(([|\\n\\p{P}&&[^-_/+“「«\"’']])(?![”」»\"]))|.(?=[“「«])|\"(?=[\"\\s])|\\s(?=\")"
    val parts = mutableListOf<String>()
    val matcher = Pattern.compile(pattern).matcher(text)
    var start = 0
    while (matcher.find()) {
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
        else {
            val words = Pattern.compile("\\s+").split(group)
            val groups = if (words.size == 1) listOf(group) else buildList {
                val sentence = StringBuilder()
                for (word in words) {
                    if (sentence.isNotEmpty() && sentence.length + word.length > limit) {
                        add(sentence.toString()); sentence.setLength(0)
                    }
                    sentence.append(' ').append(word)
                }
                add(sentence.toString())
            }
            for (sentence in groups) {
                var from = 0
                while (from < sentence.length) {
                    var end = minOf(from + limit, sentence.length)
                    if (end < sentence.length && sentence[end - 1].isHighSurrogate() && sentence[end].isLowSurrogate()) end--
                    result += sentence.substring(from, end)
                    from = end
                }
            }
        }
    }
    for (part in parts) {
        if (part.length + buffer.length > limit && part.length > 1 && buffer.isNotEmpty()) flush()
        buffer.append(part)
    }
    flush()
    return result
}
