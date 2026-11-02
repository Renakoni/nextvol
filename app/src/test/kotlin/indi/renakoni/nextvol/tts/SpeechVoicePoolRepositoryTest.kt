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
    @Test fun editingIntoAnExistingPersonAndGroupReplacesConflictsAndSurvivesReopening() = runBlocking {
        val first = SpeechVoiceRef(httpSource = "first")
        val second = SpeechVoiceRef(httpSource = "second")
        val target = SpeechVoicePair("book", "新人物", "青年", first)
        val original = SpeechVoicePair("book", "旧人物", "旧分组", first)
        val otherAutomatic = SpeechVoicePair("book", "新人物", "其他分组", first)
        val otherManual = SpeechVoicePair("book", "新人物", "人工分组", first, manual = true)
        val otherBook = SpeechVoicePair("another-book", "新人物", "青年", first, manual = true)
        val stored = MutableStateFlow<String?>(Json.encodeToString(listOf(target, otherBook, original, otherAutomatic, otherManual)))
        val dao = mockk<UserDataDao>()
        coEvery { dao.get("tts.voicePairs") } answers { stored.value }
        every { dao.getFlow("tts.voicePairs") } returns stored
        coEvery { dao.insert("tts.voicePairs", any(), "String", any()) } answers { stored.value = arg(3) }
        val data = UserDataRepository(dao)
        val repository = SpeechVoicePoolRepository(data)
        val changed = original.copy(person = target.person, group = target.group, value = second)
        repository.setManual(changed)
        assertEquals(1L, repository.revisions.value)
        val reopened = SpeechVoicePoolRepository(data)
        assertEquals(listOf(changed.copy(manual = true), otherBook, otherAutomatic), reopened.pairs())
        val catalog = listOf(first, second).associate { it.key to SpeechResolvedVoice(it, SpeechSettings(), 100) }
        assertEquals(second, reopened.existing("book", "新人物", listOf("其他分组"), catalog, 1)!!.value)
    }
}
