package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechSchemesTest {
    private fun storage(): UserDataRepository {
        val values = mutableMapOf<String, MutableStateFlow<String?>>()
        fun value(key: String) = values.getOrPut(key) { MutableStateFlow(null) }
        val dao = mockk<UserDataDao>()
        coEvery { dao.get(any()) } answers { value(firstArg()).value }
        every { dao.getFlow(any()) } answers { value(firstArg()) }
        coEvery { dao.insert(any(), any(), "String", any()) } answers { value(firstArg()).value = arg(3) }
        return UserDataRepository(dao)
    }

    @Test fun namedSchemesKeepBookContentSeparateAndDeleteRebindsWithoutCopyingGlobalSettings() = runBlocking {
        val data = storage()
        val repository = SpeechSettingsRepository(data)
        val a = repository.create("A", bookId = "book-a")
        val b = repository.create("B", bookId = "book-b")
        val role = SpeechRoleRule(id = "alice", name = "Alice", group = "People")
        repository.update(a) { it.copy(roleRules = listOf(role), voiceItems = listOf(
            SpeechVoiceItem("Women", SpeechVoiceRef(httpSource = "shared-voice"))),
            playbackSpeed = 1.5f, backgroundMusic = SpeechSchemeMusic(true, setOf("shared-track"), 0.2f)) }
        val before = repository.changesForBook("book-b").first()
        repository.update(a) { it.copy(replacements = listOf(SpeechReplacementRule("rule", "Rule", "Group", "a", "b"))) }
        assertEquals(before, repository.changesForBook("book-b").first())
        assertTrue(repository.forBook("book-b").roleRules.isEmpty())

        repository.update(a) { it.copy(engineCacheCapacity = 20, diagnosticLogging = true,
            text = it.text.copy(recordText = true)) }
        val copied = repository.create("A copy", a, "book-c")
        repository.setDefault(copied)
        repository.bind("book-a", null)
        val reopened = SpeechSettingsRepository(data)
        assertEquals(copied, reopened.forBook("book-a").schemeId)
        assertEquals(b, reopened.forBook("book-b").schemeId)
        assertEquals(copied, reopened.forBook("book-c").schemeId)
        assertEquals(listOf(role), reopened.forBook("book-a").roleRules)
        assertEquals(setOf("shared-track"), reopened.get(copied).backgroundMusic.selected)
        assertEquals(20, reopened.get(b).engineCacheCapacity)
        assertTrue(reopened.get(b).text.recordText)
        assertEquals(10, reopened.snapshot().items.first { it.id == copied }.settings.engineCacheCapacity)

        reopened.delete(copied, b)
        assertEquals(b, reopened.forBook("book-a").schemeId)
        assertEquals(b, reopened.forBook("book-c").schemeId)
        assertEquals(listOf(role), reopened.get(a).roleRules)
        reopened.updateAll { it.replaceVoiceReference(SpeechVoiceRef(httpSource = "shared-voice"),
            SpeechVoiceRef(httpSource = "renamed", label = "Renamed")) }
        assertEquals("renamed", reopened.get(a).voiceItems.single().value.httpSource)
        assertEquals(before.voiceItems, reopened.get(b).voiceItems)
    }

    @Test fun voicePairsAndDrawCachesAreIsolatedByBothBookAndSchemeAndKeepManualChoices() = runBlocking {
        val data = storage()
        val pairs = SpeechVoicePoolRepository(data)
        val first = SpeechVoiceRef(httpSource = "first")
        val second = SpeechVoiceRef(httpSource = "second")
        val catalog = listOf(first, second).associate { it.key to SpeechResolvedVoice(it, SpeechSettings(), 256) }
        val a = pairs.assign("book-a", "Alice", "Women", listOf(SpeechVoiceItem("Women", first)), catalog, 10,
            schemeId = "a")!!
        pairs.setManual(a)
        val b = pairs.assign("book-a", "Alice", "Women", listOf(SpeechVoiceItem("Women", second)), catalog, 10,
            schemeId = "b")!!
        val otherBook = pairs.assign("book-b", "Alice", "Women", listOf(SpeechVoiceItem("Women", second)), catalog, 10,
            schemeId = "a")!!
        assertEquals(second, b.value)
        assertEquals(second, otherBook.value)
        assertEquals(first, pairs.existing("book-a", "Alice", listOf("Women"), pairs.pairs(), pairs.revisions.value,
            catalog, 10, "a")!!.value)
        assertEquals(0L, pairs.revision("book-a", "b"))
        pairs.clear("book-a", "b")
        val reopened = SpeechVoicePoolRepository(data)
        assertTrue(reopened.pairs("book-a", "a").single().manual)
        assertEquals(first, reopened.pairs("book-a", "a").single().value)
        assertEquals(otherBook, reopened.pairs("book-b", "a").single())
        assertTrue(reopened.pairs("book-a", "b").isEmpty())
    }
}
