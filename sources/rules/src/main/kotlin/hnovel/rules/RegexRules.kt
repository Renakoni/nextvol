package hnovel.rules

import java.util.regex.Pattern

/** Check scans in small batches: normal long paragraphs can revisit millions of characters. */
internal class BudgetText(private val value: String, private val budget: RuleBudget,
    private val checkCancelled: () -> Unit = {}) : CharSequence {
    private var reads = 0
    override val length get() = value.length
    override fun get(index: Int): Char {
        // Keep deadline/interruption checks inside the matcher, including backtracking.
        if (reads++ and 255 == 0) { checkCancelled(); budget.check() }
        return value[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BudgetText(value.substring(startIndex, endIndex), budget, checkCancelled)
    override fun toString() = value
}

internal object RegexRules {
    fun extract(input: String, rules: List<String>, first: Boolean, budget: RuleBudget): RuleValue {
        var content = input
        rules.forEachIndexed { index, rule ->
            val matcher = Pattern.compile(rule).matcher(BudgetText(content, budget))
            if (index != rules.lastIndex) {
                val joined = StringBuilder()
                var found = false
                while (matcher.find()) {
                    found = true
                    budget.check()
                    joined.append(matcher.group())
                    budget.checkSize(joined.length, budget.limits.maxOutputChars)
                }
                if (!found) return if (first) RuleValue.Empty else RuleValue.Items(emptyList())
                content = joined.toString()
            } else {
                val matches = mutableListOf<RuleValue>()
                var characters = 0L
                while (matcher.find()) {
                    budget.check()
                    val groups = (0..matcher.groupCount()).map { group ->
                        // The pinned OnlyOne throws on an unmatched optional group.
                        matcher.group(group) ?: if (first) throw NullPointerException() else ""
                    }
                    matches.add(RuleValue.Captures(groups))
                    characters += groups.sumOf { it.length.toLong() + 1 }
                    if (characters > budget.limits.maxOutputChars) throw RuleBudgetExceeded()
                    if (first) return matches.first()
                }
                return if (first) RuleValue.Empty else RuleValue.Items(matches)
            }
        }
        return RuleValue.Empty
    }

    fun replace(input: String, pattern: String, replacement: String, first: Boolean, budget: RuleBudget): String {
        if (pattern.isEmpty()) return input
        val regex = try { Pattern.compile(pattern) } catch (_: java.util.regex.PatternSyntaxException) {
            // Pinned replacement semantics use literal replacement for an invalid regex.
            return if (first) replacement else input.replace(pattern, replacement)
        }
        val matcher = regex.matcher(BudgetText(input, budget))
        if (first) return try {
            if (matcher.find()) regex.matcher(BudgetText(matcher.group(), budget)).replaceFirst(replacement) else ""
        } catch (_: IllegalArgumentException) { replacement }
          catch (_: IndexOutOfBoundsException) { replacement }
        val output = StringBuffer()
        try {
            while (matcher.find()) {
                budget.check()
                matcher.appendReplacement(output, replacement)
                budget.checkSize(output.length, budget.limits.maxOutputChars)
            }
            matcher.appendTail(output)
        } catch (_: IllegalArgumentException) { return input.replace(pattern, replacement) }
          catch (_: IndexOutOfBoundsException) { return input.replace(pattern, replacement) }
        budget.checkSize(output.length, budget.limits.maxOutputChars)
        return output.toString()
    }
}
