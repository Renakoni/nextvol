package indi.renakoni.nextvol.data.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingRuleProcessorTest {
    @Test fun literalRulesUnescapeBothSidesAndKeepGroupReferencesLiteral() {
        val rule = FormattingRule(name = "literal", isRegex = false, match = """\n""",
            replacement = """\t\u0041\"\\\r\b\f$1""", isEnabled = true)

        assertEquals("\tA\"\\\r\b\u000c\$1", applyFormattingRules("\n", listOf(rule)))
    }

    @Test fun repeatedRegexMatchesAlsoReplaceTextProducedByEarlierMatches() {
        val rule = FormattingRule(name = "repeat", isRegex = true, match = "(a)", replacement = "\$1\$1", isEnabled = true)

        assertEquals("aaaaaaaa", applyFormattingRules("aa", listOf(rule)))
    }

    @Test fun captureSubstitutionKeepsMissingGroupsAndReplacesNumberPrefixesInOrder() {
        val rule = FormattingRule(name = "groups", isRegex = true, match = """(\u0061)(b)?""",
            replacement = "\$0:\$1:\$2:\$10", isEnabled = true)

        assertEquals("a:a:\$2:a0", applyFormattingRules("a", listOf(rule)))
    }

    @Test fun disabledAndInvalidRulesDoNotPreventFollowingRulesFromRunningInOrder() {
        val rules = listOf(
            FormattingRule(name = "disabled", isRegex = false, match = "a", replacement = "wrong", isEnabled = false),
            FormattingRule(name = "invalid", isRegex = true, match = "[", replacement = "wrong", isEnabled = true),
            FormattingRule(name = "first", isRegex = true, match = "a", replacement = "b", isEnabled = true),
            FormattingRule(name = "second", isRegex = false, match = "b", replacement = "c", isEnabled = true),
        )

        assertEquals("c", applyFormattingRules("a", rules))
    }
}
