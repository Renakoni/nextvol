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
        val first = SpeechReplacementRule(id = "one", group = "a", name = "中", source = "甲", target = "乙")
        val second = SpeechReplacementRule(id = "two", group = "b", name = "A", source = "甲", target = "丙")
        val hidden = first.copy(id = "hidden", name = "Hidden")
        val selected = SpeechSettings(httpSource = "selected", rate = 1.2f, replacements = listOf(first, second, hidden),
            text = SpeechTextSettings(separateEnglish = false))
        val stored = MutableStateFlow(selected)
        val repository = mockk<SpeechSettingsEditor>()
        every { repository.changes } returns stored
        coEvery { repository.get() } answers { stored.value }
        coEvery { repository.update(any()) } coAnswers { stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value) }
        val plans = mockk<SpeechPlanPreparer>()
        coEvery { plans.prepare(any(), any()) } coAnswers { prepareSpeechPlan(firstArg(), secondArg()) {} }
        coEvery { plans.previewRule(any(), any()) } coAnswers { previewSpeechReplacement(firstArg(), secondArg()) {} }
        val model = SpeechReplacementsViewModel(repository, plans)
        try {
            runCurrent()
            model.preview("甲", first.copy(target = "草稿", activate = false))
            val preview = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.rulePreview != null } } }
            assertEquals("草稿", preview.rulePreview)
            assertNull(preview.preview)
            assertEquals(selected, stored.value)
            coVerify(exactly = 0) { repository.update(any()) }
            model.preview("甲 hello")
            val combined = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { !it.previewing } } }
            assertNull(combined.previewError)
            assertEquals("乙 hello", combined.preview!!.segments.single().text)
            assertNull(combined.rulePreview)
            model.sort(descending = false)
            runCurrent()
            // MultiTTS's weighted UTF-16 comparison differs from lexicographic group/name sorting.
            assertEquals(listOf("hidden", "two", "one"), stored.value.replacements.map { it.id })
            model.toggleGroup(setOf(first.id), false)
            runCurrent()
            assertFalse(stored.value.replacements.first { it.id == first.id }.activate)
            assertTrue(stored.value.replacements.first { it.id == hidden.id }.activate)
            model.move("one", -2)
            runCurrent()
            // Moving filtered rows swaps endpoints without shifting hidden rows or changing voice settings.
            assertEquals(selected.copy(replacements = listOf(first.copy(activate = false), second, hidden)), stored.value)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
