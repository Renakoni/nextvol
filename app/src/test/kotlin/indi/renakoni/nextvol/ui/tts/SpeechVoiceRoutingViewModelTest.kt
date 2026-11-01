package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.tts.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpeechVoiceRoutingViewModelTest {
    @Test fun candidatesAreExclusiveAndOnlyAnExplicitAssignmentChangesTheCurrentBook() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = SpeechVoiceRef(httpSource = "first", label = "First")
        val second = SpeechVoiceRef(httpSource = "second", label = "Second")
        val original = SpeechSettings(httpSource = "default", rate = 1.2f,
            speakerBookId = "other-book", speakerVoices = mapOf("阿明" to second))
        val stored = MutableStateFlow(original)
        val repository = mockk<SpeechSettingsRepository>()
        every { repository.changes } returns stored
        coEvery { repository.update(any()) } coAnswers { stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value) }
        val catalog = mockk<SpeechVoiceCatalog>()
        coEvery { catalog.choices() } returns listOf(first, second)
        val context = SpeechContext(narrationIndex = 2, dialogueIndex = 1)
        val playback = MutableStateFlow(ReadAloudState(SpeechRequest("book", "chapter"), planContext = context))
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns playback
        val chapter = SpeechChapter("book", "chapter", "Book", "Chapter", "“<<阿明>>你好。”")
        val plans = mockk<SpeechPlanPreparer>()
        coEvery { plans.prepare(any(), any(), any(), any()) } coAnswers {
            prepareSpeechPlan(firstArg(), secondArg(), context = thirdArg()) {}
        }
        val model = SpeechVoiceRoutingViewModel(repository, catalog, SpeechChapterSource { _, _ -> chapter }, plans, controller)
        try {
            runCurrent()
            model.loadPlan()
            val preview = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.plan != null } } }
            assertEquals("阿明", preview.plan!!.segments.single().speakerHint)
            assertEquals(context, preview.plan.context)
            assertEquals(original, stored.value)
            coVerify(exactly = 0) { repository.update(any()) }

            model.toggle(false, first)
            model.toggle(false, second)
            runCurrent()
            assertEquals(listOf(first, second), stored.value.narrationVoices)
            assertTrue(stored.value.voiceRoutingConfigured)
            model.toggle(true, first)
            runCurrent()
            assertEquals(listOf(second), stored.value.narrationVoices)
            assertTrue(stored.value.dialogueVoices.isEmpty())
            model.toggle(true, first)
            runCurrent()
            assertEquals(listOf(first), stored.value.dialogueVoices)

            var saved = false
            model.assign("阿明", first) { saved = true }
            runCurrent()
            assertTrue(saved)
            assertEquals("book", stored.value.speakerBookId)
            assertEquals(mapOf("阿明" to first), stored.value.speakerVoices)
            assertEquals(original.httpSource, stored.value.httpSource)
            assertEquals(original.rate, stored.value.rate)
            val assigned = withContext(Dispatchers.Default) { withTimeout(5000) {
                model.state.first { it.plan?.synthesis?.speakerBookId == "book" }
            } }
            assertEquals(first.key, assigned.plan!!.segments.single().selectedVoice!!.reference.key)
            assertFalse(assigned.plan.segments.single().text.contains("<<"))
            verify(exactly = 0) { controller.preview(any(), any()) }
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
