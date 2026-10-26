package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import hnovel.network.StorageCipher
import hnovel.speech.HttpSpeechClient
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

    @Test fun httpCancellationOnlyRetiresAndCloseStillReleasesTheOwnerAfterFailure() = runBlocking {
        val definition = previewHttpSpeech("""{"name":"Voice","url":"https://example.org/voice"}""").sources.single()
        val source = SavedHttpSpeechSource(definition, listOf("https://example.org:443"))
        val repository = mockk<HttpSpeechRepository>()
        coEvery { repository.withSource<String>(definition.id, any()) } answers {
            secondArg<(SavedHttpSpeechSource, File) -> String>()(source, files.root)
        }
        mockkConstructor(SourceBroker::class, HttpSpeechClient::class)
        try {
            every { anyConstructed<SourceBroker>().open(any(), any()) } returns mockk(relaxed = true)
            every { anyConstructed<SourceBroker>().close() } just Runs
            every { anyConstructed<HttpSpeechClient>().cancel() } just Runs
            every { anyConstructed<HttpSpeechClient>().close() } throws IOException("close failed")
            val provider = SpeechSynthesizers(mockk(), repository, StorageCipher.Plain, ExecutionAuthority(), mockk()).synthesizer()
            assertEquals("Voice", provider.open(SpeechSettings(httpSource = definition.id)))
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
