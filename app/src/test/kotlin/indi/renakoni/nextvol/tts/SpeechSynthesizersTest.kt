package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import hnovel.network.StorageCipher
import hnovel.speech.HttpSpeechClient
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import hnovel.speech.previewHttpSpeech
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SpeechSynthesizersTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun httpWorkLeavesTheCallerThreadAndCleanupSurvivesFailure() = runBlocking {
        val caller = Thread.currentThread()
        val definition = previewHttpSpeech("""{"name":"Voice","url":"https://example.org/voice"}""").sources.single()
        val source = SavedHttpSpeechSource(definition, listOf("https://example.org:443"))
        val repository = mockk<HttpSpeechRepository>()
        coEvery { repository.withSource<String>(definition.id, any()) } answers {
            secondArg<(SavedHttpSpeechSource, File) -> String>()(source, files.root)
        }
        every { repository.recordDeniedOrigins(any(), any()) } just Runs
        mockkConstructor(SourceBroker::class, HttpSpeechClient::class)
        try {
            every { anyConstructed<SourceBroker>().open(any(), any()) } returns mockk(relaxed = true)
            every { anyConstructed<SourceBroker>().close() } just Runs
            every { anyConstructed<HttpSpeechClient>().cancel() } just Runs
            every { anyConstructed<HttpSpeechClient>().close() } throws IOException("close failed")
            coEvery { anyConstructed<HttpSpeechClient>().synthesize(any(), any()) } coAnswers {
                assertNotSame("HTTP synthesis can close regional TLS connections", caller, Thread.currentThread())
                throw HttpSpeechException(HttpSpeechError.Network)
            }
            val provider = SpeechSynthesizers(mockk(), repository, StorageCipher.Plain, ExecutionAuthority(), mockk(), mockk(), mockk()).synthesizer()
            assertEquals("Voice", provider.open(SpeechSettings(httpSource = definition.id)))
            try { provider.synthesize("Preview", File(files.root, "preview.wav")); fail("Expected network failure") }
            catch (failure: SpeechException) { assertEquals(SpeechError.Network, failure.error) }
            provider.cancel()
            verify(exactly = 1) { anyConstructed<HttpSpeechClient>().cancel() }
            verify(exactly = 0) { anyConstructed<HttpSpeechClient>().close() }
            verify(exactly = 0) { anyConstructed<SourceBroker>().close() }
            assertThrows(IOException::class.java) { provider.close() }
            provider.close()
            provider.cancel()
            verify(exactly = 1) { anyConstructed<HttpSpeechClient>().close() }
            verify(exactly = 1) { anyConstructed<SourceBroker>().close() }
        } finally { unmockkConstructor(SourceBroker::class, HttpSpeechClient::class) }
    }
}
