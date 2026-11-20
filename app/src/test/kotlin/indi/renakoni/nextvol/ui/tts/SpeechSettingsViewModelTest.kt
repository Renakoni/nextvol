package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.tts.*
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
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
class SpeechSettingsViewModelTest {
    private val settings = MutableStateFlow(SpeechSettings(rate = 1.5f, pitch = 0.8f))
    private val repository = mockk<SpeechSettingsEditor>()
    private val schemesRepository = mockk<SpeechSettingsRepository>()
    private val engines = mockk<SystemSpeechEngines>()
    private val http = mockk<HttpSpeechRepository>()
    private lateinit var model: SpeechSettingsViewModel
    private val voices = listOf(SpeechVoice("voice", "zh-CN", false, true))

    @Test fun editorPinsItsTargetAcrossBookRebindingAndRestoresItFromSavedState() = runTest {
        val snapshots = MutableStateFlow(SpeechSchemes(items = listOf(SpeechScheme("a", "A"), SpeechScheme("b", "B")),
            defaultId = "a", bindings = mapOf("book" to "a")))
        val schemesRepository = mockk<SpeechSettingsRepository>()
        every { schemesRepository.schemes } returns snapshots
        coEvery { schemesRepository.snapshot() } answers { snapshots.value }
        coEvery { schemesRepository.update(any(), any()) } returns Unit
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("bookId" to "book"))
        val editor = SpeechSettingsEditor(schemesRepository, saved)
        assertEquals("a", editor.get().schemeId)
        snapshots.value = snapshots.value.copy(defaultId = "b", bindings = mapOf("book" to "b"))
        assertEquals("a", editor.get().schemeId)
        assertEquals("a", SpeechSettingsEditor(schemesRepository, saved).get().schemeId)
        editor.update { it.copy(rate = 1.5f) }
        coVerify(exactly = 1) { schemesRepository.update("a", any()) }
        assertEquals("b", SpeechSettingsEditor(schemesRepository, androidx.lifecycle.SavedStateHandle()).get().schemeId)
    }

    @Before fun create() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.changes } returns settings
        coEvery { schemesRepository.updateAll(any()) } coAnswers {
            settings.value = firstArg<(SpeechSettings) -> SpeechSettings>()(settings.value)
        }
        every { http.changes } returns MutableStateFlow(0L)
        coEvery { engines.installed() } returns emptyList()
        coEvery { engines.inspect(any()) } throws SpeechException(SpeechError.NoEngine)
        model = SpeechSettingsViewModel(repository, engines, mockk(), http, mockk())
    }

    @After fun destroy() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun leavingTheVoiceLibraryDuringDeletionStillClearsTheSelectedReference() = runTest {
        val reference = OfflineVoiceRef("voice", "revision")
        val voice = InstalledOfflineVoice(reference, "Voice", emptyMap())
        val narrator = SpeechVoiceRef(httpSource = "narrator")
        settings.value = SpeechSettings(offlineVoice = reference, voiceRoutingConfigured = true,
            narrationVoices = listOf(narrator), dialogueVoices = listOf(SpeechVoiceRef(offlineVoice = reference)))
        val offline = mockk<OfflineSpeechRepository>()
        every { offline.changes } returns MutableStateFlow(0L)
        coEvery { offline.voices() } returns emptyList()
        val deleting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { offline.delete(setOf(reference)) } coAnswers { deleting.complete(Unit); release.await(); 0L }
        coEvery { repository.update(any()) } coAnswers {
            settings.value = firstArg<(SpeechSettings) -> SpeechSettings>()(settings.value)
        }
        val availability = mockk<indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker>()
        every { availability.changes } returns MutableStateFlow(0L)
        val engines = mockk<indi.renakoni.nextvol.tts.SpeechEngineRepository>()
        every { engines.changes } returns MutableStateFlow(0L)
        coEvery { engines.all() } returns emptyList()
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(ReadAloudState())
        val library = OfflineSpeechSourcesViewModel(offline, SpeechVoiceOperations(http, offline, schemesRepository, controller),
            repository, controller, availability, engines)
        try {
            runCurrent()
            val deletion = library.delete(voice)
            runCurrent()
            assertTrue(deleting.isCompleted)
            library.viewModelScope.cancel()
            release.complete(Unit)
            deletion.join()
            assertNull(settings.value.offlineVoice)
            assertEquals(listOf(narrator), settings.value.narrationVoices)
            assertTrue(settings.value.dialogueVoices.isEmpty())
        } finally { release.complete(Unit); library.viewModelScope.cancel() }
    }

    @Test fun httpRefreshRecoversFromMissingCredentialsAndCannotOverwriteASystemSelection() = runTest {
        runCurrent()
        val definition = hnovel.speech.previewHttpSpeech(
            """{"id":"http","name":"HTTP voice","url":"https://example.org"}""",
        ).sources.single()
        val locked = SavedHttpSpeechSource(definition, listOf("https://example.org:443"), credentialKeys = listOf("key"))
        var sources = CompletableDeferred<List<SavedHttpSpeechSource>>()
        coEvery { http.sources() } coAnswers { withContext(NonCancellable) { sources.await() } }
        settings.value = settings.value.copy(httpSource = definition.id)
        runCurrent()
        assertNull(model.state.value.error)
        sources.complete(listOf(locked))
        runCurrent()
        assertEquals(SpeechError.HttpLogin, model.state.value.error)

        sources = CompletableDeferred()
        model.refresh()
        runCurrent()
        assertEquals(SpeechError.HttpLogin, model.state.value.error)
        val configured = locked.copy(credentials = mapOf("key" to "configured"))
        sources.complete(listOf(configured))
        runCurrent()
        assertNull(model.state.value.error)
        assertTrue(model.state.value.httpSources.single().isConfigured)

        sources = CompletableDeferred()
        model.refresh()
        runCurrent()
        settings.value = settings.value.copy(httpSource = null)
        runCurrent()
        val system = model.state.value
        assertEquals(SpeechError.NoEngine, system.error)
        assertFalse(system.loading)
        sources.complete(emptyList())
        runCurrent()
        assertEquals(system, model.state.value)
    }
}
