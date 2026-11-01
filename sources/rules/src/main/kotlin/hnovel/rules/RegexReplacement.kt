package hnovel.rules

import kotlinx.serialization.Serializable
import java.util.regex.Pattern

/** Null text copies the input range; literal text is attributed to the whole matched range. */
@Serializable
data class ReplacementPart(val start: Int, val end: Int, val text: String? = null)

/** Java replacement semantics with provenance, run inside the worker deadline on Android. */
object RegexReplacement {
    fun replace(input: String, source: String, target: String, budget: RuleBudget = RuleBudget(),
        checkCancelled: () -> Unit = {}): List<ReplacementPart> {
        val matcher = Pattern.compile(source).matcher(BudgetText(input, budget, checkCancelled))
        val names by lazy { namedGroups(source) }
        val parts = mutableListOf<ReplacementPart>()
        var length = 0L
        fun append(start: Int, end: Int, text: String? = null) {
            length += text?.length ?: (end - start)
            budget.check()
            if (length > budget.limits.maxOutputChars || parts.size >= budget.limits.maxOutputChars) throw RuleBudgetExceeded()
            if (text == "" || text == null && start == end) return
            val previous = parts.lastOrNull()
            if (text == null && previous?.text == null && previous != null && previous.end == start)
                parts[parts.lastIndex] = previous.copy(end = end)
            else parts += ReplacementPart(start, end, text)
        }
        var copied = 0
        while (matcher.find()) {
            checkCancelled()
            append(copied, matcher.start())
            var index = 0
            val literal = StringBuilder()
            fun flush() { append(matcher.start(), matcher.end(), literal.toString()); literal.setLength(0) }
            while (index < target.length) {
                budget.check()
                when (val char = target[index++]) {
                    '\\' -> {
                        require(index < target.length) { "Missing escaped character" }
                        literal.append(target[index++])
                    }
                    '$' -> {
                        flush()
                        require(index < target.length) { "Missing group reference" }
                        val group: Int
                        if (target[index] == '{') {
                            val end = target.indexOf('}', ++index)
                            require(end >= 0) { "Missing group end" }
                            val name = target.substring(index, end)
                            group = names[name] ?: throw IllegalArgumentException("Unknown capture group")
                            index = end + 1
                        } else {
                            require(target[index] in '0'..'9') { "Invalid group reference" }
                            var number = target[index++].digitToInt()
                            while (index < target.length && target[index] in '0'..'9') {
                                val next = number.toLong() * 10 + target[index].digitToInt()
                                if (next > matcher.groupCount()) break
                                number = next.toInt()
                                index++
                            }
                            group = number
                        }
                        val start = matcher.start(group)
                        if (start >= 0) append(start, matcher.end(group))
                    }
                    else -> literal.append(char)
                }
            }
            flush()
            copied = matcher.end()
        }
        append(copied, input.length)
        return parts
    }

    /** Resolve names to numbered groups so API 24/25 need no Matcher.start(String) API. */
    private fun namedGroups(pattern: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        var group = 0
        var index = 0
        var comments = false
        val scopes = ArrayDeque<Boolean>()
        val classes = ArrayDeque<Boolean>()
        while (index < pattern.length) {
            val char = pattern[index++]
            if (comments && char == '#') {
                while (index < pattern.length && pattern[index] !in "\n\r\u0085\u2028\u2029") index++
                continue
            }
            if (comments && char in " \t\n\r\u000b\u000c") continue
            if (char == '\\') {
                if (pattern.getOrNull(index) == 'Q') {
                    val end = pattern.indexOf("\\E", index + 1)
                    index = if (end < 0) pattern.length else end + 2
                } else index++
                if (classes.isNotEmpty()) { classes.removeLast(); classes.addLast(false) }
                continue
            }
            if (char == '[') {
                if (classes.isNotEmpty()) { classes.removeLast(); classes.addLast(false) }
                classes.addLast(true)
                continue
            }
            if (classes.isNotEmpty()) {
                val first = classes.removeLast()
                if (char != ']' || first) classes.addLast(first && char == '^')
                continue
            }
            if (char == ')') { comments = scopes.removeLast(); continue }
            if (char != '(') continue
            scopes.addLast(comments)
            if (pattern.getOrNull(index) != '?') { group++; continue }
            if (pattern.getOrNull(index + 1) == '<' && pattern.getOrNull(index + 2) !in listOf('=', '!')) {
                val end = pattern.indexOf('>', index + 2)
                result[pattern.substring(index + 2, end)] = ++group
                index = end + 1
                continue
            }
            var flag = index + 1
            var enabled = true
            while (flag < pattern.length && pattern[flag] in "idmsuxU-") {
                if (pattern[flag] == '-') enabled = false
                if (pattern[flag] == 'x') comments = enabled
                flag++
            }
            if (flag > index + 1 && pattern.getOrNull(flag) in listOf(':', ')')) {
                if (pattern[flag] == ')') scopes.removeLast()
                index = flag + 1
            }
        }
        return result
    }
}
