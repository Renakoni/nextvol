package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.viewModelScope
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.tts.HttpSpeechRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPackageEngineImportTest {
    @Test fun failedEngineImportCanBeDismissedAndRetriedWithoutAnInlineInstallError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = mockk<SpeechSettingsEditor>()
        every { settings.changes } returns emptyFlow()
        val http = mockk<HttpSpeechRepository>()
        val engine = SpeechEngineConfig("edgetts", "Edge TTS")
        coEvery { http.registerEngine(engine) } throws IOException("Unable to save")
        val model = SpeechPackagePreviewViewModel(mockk(), mockk(), settings, mockk(), http, mockk(),
            mockk(relaxed = true), mockk())
        var saved = 0
        try {
            model.importEngine(engine) { saved++ }
            runCurrent()
            assertTrue(model.state.value.engineImportFailed)
            assertFalse(model.state.value.installing)
            assertNull(model.state.value.installError)
            assertEquals(0, saved)

            model.dismissEngineImportError()
            assertFalse(model.state.value.engineImportFailed)

            coEvery { http.registerEngine(engine) } returns Unit
            model.importEngine(engine) { saved++ }
            runCurrent()
            assertEquals(1, saved)
            assertFalse(model.state.value.engineImportFailed)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
