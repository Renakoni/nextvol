package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import hnovel.speech.OfflineEngineConfig
import hnovel.speech.OfflineVoiceConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val repository = mockk<SpeechSettingsRepository>()
    private val engines = mockk<SystemSpeechEngines>()
    private val http = mockk<HttpSpeechRepository>()
    private lateinit var model: SpeechSettingsViewModel
    private val engine = SpeechEngine("engine", "Engine")
    private val voices = listOf(SpeechVoice("voice", "zh-CN", false, true))

    @Before fun create() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { repository.changes } returns settings
        coEvery { engines.installed() } returns emptyList()
        coEvery { engines.inspect(any()) } throws SpeechException(SpeechError.NoEngine)
        model = SpeechSettingsViewModel(repository, engines, mockk(), http, mockk())
    }

    @After fun destroy() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun playbackSpeedIsSavedWithoutChangingSynthesisOrRefreshingTheVoiceCatalog() = runTest {
        coEvery { repository.update(any()) } coAnswers {
            settings.value = firstArg<(SpeechSettings) -> SpeechSettings>()(settings.value).validated()
        }
        runCurrent()
        val original = settings.value
        model.setPlaybackSpeed(2f)
        runCurrent()
        assertEquals(original.copy(playbackSpeed = 2f), model.state.value.settings)
        coVerify(exactly = 1) { engines.inspect(any()) }
    }

    @Test fun auditioningAnOnlineVoiceDoesNotSaveItAndRemovingItStopsTheSample() = runTest {
        val source = SavedHttpSpeechSource(hnovel.speech.previewHttpSpeech(
            """{"id":"candidate","name":"Candidate","url":"https://example.org"}""").sources.single(), listOf("https://example.org:443"))
        coEvery { http.sources() } returns listOf(source)
        every { http.deniedOrigins } returns MutableStateFlow(emptyMap())
        coEvery { http.delete(source.definition.id) } returns Unit
        val playback = MutableStateFlow(ReadAloudState())
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns playback
        val library = HttpSpeechSourcesViewModel(http, repository, controller, mockk())
        try {
            runCurrent()
            library.previewVoice("candidate", "Sample.")
            verify { controller.preview("Sample.", settings.value.copy(httpSource = "candidate")) }
            coVerify(exactly = 0) { repository.update(any()) }
            assertNull(library.state.value.selected)
            playback.value = ReadAloudState(SpeechRequest("", "", "Sample.", settings.value.copy(httpSource = "candidate")), SpeechPhase.Playing)
            library.delete(source)
            runCurrent()
            verify { controller.command(SpeechAction.Stop) }
            coVerify(exactly = 0) { repository.update(any()) }
        } finally { library.viewModelScope.cancel() }
    }

    @Test fun packagePreviewWaitsForConfirmationAndSavingDoesNotSelectOrPlayAVoice() = runTest {
        val definition = hnovel.speech.previewHttpSpeech(
            """{"name":"Package voice","url":"https://speech.example.invalid/audio"}""").sources.single()
        var saved = emptyList<SavedHttpSpeechSource>()
        coEvery { http.sources() } coAnswers { saved }
        coEvery { http.save(any()) } coAnswers { saved = firstArg() }
        every { http.deniedOrigins } returns MutableStateFlow(emptyMap())
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(ReadAloudState())
        val library = HttpSpeechSourcesViewModel(http, repository, controller, mockk())
        try {
            runCurrent()
            library.previewPackage(listOf(definition))
            assertEquals(definition.id, library.state.value.pending!!.single().definition.id)
            assertEquals(listOf("https://speech.example.invalid:443"), library.state.value.pending!!.single().origins)
            coVerify(exactly = 0) { http.save(any()) }
            library.dismissPreview()
            assertNull(library.state.value.pending)
            library.previewPackage(listOf(definition))
            library.confirmImport()
            runCurrent()
            coVerify(exactly = 1) { http.save(any()) }
            assertNull(library.state.value.pending)
            assertEquals(definition.id, library.state.value.sources.single().definition.id)
            coVerify(exactly = 0) { repository.update(any()) }
            verify(exactly = 0) { controller.preview(any(), any()) }
        } finally { library.viewModelScope.cancel() }
    }

    @Test fun editingOfflineParametersStopsAnAffectedPreviewButRenamingDoesNot() = runTest {
        val configuration = OfflineEngineConfig("msctts", "MSC")
        val reference = OfflineVoiceRef("voice", "revision")
        val offline = mockk<OfflineSpeechRepository>()
        coEvery { offline.voices() } returns listOf(InstalledOfflineVoice(reference, "Voice", emptyMap(),
            configuration = OfflineVoiceConfig("msctts", "voice")))
        val catalog = mockk<OfflineEngineRepository>()
        every { catalog.changes } returns MutableStateFlow(0L)
        coEvery { catalog.all() } returns listOf(configuration)
        coEvery { catalog.save(any()) } returns Unit
        coEvery { repository.get() } returns settings.value
        coEvery { http.sources() } returns emptyList()
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(ReadAloudState(
            SpeechRequest("", "", "Sample", SpeechSettings(offlineVoice = reference)), SpeechPhase.Playing))
        val editor = SpeechEnginesViewModel(catalog, http, offline, repository, controller)
        try {
            runCurrent()
            editor.save(configuration.copy(name = "Renamed")) {}
            runCurrent()
            verify(exactly = 0) { controller.command(any()) }
            editor.save(configuration.copy(parameters = "{\"region\":\"bj\"}")) {}
            runCurrent()
            verify(exactly = 1) { controller.command(SpeechAction.Stop) }
            coVerify { catalog.save(configuration.copy(parameters = "{\"region\":\"bj\"}")) }
        } finally { editor.viewModelScope.cancel() }
    }

    @Test fun leavingTheVoiceLibraryDuringDeletionStillClearsTheSelectedReference() = runTest {
        val reference = OfflineVoiceRef("voice", "revision")
        val voice = InstalledOfflineVoice(reference, "Voice", emptyMap())
        settings.value = SpeechSettings(offlineVoice = reference)
        val offline = mockk<OfflineSpeechRepository>()
        every { offline.changes } returns MutableStateFlow(0L)
        coEvery { offline.voices() } returns emptyList()
        val deleting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { offline.delete(reference) } coAnswers { deleting.complete(Unit); release.await(); 0L }
        coEvery { repository.update(any()) } coAnswers {
            settings.value = firstArg<(SpeechSettings) -> SpeechSettings>()(settings.value)
        }
        val availability = mockk<indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker>()
        every { availability.changes } returns MutableStateFlow(0L)
        val engines = mockk<indi.renakoni.nextvol.tts.offline.OfflineEngineRepository>()
        every { engines.changes } returns MutableStateFlow(0L)
        val library = OfflineSpeechSourcesViewModel(offline, repository, mockk(relaxed = true), availability, engines)
        try {
            runCurrent()
            val deletion = library.delete(voice)
            runCurrent()
            assertTrue(deleting.isCompleted)
            library.viewModelScope.cancel()
            release.complete(Unit)
            deletion.join()
            assertNull(settings.value.offlineVoice)
        } finally { release.complete(Unit); library.viewModelScope.cancel() }
    }

    @Test fun sameTargetRefreshRetainsKnownResultsUntilTheNewCheckCompletes() = runTest {
        runCurrent()
        assertEquals(SpeechError.NoEngine, model.state.value.error)
        var inspection = CompletableDeferred<SpeechEngineState>()
        coEvery { engines.inspect(any()) } coAnswers { inspection.await() }

        model.refresh()
        runCurrent()
        assertTrue(model.state.value.loading)
        assertEquals(SpeechError.NoEngine, model.state.value.error)
        inspection.completeExceptionally(SpeechException(SpeechError.NoEngine))
        runCurrent()
        assertFalse(model.state.value.loading)
        assertEquals(SpeechError.NoEngine, model.state.value.error)

        inspection = CompletableDeferred()
        coEvery { engines.installed() } returns listOf(engine)
        model.refresh()
        runCurrent()
        assertEquals(SpeechError.NoEngine, model.state.value.error)
        assertTrue(model.state.value.engines.isEmpty())
        inspection.complete(SpeechEngineState(engine, voices, "voice"))
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals(listOf(engine), model.state.value.engines)
        assertEquals(voices, model.state.value.voices)

        inspection = CompletableDeferred()
        model.refresh()
        runCurrent()
        assertEquals(voices, model.state.value.voices)
        inspection.completeExceptionally(SpeechException(SpeechError.EngineUnavailable))
        runCurrent()
        assertEquals(SpeechError.EngineUnavailable, model.state.value.error)
        assertTrue(model.state.value.voices.isEmpty())
        assertFalse(model.state.value.loading)
        assertEquals(settings.value, model.state.value.settings)
    }

    @Test fun changingEnginesClearsOldResultsAndLateInspectionCannotReplaceTheNewEngine() = runTest {
        val oldEngine = SpeechEngine("old", "Old")
        coEvery { engines.installed() } returns listOf(oldEngine)
        coEvery { engines.inspect("") } returns SpeechEngineState(oldEngine, voices, "voice")
        runCurrent()
        assertEquals(voices, model.state.value.voices)
        val oldInspection = CompletableDeferred<SpeechEngineState>()
        coEvery { engines.inspect("") } coAnswers { withContext(NonCancellable) { oldInspection.await() } }
        model.refresh()
        runCurrent()

        val newInspection = CompletableDeferred<SpeechEngineState>()
        coEvery { engines.installed() } returns listOf(engine)
        coEvery { engines.inspect(engine.packageName) } coAnswers { newInspection.await() }
        settings.value = settings.value.copy(engine = engine.packageName)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.error)
        assertTrue(model.state.value.voices.isEmpty())
        oldInspection.complete(SpeechEngineState(oldEngine, voices, "voice"))
        runCurrent()
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.voices.isEmpty())
        newInspection.complete(SpeechEngineState(engine, voices, "voice"))
        runCurrent()
        val selected = model.state.value
        assertEquals(listOf(engine), selected.engines)
        assertEquals(voices, selected.voices)
        assertFalse(selected.loading)
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
