package indi.renakoni.nextvol.data.format

import java.util.regex.PatternSyntaxException

internal fun applyFormattingRules(text: String, rules: List<FormattingRule>): String {
    var result = text
    for (formattingRule in rules.filter { it.isEnabled }) {
        try {
            result = if (formattingRule.isRegex) result.replaceTextWithRegex(
                Regex(formattingRule.match.unescapeControlChars()),
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
    return this.replace(Regex("""\\[\\"tnrbf]|\\u[0-9a-fA-F]{4}""")) {
        when (it.value) {
            "\\n" -> "\n"
            "\\t" -> "\t"
            "\\r" -> "\r"
            "\\b" -> "\b"
            "\\f" -> "\u000c"
            "\\\"" -> "\""
            "\\\\" -> "\\"
            else -> {
                if (it.value.startsWith("\\u")) {
                    it.value.substring(2).toInt(16).toChar().toString()
                } else it.value
            }
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
        var progressedReplacedText = replaced
        matchResult.groups.forEachIndexed { index, matchGroup ->
            matchGroup?.value?.let {
                progressedReplacedText = progressedReplacedText.replace("$$index", it)
            }
        }
        result.append(progressedReplacedText)
        end = matchResult.range.last + 1
    }
    return result.append(this, end, length).toString()
}
