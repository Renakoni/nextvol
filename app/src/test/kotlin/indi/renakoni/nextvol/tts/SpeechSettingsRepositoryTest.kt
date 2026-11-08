package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpeechSettingsRepositoryTest {
    private fun storage(): UserDataRepository {
        val stored = MutableStateFlow<String?>(null)
        val dao = mockk<UserDataDao>()
        coEvery { dao.get("tts.schemes") } answers { stored.value }
        every { dao.getFlow("tts.schemes") } returns stored
        coEvery { dao.insert("tts.schemes", any(), "String", any()) } answers { stored.value = arg(3) }
        return UserDataRepository(dao)
    }

    @Test fun completeRuleDataAndOrderSurviveVoiceChangesAndReopeningWithoutExecutingRegex() = runBlocking {
        val data = storage()
        val repository = SpeechSettingsRepository(data)
        assertFalse(repository.get().allowMultipleVoices)
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            repository.update { it.copy(text = SpeechTextSettings()) }
            val empty = repository.create("Empty")
            val chinese = repository.get().text
            assertTrue(Regex(chinese.maleNameRegex).matches("张强"))
            assertTrue(Regex(chinese.femaleNameRegex).matches("小婷"))
            Locale.setDefault(Locale.ENGLISH)
            assertEquals(chinese, SpeechSettingsRepository(data).get().text)
            assertEquals(chinese, SpeechSettingsRepository(data).get(empty).text)
            repository.update { it.copy(text = it.text.copy(maleNameRegex = "", femaleNameRegex = "")) }
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("", SpeechSettingsRepository(data).get().text.maleNameRegex)
            assertEquals("", SpeechSettingsRepository(data).get().text.femaleNameRegex)
        } finally { Locale.setDefault(previousLocale) }
        val rules = listOf(
            SpeechReplacementRule("first", "引号", "人物", "「", "“<<张三>>"),
            SpeechReplacementRule("second", "纠音", "草稿", "(", "$1", regex = true, activate = false),
        )
        val roles = listOf(SpeechRoleRule(id = "role", name = "Alice", regex = "[", group = "Characters",
            activate = false, mode = 0, isAbstract = true, matchBefore = false,
            speaker = SpeechVoiceRef(httpSource = "alice", label = "Alice voice")))
        val text = SpeechTextSettings(dialogue = true, recordVoice = true, recordText = true,
            continueDialogue = true, replaceEmoji = true, separateEnglish = false, filterUnspoken = false, maxSentenceLength = 72)
        repository.update { it.copy(replacements = rules, httpSource = "online", text = text,
            rate = 1.5f, pitch = 0.8f, volume = 0.74f, allowMultipleVoices = true,
            roleRules = roles, roleContextPattern = "__ROLE__") }
        val local = OfflineVoiceRef("a".repeat(64), "b".repeat(64))
        repository.update { it.copy(offlineVoice = local) }
        val expected = SpeechSettings(replacements = rules, offlineVoice = local, text = text,
            rate = 1.5f, pitch = 0.8f, volume = 0.74f, allowMultipleVoices = true,
            roleRules = roles, roleContextPattern = "__ROLE__")
        assertEquals(expected, SpeechSettingsRepository(data).get())
        repository.update { it.copy(replacements = it.replacements.reversed()) }
        assertEquals(rules.reversed(), repository.changes.first().replacements)
    }

    @Test fun bookBindingsCopyContentButShareRuntimeAndRebindOnDeletion() = runBlocking {
        val data = storage()
        val repository = SpeechSettingsRepository(data)
        val voice = SpeechVoiceRef(httpSource = "shared")
        val a = repository.create("A", bookId = "book-a")
        repository.update(a) { it.copy(rate = 1.5f, engineCacheCapacity = 20, maxCacheVoiceNum = 7,
            diagnosticLogging = true, text = it.text.copy(recordText = true, recordVoice = true, filterLanguage = true),
            httpSource = "shared", httpRevision = "http-v1", voiceRevision = "voices-v1",
            speakerBookId = "book-a", speakerVoices = mapOf("Alice" to voice), bookId = "book-a",
            voiceItems = listOf(SpeechVoiceItem("People", voice)),
            backgroundMusic = SpeechSchemeMusic(true, setOf("track"), 0.2f)) }
        val b = repository.create("B", a, "book-b")
        repository.update(a) { it.copy(rate = 0.7f) }
        val copied = SpeechSettingsRepository(data).forBook("book-b")
        assertEquals(1.5f, copied.rate)
        assertEquals("book-b", copied.bookId)
        assertEquals("B", copied.schemeName)
        assertEquals("shared", copied.httpSource)
        assertEquals("", copied.httpRevision)
        assertTrue(copied.speakerVoices.isEmpty())
        assertEquals(SpeechSchemeMusic(true, setOf("track"), 0.2f), copied.backgroundMusic)
        val defaults = repository.get()
        assertEquals(20, defaults.engineCacheCapacity)
        assertEquals(1f, defaults.rate)
        repository.updateAll { it.replaceVoiceReference(voice, voice.copy(httpSource = "renamed"))
            .copy(engineCacheCapacity = 3, text = it.text.copy(recordText = false)) }
        assertEquals(SpeechRuntimeSettings(20, 7, true, true, true, true), repository.snapshot().runtime)
        assertTrue(repository.get(b).text.recordText)
        assertEquals(listOf("renamed", "renamed"), listOf(a, b).map { repository.get(it).voiceItems.single().value.httpSource })
        repository.setDefault(b)
        repository.bind("book-a", null)
        assertEquals(b, repository.forBook("book-a").schemeId)
        repository.delete(b, a)
        assertEquals(a, repository.forBook("book-a").schemeId)
        assertEquals(a, repository.forBook("book-b").schemeId)
    }
}
