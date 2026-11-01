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
        coEvery { plans.previewRule(any(), any()) } coAnswers { previewSpeechReplacement(firstArg(), secondArg()) {} }
        val model = SpeechReplacementsViewModel(repository, plans)
        try {
            runCurrent()
            model.save(first) { fail("Empty name was saved") }
            assertEquals(SpeechReplacementSaveError.Name, model.state.value.saveError)
            model.save(first.copy(name = "规则", source = "")) { fail("Empty source was saved") }
            assertEquals(SpeechReplacementSaveError.Source, model.state.value.saveError)
            model.save(first.copy(name = "规则", source = "[", regex = true)) { fail("Invalid pattern was saved") }
            assertEquals(SpeechReplacementSaveError.Pattern, model.state.value.saveError)
            model.clearSaveError()
            model.preview("甲", first.copy(target = "草稿", activate = false))
            val preview = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.rulePreview != null } } }
            assertEquals("草稿", preview.rulePreview)
            assertNull(preview.preview)
            assertEquals(selected, stored.value)
            coVerify(exactly = 0) { repository.update(any()) }
            model.preview("甲")
            val combined = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.preview != null } } }
            assertEquals("乙", combined.preview!!.segments.single().text)
            assertNull(combined.rulePreview)
            model.move("two", -1)
            runCurrent()
            assertEquals(listOf(second, first), stored.value.replacements)
            assertNull(model.state.value.rulePreview)
            model.toggle("two", false)
            runCurrent()
            var saved = false
            model.save(first.copy(group = "纠音", name = "读音")) { saved = true }
            runCurrent()
            assertTrue(saved)
            assertEquals(listOf(second.copy(activate = false), first.copy(group = "纠音", name = "读音")), stored.value.replacements)
            model.sort(descending = true)
            runCurrent()
            assertEquals(listOf("one", "two"), stored.value.replacements.map { it.id })
            model.toggleGroup(setOf("one"), false)
            runCurrent()
            assertTrue(stored.value.replacements.none { it.activate })
            model.sort(descending = false)
            runCurrent()
            assertEquals(listOf("two", "one"), stored.value.replacements.map { it.id })
            model.delete("one")
            runCurrent()
            assertEquals(selected.copy(replacements = listOf(second.copy(activate = false))), stored.value)
            val weightedFirst = first.copy(id = "weighted-first", group = "a", name = "中")
            val weightedSecond = second.copy(id = "weighted-second", group = "b", name = "A")
            model.save(weightedFirst) {}
            runCurrent()
            model.save(weightedSecond) {}
            runCurrent()
            model.sort(descending = false)
            runCurrent()
            // MultiTTS's weighted UTF-16 comparison differs from lexicographic group/name sorting.
            assertEquals(listOf("two", "weighted-second", "weighted-first"), stored.value.replacements.map { it.id })
            val hidden = weightedFirst.copy(id = "hidden", name = "Hidden")
            model.save(hidden) {}
            runCurrent()
            model.toggleGroup(setOf(weightedFirst.id), false)
            runCurrent()
            assertFalse(stored.value.replacements.first { it.id == weightedFirst.id }.activate)
            assertTrue(stored.value.replacements.first { it.id == hidden.id }.activate)
            model.move("hidden", -3)
            runCurrent()
            assertEquals(listOf("hidden", "weighted-second", "weighted-first", "two"), stored.value.replacements.map { it.id })
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
