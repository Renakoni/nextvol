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
        assertFalse(repository.get().allowMultipleVoices)
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            repository.update { it.copy(text = SpeechTextSettings()) }
            val chinese = repository.get().text
            assertTrue(Regex(chinese.maleNameRegex).matches("张强"))
            assertTrue(Regex(chinese.femaleNameRegex).matches("小婷"))
            Locale.setDefault(Locale.ENGLISH)
            assertEquals(chinese, SpeechSettingsRepository(data).get().text)
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
            rate = 1.5f, pitch = 0.8f, playbackSpeed = 2f, allowMultipleVoices = true,
            roleRules = roles, roleContextPattern = "__ROLE__") }
        val local = OfflineVoiceRef("a".repeat(64), "b".repeat(64))
        repository.update { it.copy(offlineVoice = local) }
        val expected = SpeechSettings(replacements = rules, offlineVoice = local, text = text,
            rate = 1.5f, pitch = 0.8f, playbackSpeed = 2f, allowMultipleVoices = true,
            roleRules = roles, roleContextPattern = "__ROLE__")
        assertEquals(expected, SpeechSettingsRepository(data).get())
        repository.update { it.copy(replacements = it.replacements.reversed()) }
        assertEquals(rules.reversed(), repository.changes.first().replacements)
    }
}
