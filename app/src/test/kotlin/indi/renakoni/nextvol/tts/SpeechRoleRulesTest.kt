package indi.renakoni.nextvol.tts

import hnovel.rules.RegexReplacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechRoleRulesTest {
    private val first = SpeechVoiceRef(httpSource = "first")
    private val second = SpeechVoiceRef(httpSource = "second")
    private fun role(name: String, voice: SpeechVoiceRef = first) = SpeechRoleRule(id = name, name = name, speaker = voice)

    @Test fun contextTemplateAndDirectionSelectTheCandidate() = runBlocking {
        val rules = SpeechRoleRules(listOf(role("甲"), role("乙", second)), "__ROLE__对__OTHER__说") {}
        val match = rules.resolve("甲对乙说：", SpeechContextDirection.Previous)!!
        assertEquals("甲", match.person)
        assertEquals(first, match.voiceRef)
        val default = SpeechRoleRules(listOf(role("甲"), role("乙", second))) {}
        assertEquals("乙", default.resolve("甲对乙说：", SpeechContextDirection.Previous)?.person)
        assertEquals("甲", default.resolve("甲对乙说：", SpeechContextDirection.Next)?.person)
    }

    @Test fun aliasOverlapUsesRegexAlternationAndRuleOrderWithoutLongestNameSorting() = runBlocking {
        val short = role("短名").copy(regex = "小明|阿明")
        val long = role("长名", second).copy(regex = "阿明明")
        val rules = SpeechRoleRules(listOf(short, long)) {}
        val match = rules.resolve("阿明明说：", SpeechContextDirection.Previous)!!
        assertEquals("阿明", match.person)
        assertEquals(short.id, match.ruleId)
        assertEquals(first, rules.resolve("小明", SpeechContextDirection.Explicit)?.voiceRef)
        assertEquals("阿明明", SpeechRoleRules(listOf(long, short)) {}
            .resolve("阿明明说：", SpeechContextDirection.Previous)?.person)
    }

    @Test fun explicitNamesBypassTemplateButRespectActivationDirection() = runBlocking {
        val local = role("甲")
        val afterOnly = role("乙", second).copy(matchBefore = false)
        val rules = SpeechRoleRules(listOf(role("坏").copy(regex = "[", activate = false),
            role("语言").copy(mode = 1, regex = "甲"), local, afterOnly),
            "永远不命中的模板") {}
        assertEquals(local.id, rules.resolve("甲", SpeechContextDirection.Explicit)?.ruleId)
        assertNull(rules.resolve("甲说：", SpeechContextDirection.Previous))
        assertNull(rules.resolve("乙", SpeechContextDirection.Explicit))
    }

    @Test fun abstractRulesKeepDistinctPeopleAndMissingVoiceReferencesForTheAllocator() = runBlocking {
        val rule = role("家人").copy(regex = "小甲|小乙", isAbstract = true, speaker = null)
        val rules = SpeechRoleRules(listOf(rule)) {}
        val firstPerson = rules.resolve("小甲", SpeechContextDirection.Explicit)!!
        val secondPerson = rules.resolve("小乙", SpeechContextDirection.Explicit)!!
        assertEquals(rule.id, firstPerson.ruleId)
        assertEquals(firstPerson.ruleId, secondPerson.ruleId)
        assertNotEquals(firstPerson.person, secondPerson.person)
        assertTrue(firstPerson.isAbstract)
        assertNull(firstPerson.voiceRef)
    }

    @Test fun textRulesClaimOnlyUnmatchedSpansAndRetainOriginalUtf16Ranges() = runBlocking {
        val input = SpeechMappedText.original("前😀甲乙后").slice(1, 5)
        val firstRule = role("表情").copy(mode = 0, regex = "😀甲", matchBefore = false, matchAfter = false)
        val secondRule = role("全部", second).copy(mode = 0, regex = ".+")
        val spans = SpeechRoleRules(listOf(firstRule, secondRule)) {}.splitText(input)
        assertEquals(listOf("😀甲", "乙"), spans.map { it.content.text })
        assertEquals(listOf(firstRule.id, secondRule.id), spans.map { it.match?.ruleId })
        assertEquals(1 to 4, spans.first().content.range())
        assertEquals(4 to 5, spans.last().content.range())
        assertEquals(firstRule.name, spans.first().match?.person)
    }

    @Test fun isolatedRegexFailureNamesItsRuleWithoutSaveTimeOrSuccessPathPrevalidation() = runBlocking {
        val calls = mutableListOf<String>()
        val invalid = role("坏规则").copy(regex = "[", group = "本书")
        val rules = SpeechRoleRules(listOf(role("甲"), invalid), replaceRegex = { text, pattern, target ->
            calls += pattern
            RegexReplacement.replace(text, pattern, target)
        }) {}
        assertTrue(calls.isEmpty())
        try {
            rules.resolve("甲说：", SpeechContextDirection.Previous)
            fail("Expected the invalid rule")
        } catch (failure: SpeechRoleException) {
            assertEquals(invalid.id, failure.failure.ruleId)
            assertEquals("本书 / 坏规则", failure.failure.ruleName)
            assertEquals(SpeechReplacementProblem.Pattern, failure.failure.problem)
        }
        assertEquals(listOf("(甲)|([)", "甲", "["), calls)
        val cancelled = SpeechRoleRules(listOf(role("甲"))) { throw CancellationException("stop") }
        try {
            cancelled.resolve("甲", SpeechContextDirection.Explicit)
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
