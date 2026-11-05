package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import org.junit.Assert.*
import org.junit.Test

class SpeechTextAnalysisTest {
    private fun candidate(name: String, matches: Boolean = true, selected: Boolean = true, hint: SpeechNameHint = SpeechNameHint.Male) =
        SpeechTextAnalysisCandidate(name, name, 90,
            SpeechNameClassification(hint, SpeechNameHintBasis.Model), matches, selected)

    @Test fun automaticSelectionOnlyCountsNewMatchingNamesAndDoesNotLimitManualSaving() {
        val ordinary = candidate("窗边", matches = false)
        val existing = candidate("林逸").copy(existingRoleId = "saved")
        val unknown = candidate("小甲", hint = SpeechNameHint.Unknown)
        assertEquals(SpeechNameHint.Unknown, unknown.classification.hint)
        assertEquals(SpeechNameHint.Male, unknown.gender)
        val candidates = selectSpeechAnalysisCandidates(listOf(ordinary, existing, unknown, candidate("小乙")), 1)
        assertEquals(listOf("林逸", "小甲", "小乙", "窗边"), candidates.map { it.name })
        assertEquals(listOf("小甲"), candidates.filter { it.selected }.map { it.name })
        val saved = mergeSpeechAnalysisRoles(SpeechSettings(), "本书", candidates.filter { it.existingRoleId == null }
            .map { it.copy(selected = true) })
        assertEquals(3, saved.roleRules.size)
        assertTrue(saved.roleRules.all { it.isAbstract && it.speaker?.voice == SPEECH_POOL_MALE })
    }

    @Test fun mergingAliasesKeepsTargetStatisticAndStableIdentityWithoutChangingOtherRulesOrBindings() {
        val unrelated = SpeechRoleRule(name = "旁人", group = "另一本书")
        val original = mergeSpeechAnalysisRoles(SpeechSettings(roleRules = listOf(unrelated),
            speakerBookId = "other-book", speakerVoices = mapOf("旁人" to SpeechVoiceRef(httpSource = "manual"))),
            "本书", listOf(candidate("林逸"), candidate("小林")))
        val targetRule = original.roleRules.first { it.name == "林逸" }
        val candidates = listOf(candidate("林逸").copy(existingRoleId = targetRule.id, aliases = listOf("小林")),
            candidate("小林").copy(value = 40))
        val merged = mergeSpeechAnalysisCandidates(candidates, "林逸")
        assertEquals(90, merged.single().value)
        assertEquals(listOf("小林"), merged.single().aliases)
        val saved = mergeSpeechAnalysisRoles(original, "本书", merged)
        assertEquals(2, saved.roleRules.size)
        assertEquals(unrelated, saved.roleRules.first())
        val person = saved.roleRules.last()
        assertEquals(targetRule.id, person.id)
        assertEquals(SpeechRolePerson("林逸", listOf("小林")), person.person)
        assertEquals(original.speakerVoices, saved.speakerVoices)
        assertEquals(original.speakerBookId, saved.speakerBookId)
        assertEquals(saved, mergeSpeechAnalysisRoles(saved, "本书", merged))
        assertEquals(saved, mergeSpeechAnalysisRoles(saved, "本书", listOf(candidate("小林").copy(existingRoleId = person.id))))
    }

    @Test fun editedAliasesMergeLiteralRolesAcrossGroupsButDoNotNarrowArbitraryRegexOrTextRules() {
        val explicit = SpeechRoleRule(name = "原人物", regex = "林逸", group = "已有", speaker = SpeechVoiceRef(httpSource = "manual"))
        val group = SpeechRoleRule(name = "男性", regex = "(小林|张三)", group = "导入", isAbstract = true,
            speaker = SpeechVoiceRef(voice = SPEECH_POOL_MALE))
        val effect = SpeechRoleRule(name = "阿林", regex = "林逸", mode = 0)
        val regex = SpeechRoleRule(name = "其他匹配", regex = "阿.*")
        val current = SpeechSettings(roleRules = listOf(group, effect, explicit, regex))
        assertEquals(current, mergeSpeechAnalysisRoles(current, "新文本", listOf(candidate("小林").copy(existingRoleId = group.id))))
        val edited = candidate("阿林").copy(aliases = listOf("林逸", "小林"))
        val saved = mergeSpeechAnalysisRoles(current, "新文本", listOf(edited))
        assertEquals(listOf(explicit.id, group.id, effect.id, regex.id), saved.roleRules.map { it.id })
        assertEquals("林逸", saved.roleRules.first().person!!.name)
        assertEquals(explicit.speaker, saved.roleRules.first().speaker)
        assertEquals(listOf("阿林", "小林"), saved.roleRules.first().person!!.aliases)
        assertEquals(group, saved.roleRules[1])
        assertEquals(regex, saved.roleRules.last())
    }

    @Test fun quotedNamesRemainLiteral() {
        val unusual = "林|逸\\E"
        val quoted = SpeechRoleRule(name = "字面人物", regex = java.util.regex.Pattern.quote(unusual))
        val quotedMerged = mergeSpeechAnalysisRoles(SpeechSettings(roleRules = listOf(quoted)), "文本",
            listOf(candidate("新别名").copy(aliases = listOf(unusual))))
        assertEquals(1, quotedMerged.roleRules.size)
        assertEquals(unusual, quotedMerged.roleRules.single().person!!.name)
        assertEquals(quoted.id, quotedMerged.roleRules.single().id)
    }

    @Test fun mergingAKnownCandidateRetainsItsMatchedRuleWithoutInventingNamesFromTheRegex() {
        val group = SpeechRoleRule(name = "匹配规则", regex = "小[林张]", isAbstract = true,
            speaker = SpeechVoiceRef(voice = SPEECH_POOL_MALE))
        val merged = mergeSpeechAnalysisCandidates(listOf(candidate("林逸").copy(aliases = listOf("小林")),
            candidate("小林").copy(existingRoleId = group.id)), "林逸")
        val saved = mergeSpeechAnalysisRoles(SpeechSettings(roleRules = listOf(group)), "文本", merged)
        assertEquals(group, saved.roleRules.last())
        assertEquals(SpeechRolePerson("林逸", listOf("小林")), saved.roleRules.first().person)
        assertFalse(saved.roleRules.first().pattern.contains("小张"))
    }

    @Test fun editsRespectRemovedAliasesAndResolveOnlyActualNameConflicts() {
        val initial = mergeSpeechAnalysisRoles(SpeechSettings(), "文本", listOf(candidate("林逸").copy(aliases = listOf("小林"))))
        val role = initial.roleRules.single()
        val name = candidate("林逸").copy(existingRoleId = role.id, aliases = emptyList(),
            originalAliases = listOf("小林"), edited = true)
        val alias = candidate("小林", selected = false).copy(existingRoleId = role.id, aliases = listOf("林逸"))
        val edited = mergeSpeechAnalysisCandidates(listOf(name, alias), "林逸")
        assertEquals(2, edited.size)
        val saved = mergeSpeechAnalysisRoles(initial, "文本", edited)
        assertEquals(SpeechRolePerson("林逸"), saved.roleRules.single().person)
        assertEquals(role.id, saved.roleRules.single().id)

        val labelConflict = SpeechRoleRule(name = "林逸", regex = "赵灵儿", group = "文本")
        val replaced = mergeSpeechAnalysisRoles(SpeechSettings(roleRules = listOf(labelConflict)), "文本", listOf(candidate("林逸")))
        assertEquals(labelConflict.id, replaced.roleRules.single().id)
        assertEquals(SpeechRolePerson("林逸"), replaced.roleRules.single().person)
        assertFalse(replaced.roleRules.single().pattern.contains("赵灵儿"))
    }
}
