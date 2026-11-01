package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechVoiceRoutingTest {
    private val narrator = SpeechVoiceRef(engine = "system", voice = "narrator")
    private val alternate = SpeechVoiceRef(httpSource = "alternate")
    private val online = SpeechVoiceRef(httpSource = "person-a")
    private val offline = SpeechVoiceRef(offlineVoice = OfflineVoiceRef("person-b", "model-v1"))
    private val sameModel = SpeechVoiceRef(offlineVoice = OfflineVoiceRef("person-c", "model-v1"))
    private val settings = SpeechSettings(voiceRoutingConfigured = true,
        narrationVoices = listOf(narrator, alternate), dialogueVoices = listOf(alternate, narrator),
        speakerBookId = "book", speakerVoices = mapOf("甲" to online, "乙" to offline, "丙" to sameModel),
        text = SpeechTextSettings(dialogue = true, separateEnglish = false, maxSentenceLength = 256))

    @Test fun explicitPeopleKeepTheirBackendParametersAndOriginsThroughVoiceSpecificSplits() = runBlocking {
        val chapter = SpeechChapter("book", "one", "", "",
            "前“引文”后。“<<甲>>甲𠀀说了一段比较长的话。”“<<乙>>乙也说了一段话。”" +
                "“<<丙>>丙接着回答。”“<<甲>>甲又回来了。”“未知一。”“未知二。”")
        val configured = settings.copy(replacements = listOf(SpeechReplacementRule(source = "甲", target = "发")))
        val voices = listOf(narrator to 64, alternate to 64, online to 12, offline to 6, sameModel to 9)
            .associate { (reference, limit) -> reference.key to SpeechResolvedVoice(reference,
                configured.forVoice(reference).copy(rate = if (reference == offline) 1.5f else 1f), limit) }
        val plan = prepareSpeechPlan(chapter, configured, maxInputLength = 2, voices = voices) {}
        val people = plan.segments.filter { it.speaker != null }
        val runs = people.fold(mutableListOf<String>()) { result, segment ->
            if (result.lastOrNull() != segment.speaker) result += segment.speaker!!
            result
        }
        assertEquals(listOf("甲", "乙", "丙", "甲"), runs)
        for (segment in people) {
            val expected = voices.getValue(configured.speakerVoices.getValue(segment.speaker!!).key)
            assertEquals(expected, segment.selectedVoice)
            assertTrue(segment.text.length <= expected.maxInputLength)
            assertFalse(segment.text.first().isLowSurrogate())
            assertFalse(segment.text.last().isHighSurrogate())
        }
        assertTrue(people.any { it.text.length > 2 })
        assertTrue(people.filter { it.speaker == "乙" }.size > 1)
        assertEquals(listOf(narrator.key, alternate.key, narrator.key),
            plan.segments.filterNot { it.dialogue }.map { it.selectedVoice!!.reference.key })
        assertEquals(listOf(alternate.key, narrator.key), plan.segments.filter { it.dialogue && it.speaker == null }
            .map { it.selectedVoice!!.reference.key })
        assertTrue(people.none { "<<" in it.text || "甲" in it.text })
        val index = plan.segments.indexOfFirst { "𠀀" in it.text }
        val offset = plan.segments[index].text.indexOf("𠀀")
        val position = plan.position(index, offset, offset + 2)
        assertEquals(chapter.text.indexOf("𠀀"), position.start)
        assertEquals(position.start + 2, position.end)
        assertEquals("𠀀", chapter.text.substring(position.start, position.end))
        assertEquals(0, plan.nextContext.narrationIndex)
        assertEquals(1, plan.nextContext.dialogueIndex)
    }

    @Test fun controlMarksDoNotConsumeTurnsAndUnfinishedDialogueKeepsItsCursorAcrossChapters() = runBlocking {
        val configured = settings.copy(text = settings.text.copy(continueDialogue = true))
        val first = prepareSpeechPlan(SpeechChapter("book", "one", "", "", "[[PAUSE:10]]旁白。“还没说完"), configured) {}
        assertEquals(narrator.key, first.segments.first { it.language != "lang:mark" }.selectedVoice!!.reference.key)
        assertEquals(0, first.nextContext.narrationIndex)
        assertEquals(0, first.nextContext.dialogueIndex)
        assertTrue(first.nextContext.openDialogue)
        val second = prepareSpeechPlan(SpeechChapter("book", "two", "", "", "继续说完。”“另一句。”旁白。"),
            configured, context = first.nextContext) {}
        assertEquals(listOf(alternate.key, narrator.key, alternate.key),
            second.segments.map { it.selectedVoice!!.reference.key })
        assertFalse(second.segments.first().changeVoice)
        assertEquals(1, second.nextContext.narrationIndex)
        assertEquals(1, second.nextContext.dialogueIndex)
        assertEquals(first.nextContext, second.bookmark(second.segments.first().end).context)
        assertEquals(SpeechContext(), first.context)
    }

    @Test fun emptyRememberedSlotsUseCandidatesButMissingReferencesAndClearedCandidatesStayVisible() = runBlocking {
        val chapter = SpeechChapter("book", "one", "", "", "“未识别。”")
        val configured = settings.copy(text = settings.text.copy(recordVoice = true))
        val empty = prepareSpeechPlan(chapter, configured,
            context = SpeechContext(recentVoices = listOf("", online.key))) {}
        assertEquals("", empty.segments.single().previousVoice)
        assertEquals(alternate.key, empty.segments.single().selectedVoice!!.reference.key)
        assertNull(empty.segments.single().selectedVoice!!.error)
        val missing = prepareSpeechPlan(chapter, configured,
            context = SpeechContext(recentVoices = listOf("http:removed", null))) {}
        assertEquals(SpeechError.MissingVoice, missing.segments.single().selectedVoice!!.error)
        val cleared = prepareSpeechPlan(chapter, configured.copy(dialogueVoices = emptyList())) {}
        assertEquals(SpeechError.MissingVoice, cleared.segments.single().selectedVoice!!.error)
        val otherBook = prepareSpeechPlan(chapter.copy(bookId = "other", text = "“<<甲>>你好。”"), configured) {}
        assertNull(otherBook.segments.single().speaker)
        assertEquals("“<<甲>>你好。”", otherBook.segments.single().text)
        assertEquals(alternate.key, otherBook.segments.single().selectedVoice!!.reference.key)
    }
}
