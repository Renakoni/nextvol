package indi.renakoni.nextvol.data.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingRuleProcessorTest {
    @Test fun literalRulesUnescapeBothSidesAndKeepGroupReferencesLiteral() {
        val rule = FormattingRule(name = "literal", isRegex = false, match = """\n""",
            replacement = """\t\u0041\"\\\r\b\f$1""", isEnabled = true)

        assertEquals("\tA\"\\\r\b\u000c\$1", applyFormattingRules("\n", listOf(rule)))
    }

    @Test fun regexReplacementsDoNotReprocessGeneratedText() {
        val rule = FormattingRule(name = "repeat", isRegex = true, match = "(a)", replacement = "\$1\$1", isEnabled = true)

        assertEquals("aaaa", applyFormattingRules("aa", listOf(rule)))
    }

    @Test fun regexReplacementsOnlyChangeMatchedPositions() {
        val rule = FormattingRule(name = "context", isRegex = true, match = "(?<=x)a", replacement = "b", isEnabled = true)

        assertEquals("a xb", applyFormattingRules("a xa", listOf(rule)))
    }

    @Test fun zeroLengthMatchesArePreservedAlongsideNonEmptyMatches() {
        val rule = FormattingRule(name = "boundaries", isRegex = true, match = "^|b|$", replacement = "_", isEnabled = true)

        assertEquals("_a__", applyFormattingRules("ab", listOf(rule)))
    }

    @Test fun numericReferencesHandleWholeMatchOptionalAndMissingGroups() {
        val rule = FormattingRule(name = "groups", isRegex = true, match = """(\u0061)(b)?""",
            replacement = "\$0:\$1:\$2:\$10:\$9:\$", isEnabled = true)

        assertEquals("a:a::a0:\$9:\$", applyFormattingRules("a", listOf(rule)))
    }

    @Test fun multiDigitReferencesUseTheLongestExistingGroupNumber() {
        val rule = FormattingRule(name = "ten groups", isRegex = true,
            match = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", replacement = "\$10:\$1", isEnabled = true)

        assertEquals("j:a", applyFormattingRules("abcdefghij", listOf(rule)))
    }

    @Test fun capturedTextIsNotParsedAsAReplacementTemplate() {
        val rule = FormattingRule(name = "captured text", isRegex = true,
            match = "(.*)-(.*)", replacement = "\$1\\n\$2\\\\", isEnabled = true)

        assertEquals("\$2\\原文\n正文\\", applyFormattingRules("\$2\\原文-正文", listOf(rule)))
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
