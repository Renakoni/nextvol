package hnovel.rules

import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class RegexReplacementTest {
    @Test fun namedGroupNumbersRespectQuotedTextClassesAndScopedComments() {
        val examples = listOf(
            Triple("(甲)乙", "\\Q(甲)\\E(?<name>乙)", "\${name}"),
            Triple("]甲", "([]()])(?<name>甲)", "$1\${name}"),
            Triple("甲乙", "(?x)(甲) # (ignored)\n(?-x:(?<name>乙))", "\${name}$1"),
            Triple("甲乙", "(?<name>甲)(乙)", "\${name}$2$0"),
        )
        for ((input, source, target) in examples) {
            val parts = RegexReplacement.replace(input, source, target)
            val output = parts.joinToString("") { it.text ?: input.substring(it.start, it.end) }
            assertEquals(Pattern.compile(source).matcher(input).replaceAll(target), output)
        }
        assertEquals(listOf(ReplacementPart(0, 1)), RegexReplacement.replace("甲甲", "(?<first>甲)(甲)", "\${first}"))
    }
}
