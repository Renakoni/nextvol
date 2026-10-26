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

class SpeechSettingsRepositoryTest {
    @Test fun legacySynthesisSettingsKeepTheirMeaningWhenPlaybackSpeedIsSavedAndReopened() = runBlocking {
        for (legacy in listOf("""{"rate":null,"pitch":null}""", """{"rate":1.5,"pitch":0.8}""")) {
            val stored = MutableStateFlow<String?>(legacy)
            val dao = mockk<UserDataDao>()
            coEvery { dao.get("tts.settings") } answers { stored.value }
            every { dao.getFlow("tts.settings") } returns stored
            coEvery { dao.insert("tts.settings", any(), "String", any()) } answers { stored.value = arg(3) }
            val data = UserDataRepository(dao)
            val repository = SpeechSettingsRepository(data)
            val old = repository.get()
            assertEquals(1f, old.playbackSpeed)
            assertEquals(if (legacy.contains("1.5")) 1.5f else null, old.rate)
            assertEquals(if (legacy.contains("0.8")) 0.8f else null, old.pitch)
            repository.update { it.copy(playbackSpeed = 2f) }
            assertEquals(old.copy(playbackSpeed = 2f), repository.changes.first())
            assertEquals(old.copy(playbackSpeed = 2f), SpeechSettingsRepository(data).get())
        }
    }

    @Test fun invalidPlaybackSpeedIsNormalizedIndependentlyOfSynthesisDefaults() {
        val defaults = SpeechSettings()
        for ((input, expected) in listOf(Float.NaN to 1f, -1f to 0.5f, 3f to 2f)) {
            assertEquals(defaults.copy(playbackSpeed = expected), defaults.copy(playbackSpeed = input).validated())
        }
    }
}
