package indi.renakoni.nextvol.tts

import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechVoiceOperationsTest {
    private val http = mockk<HttpSpeechRepository>()
    private val offline = mockk<OfflineSpeechRepository>()
    private val settings = mockk<SpeechSettingsRepository>()
    private val controller = mockk<ReadAloudController>()
    private val operations = SpeechVoiceOperations(http, offline, settings, controller)

    @Test fun httpDisablingStopsAnAffectedBookBeforeWritingButEnablingDoesNotStop() = runTest {
        val source = SavedHttpSpeechSource(previewHttpSpeech(
            """{"id":"voice","name":"Voice","url":"https://example.org"}""").sources.single(), emptyList())
        val ids = setOf(source.definition.id)
        every { controller.state } returns MutableStateFlow(ReadAloudState(SpeechRequest("book", "chapter")))
        every { controller.command(SpeechAction.Stop) } returns Unit
        coEvery { settings.forBook("book") } returns SpeechSettings(speakerVoices = mapOf("role" to SpeechVoiceRef(httpSource = "voice")))
        coEvery { http.enable(ids, any()) } returns Unit

        operations.enableHttp(listOf(source), true)
        operations.enableHttp(listOf(source), false)

        coVerifyOrder {
            http.enable(ids, true)
            settings.forBook("book")
            controller.command(SpeechAction.Stop)
            http.enable(ids, false)
        }
        verify(exactly = 1) { controller.command(SpeechAction.Stop) }
    }

    @Test fun offlineDisablingRefreshesTheDefaultRevisionBeforeStoppingAndEnablingAlsoRefreshesIt() = runTest {
        val reference = OfflineVoiceRef("voice", "original")
        val voice = InstalledOfflineVoice(reference, "Voice", emptyMap())
        val disabled = voice.copy(reference = reference.copy(revision = "disabled"), enabled = false)
        val enabled = voice.copy(reference = reference.copy(revision = "enabled"))
        val candidate = SpeechVoiceRef(offlineVoice = reference)
        val original = SpeechSettings(offlineVoice = reference, voiceRoutingConfigured = true,
            narrationVoices = listOf(candidate), dialogueVoices = listOf(candidate), speakerVoices = mapOf("role" to candidate))
        var current = original
        every { controller.state } returns MutableStateFlow(ReadAloudState(SpeechRequest("book", "chapter")))
        every { controller.command(SpeechAction.Stop) } returns Unit
        coEvery { offline.enable(setOf(reference), false) } returns listOf(disabled)
        coEvery { offline.enable(setOf(disabled.reference), true) } returns listOf(enabled)
        coEvery { settings.updateAll(any()) } coAnswers {
            current = firstArg<(SpeechSettings) -> SpeechSettings>()(current)
        }
        coEvery { settings.forBook("book") } coAnswers { current }

        operations.enableOffline(listOf(voice), false)
        assertEquals(original.copy(offlineVoice = disabled.reference), current)
        operations.enableOffline(listOf(disabled), true)
        assertEquals(original.copy(offlineVoice = enabled.reference), current)

        coVerifyOrder {
            offline.enable(setOf(reference), false)
            settings.updateAll(any())
            settings.forBook("book")
            controller.command(SpeechAction.Stop)
            offline.enable(setOf(disabled.reference), true)
            settings.updateAll(any())
        }
        verify(exactly = 1) { controller.command(SpeechAction.Stop) }
    }
}
