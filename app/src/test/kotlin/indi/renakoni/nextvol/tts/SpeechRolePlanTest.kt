package indi.renakoni.nextvol.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechRolePlanTest {
    private val narrator = SpeechVoiceRef(engine = "system", voice = "narrator")
    private val first = SpeechVoiceRef(httpSource = "first")
    private val second = SpeechVoiceRef(httpSource = "second")
    private val settings = SpeechSettings(voiceRoutingConfigured = true,
        narrationVoices = listOf(narrator), dialogueVoices = listOf(narrator),
        text = SpeechTextSettings(dialogue = true, separateEnglish = false, maxSentenceLength = 256))

    private fun role(name: String, voice: SpeechVoiceRef? = first) = SpeechRoleRule(id = name, name = name, speaker = voice)
    private fun chapter(text: String, book: String = "book", id: String = "one") = SpeechChapter(book, id, "", "", text)

    @Test fun textRulesPreserveQuoteStateWithoutSupplyingOrBlockingRoleContext() = runBlocking {
        val textRule = role("插入", second).copy(mode = 0, regex = "\\[\\[乙“”\\]\\]")
        val configured = settings.copy(roleRules = listOf(textRule, role("甲"), role("乙", second)))
        val plan = prepareSpeechPlan(chapter("甲说：[[乙“”]]“开始[[乙“”]]结束。”"), configured) {}
        val dialogue = plan.segments.filter { it.dialogue }
        assertEquals(2, dialogue.size)
        assertEquals(listOf("甲", "甲"), dialogue.map { it.speaker })
        assertTrue(dialogue.all { it.selectedVoice!!.reference.key == first.key })
        assertTrue(dialogue.first().changeVoice)
        assertFalse(dialogue.last().changeVoice)
        assertEquals(listOf("甲说："), dialogue.first().beforeText)
        val inserted = plan.segments.filter { it.roleMatch?.ruleId == textRule.id }
        assertEquals(2, inserted.size)
        assertTrue(inserted.all { !it.dialogue && it.selectedVoice!!.reference.key == second.key })
        assertTrue(inserted.none { it.language == "lang:mark" })
        assertEquals("甲", plan.nextContext.previousText.single().personVoice?.person)
        assertFalse(plan.nextContext.openDialogue)
    }

    @Test fun matchedPeopleAndOriginsSurvivePronunciationReplacementAndBackendSplitting() = runBlocking {
        val concrete = role("甲").copy(regex = "阿甲|甲")
        val abstract = role("家人", second).copy(regex = "小乙|小丙", isAbstract = true)
        val configured = settings.copy(roleRules = listOf(concrete, abstract), replacements = listOf(
            SpeechReplacementRule(source = "甲", target = "发"),
            SpeechReplacementRule(source = "重庆", target = "崇庆")))
        val input = chapter("“<<阿甲>>甲𠀀重庆。”“<<小乙>>重庆。”“<<小丙>>重庆。”")
        val voices = listOf(narrator to 256, first to 4, second to 6).associate { (reference, limit) ->
            reference.key to SpeechResolvedVoice(reference, configured.forVoice(reference), limit)
        }
        val plan = prepareSpeechPlan(input, configured, voices = voices) {}
        assertEquals(listOf("阿甲", "小乙", "小丙"), plan.segments.mapNotNull { it.roleMatch?.person }.distinct())
        assertTrue(plan.segments.all { "<<" !in it.text && "重庆" !in it.text && "甲" !in it.text })
        val family = plan.segments.filter { it.roleMatch?.isAbstract == true }
        assertEquals(setOf("小乙", "小丙"), family.map { it.roleMatch!!.person }.toSet())
        assertTrue(family.all { it.roleMatch!!.ruleId == abstract.id })
        for (segment in plan.segments) {
            val match = segment.roleMatch!!
            assertEquals(match.person, segment.speaker)
            assertEquals(match.voiceRef!!.key, segment.selectedVoice!!.reference.key)
            assertTrue(segment.text.length <= segment.selectedVoice!!.maxInputLength)
        }
        val index = plan.segments.indexOfFirst { "𠀀" in it.text }
        val offset = plan.segments[index].text.indexOf("𠀀")
        val original = plan.position(index, offset, offset + 2)
        assertEquals(input.text.indexOf("𠀀"), original.start)
        assertEquals("𠀀", input.text.substring(original.start, original.end))
    }

    @Test fun globalRulesAndMissingSourcesNeverChooseAnotherConfiguredVoice() = runBlocking {
        val local = role("甲")
        val missing = role("乙", null)
        val unavailable = role("丙", second)
        val configured = settings.copy(roleRules = listOf(local, missing, unavailable))
        val voices = mapOf(second.key to SpeechResolvedVoice(second, configured.forVoice(second), 256,
            error = SpeechError.HttpSourceUnavailable))
        val plan = prepareSpeechPlan(chapter("“<<甲>>你好。”“<<乙>>你好。”“<<丙>>你好。”"), configured, voices = voices) {}
        assertEquals(first.key, plan.segments.first().selectedVoice!!.reference.key)
        val noSelection = plan.segments.first { it.speaker == "乙" }
        assertEquals(missing.id, noSelection.roleMatch?.ruleId)
        assertEquals(SpeechError.MissingVoice, noSelection.selectedVoice!!.error)
        assertNotEquals(narrator.key, noSelection.selectedVoice!!.reference.key)
        val deleted = plan.segments.first { it.speaker == "丙" }
        assertEquals(second.key, deleted.selectedVoice!!.reference.key)
        assertEquals(SpeechError.HttpSourceUnavailable, deleted.selectedVoice!!.error)
        val otherBook = prepareSpeechPlan(chapter("“<<甲>>你好。”", book = "other"), configured, voices = voices) {}
        assertEquals(local.id, otherBook.segments.single().roleMatch?.ruleId)
        assertEquals("甲", otherBook.segments.single().speaker)
        assertFalse("<<甲>>" in otherBook.segments.single().text)
        assertEquals(first.key, otherBook.segments.single().selectedVoice!!.reference.key)
    }

    @Test fun roleRulesOverrideExplicitAssignmentsAndTheirContinuedContext() = runBlocking {
        val assigned = settings.copy(speakerVoices = mapOf("甲" to first), speakerBookId = "book",
            text = settings.text.copy(continueDialogue = true))
        val opening = chapter("“<<甲>>开始")
        val before = prepareSpeechPlan(opening, assigned) {}
        assertEquals(first.key, before.segments.single().selectedVoice!!.reference.key)
        assertNull(before.segments.single().roleMatch)
        assertTrue(before.nextContext.openDialogue)

        val rule = role("甲", second)
        val configured = assigned.copy(roleRules = listOf(rule))
        val explicit = prepareSpeechPlan(opening, configured) {}
        val continued = prepareSpeechPlan(chapter("接着说。”", id = "two"), configured,
            context = before.nextContext) {}
        for (plan in listOf(explicit, continued)) {
            assertEquals("甲", plan.segments.single().speaker)
            assertEquals(rule.id, plan.segments.single().roleMatch?.ruleId)
            assertEquals(second.key, plan.segments.single().selectedVoice!!.reference.key)
        }
    }

    @Test fun continuedDialogueUsesTheEditedRoleVoiceInsteadOfItsPreviousContextSnapshot() = runBlocking {
        val rule = role("甲")
        val configured = settings.copy(roleRules = listOf(rule), text = settings.text.copy(continueDialogue = true))
        val before = prepareSpeechPlan(chapter("“<<甲>>开始"), configured) {}
        assertTrue(before.nextContext.openDialogue)
        val edited = configured.copy(roleRules = listOf(rule.copy(speaker = second)))
        val after = prepareSpeechPlan(chapter("接着说。”", id = "two"), edited, context = before.nextContext) {}
        assertEquals("甲", after.segments.single().speaker)
        assertEquals(rule.id, after.segments.single().roleMatch?.ruleId)
        assertEquals(second.key, after.segments.single().selectedVoice!!.reference.key)
    }
}
