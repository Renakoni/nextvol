package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechVoicePoolRepositoryTest {
    @Test fun languageDefaultsKeepManualPairsWithinTheirGroupsWithoutBecomingPeopleOrOverridingConcreteVoices() = runBlocking {
        val fallback = SpeechVoiceRef(httpSource = "fallback")
        val english = SpeechVoiceRef(httpSource = "english")
        val japanese = SpeechVoiceRef(httpSource = "japanese")
        val french = SpeechVoiceRef(httpSource = "french")
        val englishPair = SpeechVoicePair("book", SPEECH_LANGUAGE_PERSON, "English", fallback)
        val japanesePair = SpeechVoicePair("book", SPEECH_LANGUAGE_PERSON, "日本語", fallback)
        val automaticPair = SpeechVoicePair("book", SPEECH_LANGUAGE_PERSON, "Français", fallback)
        val stored = MutableStateFlow<String?>(Json.encodeToString(listOf(englishPair, japanesePair, automaticPair)))
        val dao = mockk<UserDataDao>()
        coEvery { dao.get("tts.voicePairs") } answers { stored.value }
        every { dao.getFlow("tts.voicePairs") } returns stored
        coEvery { dao.insert("tts.voicePairs", any(), "String", any()) } answers { stored.value = arg(3) }
        val data = UserDataRepository(dao)
        val repository = SpeechVoicePoolRepository(data)
        repository.setManual(englishPair.copy(value = english))
        repository.setManual(japanesePair.copy(value = japanese))
        val reopened = SpeechVoicePoolRepository(data)
        val saved = reopened.pairs("book")
        assertEquals(listOf(englishPair.copy(value = english, manual = true),
            japanesePair.copy(value = japanese, manual = true), automaticPair), saved)

        val settings = SpeechSettings(voiceRoutingConfigured = true, narrationVoices = listOf(fallback),
            text = SpeechTextSettings(detectLanguage = true, separateEnglish = false), roleRules = listOf(
                SpeechRoleRule(name = "English", regex = "lang:en", mode = 1, isAbstract = true, speaker = fallback),
                SpeechRoleRule(name = "日本語", regex = "lang:ja", mode = 1, isAbstract = true, speaker = fallback),
                SpeechRoleRule(name = "Français", regex = "lang:fr", mode = 1, speaker = french)))
        val catalog = listOf(fallback, english, japanese, french).associate { it.key to
            SpeechResolvedVoice(it, settings.forVoice(it), 256) }
        val chapter = SpeechChapter("book", "one", "", "", "Hello!こんにちは。Bonjour!")
        val assignments = mutableListOf<String>()
        val characters = SpeechCharacterAssignment(settings, chapter, saved, catalog,
            recognize = { error("Automatic discovery is off") }, newWords = { _, _, _ -> error("Automatic discovery is off") },
            classify = { error("Automatic discovery is off") },
            existing = { _, _ -> error("Language defaults are not people") },
            assign = { name, group ->
                assignments += group
                reopened.assign("book", name, group, emptyList(), catalog, 50)
            }, checkCancelled = {})
        assertNull(characters.resolve(SPEECH_LANGUAGE_PERSON, SpeechContextDirection.Explicit, "name",
            SpeechRoleRules(settings.roleRules, checkCancelled = {})))
        val plan = prepareSpeechPlan(chapter, settings, voices = catalog, characters = characters,
            detectLanguage = { text, _ -> when {
                text.startsWith("Hello") -> "en"
                text.startsWith("Bonjour") -> "fr"
                else -> "ja"
            } }) {}
        assertEquals(listOf(english.key, japanese.key, french.key), plan.segments.map { it.selectedVoice!!.reference.key })
        assertEquals(listOf("English", "日本語"), assignments)
    }

    @Test fun editingAndReassigningAnAliasReplaceItsPersonsConflictsAndSurviveReopening() = runBlocking {
        val first = SpeechVoiceRef(httpSource = "first")
        val second = SpeechVoiceRef(httpSource = "second")
        val target = SpeechVoicePair("book", "新人物", "青年", first, manual = true)
        val original = SpeechVoicePair("book", "旧人物", "旧分组", first, manual = true)
        val otherAutomatic = SpeechVoicePair("book", "新人物", "其他分组", first)
        val otherManual = SpeechVoicePair("book", "新人物", "人工分组", first, manual = true)
        val otherBook = SpeechVoicePair("another-book", "新人物", "青年", first, manual = true)
        val otherScheme = SpeechVoicePair("book", "新人物", "青年", first, manual = true, schemeId = "other")
        val people = listOf(SpeechRolePerson(target.person, listOf(original.person)))
        val initial = listOf(target, otherBook, original, otherAutomatic, otherManual, otherScheme)
        val stored = MutableStateFlow<String?>(Json.encodeToString(initial))
        val dao = mockk<UserDataDao>()
        coEvery { dao.get("tts.voicePairs") } answers { stored.value }
        every { dao.getFlow("tts.voicePairs") } returns stored
        coEvery { dao.insert("tts.voicePairs", any(), "String", any()) } answers { stored.value = arg(3) }
        val data = UserDataRepository(dao)
        val repository = SpeechVoicePoolRepository(data)
        val changed = original.copy(group = target.group, value = second)
        repository.setManual(changed, people)
        assertEquals(1L, repository.revisions.value)
        assertEquals(0L, repository.revision("book", "other"))
        val reopened = SpeechVoicePoolRepository(data)
        val saved = reopened.pairs()
        assertEquals(listOf(changed.copy(person = target.person), otherBook, otherAutomatic, otherScheme), saved)
        val catalog = listOf(first, second).associate { it.key to SpeechResolvedVoice(it, SpeechSettings(), 100) }
        assertEquals(second, reopened.existing("book", "新人物", listOf("其他分组"), saved, 0, catalog - second.key, 1)!!.value)
        assertEquals(first, reopened.existing("book", "新人物", listOf("其他分组"), saved, 0, catalog, 1, "other")!!.value)
        reopened.remove(setOf(changed.id))
        assertEquals(first, reopened.existing("book", "新人物", listOf("其他分组"), saved, 0, catalog, 1)!!.value)

        stored.value = Json.encodeToString(initial)
        assertTrue(reopened.reassign(setOf(original.id), listOf(SpeechVoiceItem(original.group, second)), catalog, people).isEmpty())
        val reassigned = SpeechVoicePoolRepository(data).pairs()
        assertEquals(listOf(original.copy(person = target.person, value = second, manual = false), otherBook, otherAutomatic, otherScheme), reassigned)
        assertEquals(second, reopened.existing("book", "新人物", listOf(original.group), reassigned,
            reopened.revisions.value, catalog, 1)!!.value)
        reopened.clear("book", "other")
        assertEquals(reassigned - otherScheme, reopened.pairs())
    }
}
