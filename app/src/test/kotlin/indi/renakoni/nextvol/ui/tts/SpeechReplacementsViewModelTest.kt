package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.tts.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechReplacementsViewModelTest {
    @Test fun previewUsesUnsavedDraftAndManagementChangesOnlyRuleSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = SpeechReplacementRule(id = "one", source = "甲", target = "乙")
        val second = SpeechReplacementRule(id = "two", source = "甲", target = "丙")
        val selected = SpeechSettings(httpSource = "selected", rate = 1.2f, replacements = listOf(first, second))
        val stored = MutableStateFlow(selected)
        val repository = mockk<SpeechSettingsRepository>()
        every { repository.changes } returns stored
        coEvery { repository.update(any()) } coAnswers { stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value) }
        val plans = mockk<SpeechPlanPreparer>()
        coEvery { plans.prepare(any(), any()) } coAnswers { prepareSpeechPlan(firstArg(), secondArg()) {} }
        val model = SpeechReplacementsViewModel(repository, plans)
        try {
            runCurrent()
            model.preview("甲", first.copy(target = "草稿"))
            val preview = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.preview != null } } }.preview!!
            assertEquals("草稿", preview.segments.single().text)
            assertEquals(selected, stored.value)
            coVerify(exactly = 0) { repository.update(any()) }
            model.move("two", -1)
            runCurrent()
            assertEquals(listOf(second, first), stored.value.replacements)
            assertNull(model.state.value.preview)
            model.toggle("two", false)
            runCurrent()
            var saved = false
            model.save(first.copy(group = "纠音", name = "读音")) { saved = true }
            runCurrent()
            assertTrue(saved)
            assertEquals(listOf(second.copy(activate = false), first.copy(group = "纠音", name = "读音")), stored.value.replacements)
            model.delete("one")
            runCurrent()
            assertEquals(selected.copy(replacements = listOf(second.copy(activate = false))), stored.value)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
