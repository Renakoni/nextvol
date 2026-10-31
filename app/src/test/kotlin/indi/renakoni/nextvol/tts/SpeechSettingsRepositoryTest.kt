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

class SpeechSettingsRepositoryTest {
    @Test fun legacySettingsAndOfflineSelectionSurviveSavingAndReopening() = runBlocking {
        for (legacy in listOf("""{"rate":null,"pitch":null}""", """{"rate":1.5,"pitch":0.8,"httpSource":"old-http-id"}""")) {
            val stored = MutableStateFlow<String?>(legacy)
            val dao = mockk<UserDataDao>()
            coEvery { dao.get("tts.settings") } answers { stored.value }
            every { dao.getFlow("tts.settings") } returns stored
            coEvery { dao.insert("tts.settings", any(), "String", any()) } answers { stored.value = arg(3) }
            val data = UserDataRepository(dao)
            val repository = SpeechSettingsRepository(data)
            val old = repository.get()
            assertNull(old.offlineVoice)
            assertEquals(if (legacy.contains("old-http-id")) "old-http-id" else null, old.httpSource)
            assertEquals(1f, old.playbackSpeed)
            assertEquals(if (legacy.contains("1.5")) 1.5f else null, old.rate)
            assertEquals(if (legacy.contains("0.8")) 0.8f else null, old.pitch)
            repository.update { it.copy(playbackSpeed = 2f) }
            assertEquals(old.copy(playbackSpeed = 2f), repository.changes.first())
            assertEquals(old.copy(playbackSpeed = 2f), SpeechSettingsRepository(data).get())
            val local = OfflineVoiceRef("a".repeat(64), "b".repeat(64))
            repository.update { it.copy(offlineVoice = local) }
            val reopened = SpeechSettingsRepository(data).get()
            assertEquals(local, reopened.offlineVoice)
            assertNull(reopened.httpSource)
            assertEquals(old.rate, reopened.rate)
        }
    }

    @Test fun invalidPlaybackSpeedIsNormalizedIndependentlyOfSynthesisDefaults() {
        val defaults = SpeechSettings()
        for ((input, expected) in listOf(Float.NaN to 1f, -1f to 0.5f, 3f to 2f)) {
            assertEquals(defaults.copy(playbackSpeed = expected), defaults.copy(playbackSpeed = input).validated())
        }
    }

    @Test fun completeRuleDataAndOrderSurviveVoiceChangesAndReopeningWithoutExecutingRegex() = runBlocking {
        val stored = MutableStateFlow<String?>(null)
        val dao = mockk<UserDataDao>()
        coEvery { dao.get("tts.settings") } answers { stored.value }
        every { dao.getFlow("tts.settings") } returns stored
        coEvery { dao.insert("tts.settings", any(), "String", any()) } answers { stored.value = arg(3) }
        val data = UserDataRepository(dao)
        val repository = SpeechSettingsRepository(data)
        val rules = listOf(
            SpeechReplacementRule("first", "引号", "人物", "「", "“<<张三>>"),
            SpeechReplacementRule("second", "纠音", "草稿", "(", "$1", regex = true, activate = false),
        )
        repository.update { it.copy(replacements = rules, httpSource = "online") }
        repository.update { it.copy(offlineVoice = OfflineVoiceRef("a".repeat(64), "b".repeat(64))) }
        assertEquals(rules, SpeechSettingsRepository(data).get().replacements)
        repository.update { it.copy(replacements = it.replacements.reversed()) }
        assertEquals(rules.reversed(), repository.changes.first().replacements)
    }
}
