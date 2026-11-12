package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.HttpSpeechRepository
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.SpeechVoiceOperations
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class VoiceEditorViewModelTest {
    private val http = mockk<HttpSpeechRepository>()
    private val offline = mockk<OfflineSpeechRepository>()
    private val operations = mockk<SpeechVoiceOperations>()
    private lateinit var httpEditor: HttpVoiceEditorViewModel
    private lateinit var offlineEditor: OfflineVoiceEditorViewModel

    @Before fun create() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { http.avatar(any()) } returns null
        every { offline.avatar(any<InstalledOfflineVoice>()) } returns null
        httpEditor = HttpVoiceEditorViewModel(http, SavedStateHandle(), mockk(), mockk(), operations)
        offlineEditor = OfflineVoiceEditorViewModel(offline, SavedStateHandle(), mockk(), mockk(), operations)
    }

    @After fun destroy() {
        httpEditor.viewModelScope.cancel()
        offlineEditor.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun httpDisableIgnoresDraftEditsAndClosesOnlyAfterTheOperationFinishes() = runTest {
        val source = SavedHttpSpeechSource(previewHttpSpeech(
            """{"id":"voice","name":"Voice","url":"https://example.org"}""").sources.single(), emptyList())
        httpEditor.load("voice", cachedSource = source)
        val draft = httpEditor.state.first { !it.busy && it.draft != null }.draft!!
        httpEditor.edit(draft.copy(name = "Unsaved", code = "changed", speed = "invalid"))
        val persisted = CompletableDeferred<Unit>()
        coEvery { operations.enableHttp(listOf(source), false) } coAnswers { persisted.await() }
        var closed = false

        val action = httpEditor.toggleEnabled { closed = true }
        runCurrent()
        assertTrue(httpEditor.state.value.busy)
        assertFalse(closed)
        persisted.complete(Unit)
        action.join()

        assertTrue(closed)
        coVerify(exactly = 1) { operations.enableHttp(listOf(source), false) }
        coVerify(exactly = 0) { http.saveVoice(any()) }
    }

    @Test fun offlineEnableKeepsTheEditorOpenOnFailureAndRetriesWithoutSavingTheDraft() = runTest {
        val voice = InstalledOfflineVoice(OfflineVoiceRef("voice", "revision"), "Voice", emptyMap(), enabled = false,
            configuration = OfflineVoiceConfig("sherpa", "voice"))
        offlineEditor.load("voice", cachedVoice = voice)
        val draft = offlineEditor.state.first { !it.busy && it.draft != null }.draft!!
        offlineEditor.edit(draft.copy(name = "Unsaved", code = "changed", speed = "invalid"))
        coEvery { operations.enableOffline(listOf(voice), true) } throws IllegalStateException("write failed")
        var closed = false

        offlineEditor.toggleEnabled { closed = true }.join()
        assertFalse(closed)
        assertFalse(offlineEditor.state.value.busy)
        assertEquals(R.string.tts_voice_save_failed, offlineEditor.state.value.error)

        coEvery { operations.enableOffline(listOf(voice), true) } returns Unit
        offlineEditor.toggleEnabled { closed = true }.join()
        assertTrue(closed)
        assertNull(offlineEditor.state.value.error)
        coVerify(exactly = 0) { offline.save(any(), any()) }
    }
}
