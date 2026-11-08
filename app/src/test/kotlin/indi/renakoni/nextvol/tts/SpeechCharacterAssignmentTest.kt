package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SpeechCharacterAssignmentTest {
    private val narrator = SpeechVoiceRef(httpSource = "narrator")
    private val dialogue = SpeechVoiceRef(httpSource = "dialogue")
    private val male = SpeechVoiceRef(httpSource = "male")
    private val female = SpeechVoiceRef(httpSource = "female")
    private val catalog = listOf(narrator, dialogue, male, female).associate { it.key to
        SpeechResolvedVoice(it, SpeechSettings(httpSource = it.httpSource), 256) }

    private fun configured() = SpeechSettings(
        narrationVoices = listOf(narrator), dialogueVoices = listOf(dialogue), voiceRoutingConfigured = true,
        text = SpeechTextSettings(dialogue = true, separateEnglish = false), autoAssignRole = true,
        voiceItems = listOf(SpeechVoiceItem(SPEECH_POOL_MALE, male), SpeechVoiceItem(SPEECH_POOL_FEMALE, female)),
    )

    @Test fun abstractRuleAllocatesActualDialogueVoicesWithAutoDiscoveryOffAndKeepsThemAcrossChapters() = runBlocking {
        val rule = SpeechRoleRule(name = "青年", regex = "小甲|小乙", isAbstract = true)
        val fixture = Fixture(configured().copy(autoAssignRole = false, roleRules = listOf(rule),
            voiceItems = listOf(SpeechVoiceItem("青年", male), SpeechVoiceItem("青年", female))))
        val first = fixture.prepare("小甲说：“你好。”小乙说：“再见。”")
        val spoken = first.segments.filter { it.dialogue }
        assertEquals(setOf(male.key, female.key), spoken.map { it.selectedVoice!!.reference.key }.toSet())
        assertTrue(spoken.all { it.roleMatch?.poolGroup == "青年" && it.selectedVoice?.error == null })
        assertTrue(first.segments.filterNot { it.dialogue }.all { it.selectedVoice!!.reference == narrator })
        assertEquals(2, fixture.saved.size)
        val assigned = fixture.saved.first { it.person == "小甲" }.value
        fixture.pool = SpeechVoicePool(Random(99))
        val next = fixture.prepare("小甲说：“还是我。”", "next")
        assertEquals(assigned, next.segments.single { it.dialogue }.selectedVoice!!.reference)
        assertEquals(2, fixture.saved.size)
        assertTrue(fixture.recognized.isEmpty())
        assertTrue(fixture.newWordCalls.isEmpty())
        assertTrue(fixture.classified.isEmpty())
    }

    @Test fun savedAlgorithmChangesTheDiscoveryPathAndTheVoiceUsedByTheBody() = runBlocking {
        val ner = Fixture()
        val nerSegment = ner.prepare("小甲与小乙说：“你好。”").segments.single { it.dialogue }
        assertEquals("小甲", nerSegment.speaker)
        assertEquals(male, nerSegment.selectedVoice!!.reference)
        assertEquals(SpeechPersonOrigin.Ner, nerSegment.roleMatch!!.origin)
        assertTrue(ner.recognized.isNotEmpty())
        assertTrue(ner.newWordCalls.isEmpty())

        val p6 = Fixture(configured().copy(text = configured().text.copy(nameDiscoveryAlgorithm = 100)))
        val p6Segment = p6.prepare("小甲与小乙说：“你好。”").segments.single { it.dialogue }
        assertEquals("小乙", p6Segment.speaker)
        assertEquals(female, p6Segment.selectedVoice!!.reference)
        assertEquals(SpeechPersonOrigin.NewWord, p6Segment.roleMatch!!.origin)
        assertTrue(p6.recognized.isEmpty())
        assertTrue(p6.newWordCalls.isNotEmpty())
        assertTrue(p6.newWordCalls.all { it.first == "小甲与小乙说：" && it.second.isNotEmpty() && it.third })
    }

    @Test fun manualBindingPrecedesConcreteRulesAndSavedGenderPairPrecedesAnotherClassification() = runBlocking {
        val manual = Fixture(configured().copy(roleRules = listOf(SpeechRoleRule(name = "小甲", speaker = male))))
        manual.saved = listOf(SpeechVoicePair("book", "小甲", SPEECH_POOL_FEMALE, female, manual = true))
        val manualSegment = manual.prepare("小甲说：“用我指定的声音。”").segments.single { it.dialogue }
        assertEquals(female, manualSegment.selectedVoice!!.reference)
        assertEquals(SpeechPersonOrigin.Manual, manualSegment.roleMatch!!.origin)
        assertTrue(manual.classified.isEmpty())

        val existing = Fixture(hint = SpeechNameHint.Unknown)
        existing.saved = listOf(SpeechVoicePair("book", "小甲", SPEECH_POOL_FEMALE, female))
        val reused = existing.prepare("小甲说：“声音不变。”").segments.single { it.dialogue }
        assertEquals(female, reused.selectedVoice!!.reference)
        assertEquals(SpeechPersonOrigin.Pair, reused.roleMatch!!.origin)
        assertTrue(existing.classified.isEmpty())
    }

    @Test fun unknownClassificationAndEmptyPoolUseTheDialogueCandidateWithoutSavingAnAssignment() = runBlocking {
        val unknown = Fixture(hint = SpeechNameHint.Unknown)
        val empty = Fixture(configured().copy(voiceItems = emptyList()))
        for (fixture in listOf(unknown, empty)) {
            val segment = fixture.prepare("小甲说：“你好。”").segments.single { it.dialogue }
            assertEquals(dialogue, segment.selectedVoice!!.reference)
            assertNull(segment.selectedVoice!!.error)
            assertTrue(fixture.saved.isEmpty())
        }
        val disabled = Fixture(configured().copy(autoAssignRole = false))
        assertEquals(dialogue, disabled.prepare("小甲说：“你好。”").segments.single { it.dialogue }.selectedVoice!!.reference)
        assertTrue(disabled.recognized.isEmpty())
        assertTrue(disabled.classified.isEmpty())
    }

    @Test fun abstractRuleUsesItsDeclaredFallbackWhenItsPoolIsEmpty() = runBlocking {
        val rule = SpeechRoleRule(name = "青年", regex = "小甲", isAbstract = true, speaker = female)
        val fixture = Fixture(configured().copy(roleRules = listOf(rule), autoAssignRole = false))
        val segment = fixture.prepare("小甲说：“你好。”").segments.single { it.dialogue }
        assertEquals(female, segment.selectedVoice!!.reference)
        assertEquals("青年", segment.roleMatch!!.poolGroup)
        assertTrue(fixture.saved.isEmpty())
    }

    @Test fun analyzedAliasesUseOneBookIdentityAndPreserveManualCorrectionsAndOtherBooks() = runBlocking {
        val candidate = SpeechTextAnalysisCandidate("小甲", "小甲", 90,
            SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.Model), true, true, aliases = listOf("阿甲"))
        val fixture = Fixture(mergeSpeechAnalysisRoles(configured().copy(autoAssignRole = false), "文本", listOf(candidate)))
        val first = fixture.prepare("小甲说：“你好。”阿甲说：“还是我。”").segments.filter { it.dialogue }
        assertEquals(listOf("小甲", "小甲"), first.map { it.speaker })
        assertTrue(first.all { it.selectedVoice!!.reference == male })
        assertEquals(1, fixture.saved.size)
        fixture.saved = fixture.saved + SpeechVoicePair("other-book", "小甲", SPEECH_POOL_MALE, male)
        val other = fixture.saved.last()
        fixture.saved = fixture.saved.map { if (it.bookId == "book") it.copy(value = female, manual = true) else it }
        val corrected = fixture.prepare("阿甲说：“按人工指定。”小甲说：“我也一样。”", "next").segments.filter { it.dialogue }
        assertTrue(corrected.all { it.selectedVoice!!.reference == female && it.roleMatch!!.origin == SpeechPersonOrigin.Manual })
        assertEquals(other, fixture.saved.last())
        assertEquals(male, fixture.prepare("阿甲说：“另一本书。”", bookId = "other-book")
            .segments.single { it.dialogue }.selectedVoice!!.reference)
        // An existing correction under an alias remains usable without rewriting persisted pairs.
        fixture.saved = listOf(SpeechVoicePair("book", "阿甲", SPEECH_POOL_MALE, female, manual = true), other)
        assertEquals(female, fixture.prepare("小甲说：“别名已有人工修正。”").segments.single { it.dialogue }.selectedVoice!!.reference)
        assertEquals(listOf("阿甲", "小甲"), fixture.saved.map { it.person })
    }

    private inner class Fixture(var settings: SpeechSettings = configured(),
        private val hint: SpeechNameHint? = null) {
        var saved = emptyList<SpeechVoicePair>()
        var pool = SpeechVoicePool(Random(7))
        val recognized = mutableListOf<String>()
        val newWordCalls = mutableListOf<Triple<String, String, Boolean>>()
        val classified = mutableListOf<String>()

        suspend fun prepare(text: String, chapterId: String = "chapter", bookId: String = "book"): SpeechPlan {
            val chapter = SpeechChapter(bookId, chapterId, "本书", "章节", text)
            val characters = SpeechCharacterAssignment(settings, chapter, saved.filter { it.bookId == bookId }, catalog,
                recognize = { input -> recognized += input; listOf("小甲").filter { it in input } },
                newWords = { input, key, reverse ->
                    newWordCalls += Triple(input, key, reverse)
                    listOf("小乙").filter { it in input }
                },
                classify = { name ->
                    classified += name
                    SpeechNameClassification(hint ?: if (name == "小甲") SpeechNameHint.Male else SpeechNameHint.Female,
                        SpeechNameHintBasis.Model)
                },
                existing = { name, groups -> pool.existing(bookId, name, groups, saved, catalog, settings.maxCacheVoiceNum) },
                assign = { name, group ->
                    pool.assign(bookId, name, group, saved, settings.voiceItems, catalog, settings.maxCacheVoiceNum)?.also { pair ->
                        saved = saved.filterNot { it.id == pair.id } + pair
                    }
                }, checkCancelled = {})
            return prepareSpeechPlan(chapter, settings, voices = catalog, characters = characters) {}
        }
    }
}
