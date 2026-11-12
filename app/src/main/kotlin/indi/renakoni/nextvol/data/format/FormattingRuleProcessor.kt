package indi.renakoni.nextvol.data.format

import java.util.regex.PatternSyntaxException

private val groupReferenceRegex = Regex("""\$([0-9]+)""")
private val controlCharEscapeRegex = Regex("""\\[\\"tnrbf]|\\u[0-9a-fA-F]{4}""")

internal fun applyFormattingRules(text: String, rules: List<FormattingRule>): String {
    var result = text
    for (formattingRule in rules) {
        if (!formattingRule.isEnabled) continue
        try {
            result = if (formattingRule.isRegex) result.replaceTextWithRegex(
                Regex(formattingRule.match),
                formattingRule.replacement.unescapeControlChars()
            )
            else result.replace(
                formattingRule.match.unescapeControlChars(),
                formattingRule.replacement.unescapeControlChars()
            )
        } catch (_: PatternSyntaxException) {
        }
    }
    return result
}

private fun String.unescapeControlChars(): String {
    return this.replace(controlCharEscapeRegex) {
        when (it.value) {
            "\\n" -> "\n"
            "\\t" -> "\t"
            "\\r" -> "\r"
            "\\b" -> "\b"
            "\\f" -> "\u000c"
            "\\\"" -> "\""
            "\\\\" -> "\\"
            else -> it.value.substring(2).toInt(16).toChar().toString()
        }
    }
}

private fun String.replaceTextWithRegex(regex: Regex, replaced: String): String {
    val matches = regex.findAll(this).iterator()
    if (!matches.hasNext()) return this
    val result = StringBuilder(length)
    var end = 0
    for (matchResult in matches) {
        result.append(this, end, matchResult.range.first)
        result.append(groupReferenceRegex.replace(replaced) { reference ->
            val digits = reference.groupValues[1]
            var group = digits[0].digitToInt()
            if (group >= matchResult.groups.size) return@replace reference.value
            var consumed = 1
            while (consumed < digits.length) {
                val next = group.toLong() * 10 + digits[consumed].digitToInt()
                if (next >= matchResult.groups.size) break
                group = next.toInt()
                consumed++
            }
            matchResult.groupValues[group] + digits.substring(consumed)
        })
        end = matchResult.range.last + 1
    }
    return result.append(this, end, length).toString()
}
