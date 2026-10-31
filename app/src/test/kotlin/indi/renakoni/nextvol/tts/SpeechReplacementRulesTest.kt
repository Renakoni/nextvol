package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechReplacementRulesTest {
    private fun literal(source: String, target: String) = SpeechReplacementRule(source = source, target = target)
    private fun regex(source: String, target: String) = literal(source, target).copy(regex = true)
    private fun apply(text: String, rules: List<SpeechReplacementRule>, wholeText: Boolean = false) = runBlocking {
        SpeechReplacements(rules) {}.apply(SpeechMappedText.original(text), wholeText)
    }

    @Test fun literalBatchMatchesOriginalReplayAndRegexAlwaysFollowsIt() {
        // MultiTTS 1.8.3 p096n6.a.a: earliest input match, list order breaks ties, no rescan.
        assertEquals("乙丙", apply("甲乙", listOf(literal("甲", "乙"), literal("乙", "丙"))).text)
        assertEquals("X", apply("ab", listOf(literal("ab", "X"), literal("a", "Y"))).text)
        assertEquals("Yb", apply("ab", listOf(literal("a", "Y"), literal("ab", "X"))).text)
        assertEquals("乙丙", apply("甲", listOf(regex("(乙)", "$1丙"), literal("甲", "乙"), literal("", "bad"))).text)
        assertEquals("$1", apply("甲", listOf(literal("甲", "$1"))).text)
    }

    @Test fun capturesUseJavaReplacementGrammarIncludingUnmatchedGroupsAndEscaping() {
        val fixtures = listOf(
            Triple("甲乙", "(甲)(乙)", "$2-$1-$0") to "乙-甲-甲乙",
            Triple("乙", "(甲)?乙", "$1丙") to "丙",
            Triple("张三", "(?<person>张三)", "\${person}先生") to "张三先生",
            Triple("甲", "(?=甲)", "乙") to "乙甲",
            Triple("😀甲", "(😀)(甲)", "$2$1") to "甲😀",
            Triple("甲", "(甲)", "\\$1") to "$1",
            Triple("甲", "(甲)", "$12") to "甲2",
        )
        for ((fixture, expected) in fixtures) {
            val (input, source, target) = fixture
            assertEquals(expected, apply(input, listOf(regex(source, target))).text)
        }
    }

    @Test fun stageUsesRawStructureCharactersAndGroupDoesNotRestrictApplication() = runBlocking {
        for (source in listOf("[甲乙]", "甲|乙", "(?:甲)", "「")) assertTrue(literal(source, "").wholeText)
        for (source in listOf("(?<person>甲)", "(?=甲)", "甲")) assertFalse(literal(source, "<<张三>>").wholeText)
        val open = literal("「", "“<<张三>>").copy(group = "书中人物")
        val rules = listOf(literal("重庆", "崇庆"), open, literal("」", "”"))
        val chapter = SpeechChapter("book", "chapter", "", "", "旁白。\n「重庆」")
        val plan = prepareSpeechPlan(chapter, SpeechSettings(replacements = rules)) {}
        assertEquals("旁白。\n“<<张三>>重庆”", plan.structuredText)
        val dialogue = plan.segments.last()
        assertTrue(dialogue.dialogue)
        assertEquals("张三", dialogue.speaker)
        assertEquals("“崇庆”", dialogue.text)
        assertEquals(4, dialogue.start)
        assertEquals(8, dialogue.end)
        assertEquals(chapter.fingerprint, plan.position(1).fingerprint)
    }

    @Test fun expansionDeletionAndSuccessiveCapturesRetainOriginalCodePointRanges() {
        val output = apply("甲😀乙丙丁", listOf(literal("甲", "开头"), literal("乙", ""),
            literal("丙", "中"), regex("(😀)(中)", "$2$1!"), regex("!", "尾")))
        assertEquals("开头中😀尾丁", output.text)
        assertEquals(0 to 1, output.range(0, 2))
        assertEquals(4 to 5, output.range(2, 3))
        assertEquals(1 to 3, output.range(3, 5))
        assertEquals(1 to 5, output.range(5, 6))
        assertEquals(5 to 6, output.range(6, 7))
        assertEquals(1, output.anchor(4)) // Low surrogate still follows the complete original code point.
        val inserted = apply("😀甲", listOf(regex("(?=😀)", "前")))
        assertEquals(0 to 2, inserted.range(0, 1))
    }

    @Test fun expansionClipsResumeByPlanIndexAndChangedRulesFallBackToOriginalText() = runBlocking {
        val chapter = SpeechChapter("book", "chapter", "", "", "甲乙")
        val rules = listOf(literal("甲", "字".repeat(450)))
        val plan = prepareSpeechPlan(chapter, SpeechSettings(replacements = rules)) {}
        assertTrue(plan.segments.size > 2)
        assertEquals(plan.segments[0].start, plan.segments[1].start)
        val boundary = plan.bookmark(plan.segments[0].end, 1)
        assertEquals(1, plan.startIndex(boundary))
        assertEquals(1, prepareSpeechPlan(chapter, SpeechSettings(replacements = rules, httpSource = "online")) {}.startIndex(boundary))
        val changed = prepareSpeechPlan(chapter, SpeechSettings(replacements = listOf(literal("乙", "丙")))) {}
        assertEquals(0, changed.startIndex(boundary))
        assertEquals(0, plan.startIndex(boundary.copy(fingerprint = "stale")))
        assertEquals(plan.segments.size, plan.startIndex(plan.bookmark(chapter.text.length, plan.segments.size)))
    }

    @Test fun insertionAfterCompleteDeletionRetainsTheDeletedOriginalRange() = runBlocking {
        val chapter = SpeechChapter("book", "chapter", "", "", "😀甲")
        for (delete in listOf(literal("😀甲", ""), regex(".+", ""))) {
            val plan = prepareSpeechPlan(chapter,
                SpeechSettings(replacements = listOf(delete, regex("^$", "替代内容")))) {}
            assertEquals("替代内容", plan.segments.single().text)
            assertEquals(0, plan.position(0).start)
            assertEquals(chapter.text.length, plan.position(0).end)
        }
    }

    @Test fun deletionProducesNoEmptySynthesisSegmentsAndInactiveInvalidRulesDoNotRun() = runBlocking {
        val chapter = SpeechChapter("book", "chapter", "", "", "甲\n乙")
        val plan = prepareSpeechPlan(chapter, SpeechSettings(replacements = listOf(regex(".+", ""),
            regex("(", "").copy(activate = false)))) {}
        assertTrue(plan.segments.isEmpty())
        val invalid = regex("(", "").copy(id = "bad-pattern", name = "坏正则")
        val failure = assertThrows(SpeechReplacementException::class.java) { apply("甲", listOf(invalid)) }.failure
        assertEquals("bad-pattern", failure.ruleId)
        assertEquals(SpeechReplacementProblem.Pattern, failure.problem)
        val capture = assertThrows(SpeechReplacementException::class.java) { apply("甲", listOf(regex("甲", "$1"))) }.failure
        assertEquals(SpeechReplacementProblem.Replacement, capture.problem)
    }

    @Test(timeout = 6000) fun pathologicalRegexStopsInsideMatcherAndNamesTheRule() {
        val rule = regex("(a+)+$", "").copy(id = "slow", name = "回溯")
        val failure = assertThrows(SpeechReplacementException::class.java) { apply("a".repeat(30_000) + "!", listOf(rule)) }
        assertEquals("slow", failure.failure.ruleId)
        assertEquals(SpeechReplacementProblem.Budget, failure.failure.problem)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                SpeechReplacements(listOf(rule)) { if (++checks == 20) throw CancellationException("Stop") }
                    .apply(SpeechMappedText.original("a".repeat(30_000) + "!"), false)
            }
        }
        assertEquals(20, checks)
    }
}
