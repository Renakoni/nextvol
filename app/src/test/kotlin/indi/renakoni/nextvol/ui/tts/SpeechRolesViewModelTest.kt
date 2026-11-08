package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechRolesViewModelTest {
    @Test fun editorKeepsEngineVoiceSelectionAndSavesOnlyTheConfirmedDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = SpeechVoiceRef(httpSource = "first", label = "First")
        val second = SpeechVoiceRef(httpSource = "second", label = "Second")
        val original = SpeechRoleRule(id = "original", name = "Original", group = "Friends", speaker = first)
        val stored = MutableStateFlow(SpeechSettings(rate = 1.2f, roleRules = listOf(original)))
        val repository = mockk<SpeechSettingsEditor>()
        every { repository.changes } returns stored
        coEvery { repository.get() } answers { stored.value }
        coEvery { repository.update(any()) } coAnswers {
            stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value)
        }
        val catalog = mockk<SpeechVoiceCatalog>()
        coEvery { catalog.roleEngines(any()) } returns listOf(
            SpeechRoleEngine("first", "Engine one", listOf(first)),
            SpeechRoleEngine("second", "Engine two", listOf(second)))
        val model = SpeechRolesViewModel(repository, catalog)
        try {
            runCurrent()
            model.editRule(null)
            assertEquals(first, model.state.value.draft!!.speaker)
            assertEquals("Friends", model.state.value.draft!!.group)
            model.selectEngine("second")
            assertEquals(second, model.state.value.draft!!.speaker)
            model.edit { it.copy(name = "Friends", regex = "[", isAbstract = true, matchAfter = false) }
            model.save("Default") { fail("Invalid pattern must stay in the editor") }
            assertEquals(R.string.tts_replace_error_pattern, model.state.value.saveError)
            coVerify(exactly = 0) { repository.update(any()) }
            model.edit { it.copy(regex = "", group = "") }
            var saved = false
            model.save("Default") { saved = true }
            runCurrent()
            assertTrue(saved)
            val rule = stored.value.roleRules.last()
            assertEquals("Friends", rule.regex)
            assertEquals("Default", rule.group)
            assertEquals(second, rule.speaker)
            assertTrue(rule.isAbstract)
            assertFalse(rule.matchAfter)
            assertEquals(1.2f, stored.value.rate)
            model.editRule(rule.id)
            assertEquals(rule, model.state.value.draft)
            assertEquals("second", model.state.value.engineKey)

            val imported = SpeechVoiceRef(externalId = "http_test_second", label = "Imported")
            val resolved = second.copy(externalId = imported.externalId)
            stored.value = stored.value.copy(roleRules = listOf(rule.copy(speaker = imported)))
            coEvery { catalog.packageReferences() } returns mapOf("http_test_second" to second)
            runCurrent()
            model.editRule(rule.id)
            runCurrent()
            assertEquals(resolved, model.state.value.draft!!.speaker)
            assertEquals("second", model.state.value.engineKey)
            model.selectEngine("second")
            assertEquals(resolved, model.state.value.draft!!.speaker)

            // A removed voice remains identifiable; opening its rule must not silently choose another.
            stored.value = stored.value.copy(roleRules = listOf(rule.copy(speaker = SpeechVoiceRef(httpSource = "gone"))))
            runCurrent()
            model.editRule(rule.id)
            assertEquals("gone", model.state.value.draft!!.speaker!!.httpSource)
            assertNull(model.state.value.engineKey)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun filteredGroupActionsOnlyChangeTheirVisibleMembers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val visible = SpeechRoleRule(id = "visible", name = "Visible", group = "Group")
        val hidden = visible.copy(id = "hidden", name = "Hidden")
        val stored = MutableStateFlow(SpeechSettings(roleRules = listOf(visible, hidden)))
        val repository = mockk<SpeechSettingsEditor>()
        every { repository.changes } returns stored
        coEvery { repository.get() } answers { stored.value }
        coEvery { repository.update(any()) } coAnswers {
            stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value)
        }
        val catalog = mockk<SpeechVoiceCatalog>()
        coEvery { catalog.roleEngines(any()) } returns emptyList()
        val model = SpeechRolesViewModel(repository, catalog)
        try {
            runCurrent()
            val ids = filterSpeechRoles(stored.value.roleRules, "Visible").map { it.id }.toSet()
            model.toggle(ids, false)
            runCurrent()
            model.renameGroup(ids, "Renamed")
            runCurrent()
            assertEquals(visible.copy(activate = false, group = "Renamed"), stored.value.roleRules.first())
            assertEquals(hidden, stored.value.roleRules.last())
            model.delete(ids)
            runCurrent()
            assertEquals(listOf(hidden), stored.value.roleRules)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
