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
import kotlinx.coroutines.flow.MutableSharedFlow
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
class SpeechRolesViewModelTest {
    @Test fun savingDoesNotExecuteRulesAndDraftPreviewDoesNotPublishChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val voice = SpeechVoiceRef(httpSource = "voice", label = "Voice")
        val otherBook = SpeechRoleRule(id = "other", name = "Other", bookId = "other-book")
        val original = SpeechSettings(rate = 1.2f, roleRules = listOf(otherBook))
        val stored = MutableStateFlow(original)
        val observed = MutableSharedFlow<SpeechSettings>(replay = 1)
        val repository = mockk<SpeechSettingsRepository>()
        every { repository.changes } returns observed
        coEvery { repository.update(any()) } coAnswers {
            stored.value = firstArg<(SpeechSettings) -> SpeechSettings>()(stored.value)
            observed.emit(stored.value)
        }
        val catalog = mockk<SpeechVoiceCatalog>()
        coEvery { catalog.choices() } returns listOf(voice)
        val plans = mockk<SpeechPlanPreparer>()
        coEvery { plans.prepare(any(), any(), any(), any()) } coAnswers {
            SpeechPlan(firstArg(), secondArg(), emptyList(), "", emptyList(), context = thirdArg())
        }
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(ReadAloudState(SpeechRequest("book", "chapter"), bookTitle = "Book"))
        val chapters = mockk<SpeechChapterSource>()
        val model = SpeechRolesViewModel(repository, catalog, plans, chapters, controller)
        try {
            runCurrent()
            assertFalse(model.state.value.catalogLoading)
            assertTrue(model.state.value.loading)
            observed.emit(original)
            runCurrent()
            assertFalse(model.state.value.loading)
            model.editRule(null, true)
            model.edit { it.copy(name = "朋友", regex = "[", group = "同伴", activate = false,
                isAbstract = true, matchBefore = false, matchAfter = true, speaker = voice, note = "备注") }
            val saved = model.state.value.draft!!
            model.save {}
            runCurrent()
            assertEquals(original.copy(roleRules = listOf(otherBook, saved)), stored.value)
            assertEquals("book", saved.bookId)
            coVerify(exactly = 0) { plans.prepare(any(), any(), any(), any()) }

            model.edit { it.copy(regex = "张三|小张") }
            model.preview("张三说：“你好。”", draft = true)
            val result = withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first { it.preview != null } } }
            assertEquals(saved.copy(regex = "张三|小张", activate = true), result.preview!!.synthesis.roleRules.last())
            assertEquals(listOf(otherBook, saved), stored.value.roleRules)
            coVerify(exactly = 1) { repository.update(any()) }
            verify(exactly = 0) { controller.preview(any(), any()) }

            model.editRule(saved.id, false)
            assertEquals(saved, model.state.value.draft)
            model.audition(saved, "试听")
            verify { controller.preview("试听", stored.value.forVoice(voice)) }

            val first = saved.copy(id = "first", group = "a")
            val last = saved.copy(id = "last", group = "b")
            val global = otherBook.copy(id = "global", bookId = null)
            stored.value = stored.value.copy(roleRules = listOf(first, otherBook, global, last))
            model.move(last.id, -3)
            runCurrent()
            // Moving across filtered groups preserves the relative order of every other rule.
            assertEquals(listOf(last, first, otherBook, global), stored.value.roleRules)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
