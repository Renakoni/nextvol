package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import hnovel.network.StorageCipher
import hnovel.speech.HttpSpeechClient
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import hnovel.speech.OfflineVoiceConfig
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.offline.OfflineSpeechSynthesizer
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
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        val caller = Thread.currentThread()
        val definition = previewHttpSpeech("""{"name":"Voice","url":"https://example.org/voice"}""").sources.single()
        val source = SavedHttpSpeechSource(definition, listOf("https://example.org:443"))
        val repository = mockk<HttpSpeechRepository>()
        coEvery { repository.sources() } returns listOf(source)
        coEvery { repository.withSource<String>(definition.id, any()) } answers {
            secondArg<(SavedHttpSpeechSource, File) -> String>()(source, files.root)
        }
        every { repository.recordSynthesis(any(), any()) } just Runs
        mockkConstructor(SourceBroker::class, HttpSpeechClient::class)
        try {
            every { anyConstructed<SourceBroker>().open(any(), any()) } returns mockk(relaxed = true)
            every { anyConstructed<SourceBroker>().close() } just Runs
            every { anyConstructed<HttpSpeechClient>().cancel() } just Runs
            every { anyConstructed<HttpSpeechClient>().updatedEngineParameters } returns null
            every { anyConstructed<HttpSpeechClient>().close() } throws IOException("close failed")
            coEvery { anyConstructed<HttpSpeechClient>().synthesize(any(), any(), any()) } coAnswers {
                assertNotSame("HTTP synthesis can close regional TLS connections", caller, Thread.currentThread())
                throw HttpSpeechException(HttpSpeechError.Network)
            }
            val provider = SpeechSynthesizers(mockk(), repository, StorageCipher.Plain, ExecutionAuthority(), mockk(), mockk(), mockk(), mockk(),
                mockk(relaxed = true)).synthesizer()
            assertEquals("Voice", provider.open(SpeechSettings(httpSource = definition.id)))
            try { provider.synthesize("Preview", File(files.root, "preview.wav")); fail("Expected network failure") }
            catch (failure: SpeechException) { assertEquals(SpeechError.Network, failure.error) }
            verify(exactly = 1) { repository.recordSynthesis(source, SpeechError.Network.messageResource) }
            provider.cancel()
            verify(exactly = 1) { anyConstructed<HttpSpeechClient>().cancel() }
            verify(exactly = 0) { anyConstructed<HttpSpeechClient>().close() }
            verify(exactly = 0) { anyConstructed<SourceBroker>().close() }
            assertThrows(IOException::class.java) { provider.close() }
            provider.close()
            provider.cancel()
            verify(exactly = 1) { anyConstructed<HttpSpeechClient>().close() }
            verify(exactly = 1) { anyConstructed<SourceBroker>().close() }
        } finally {
            unmockkConstructor(SourceBroker::class, HttpSpeechClient::class)
            unmockkStatic(android.util.Log::class)
        }
    }

    @Test fun onlineMscClosesTheCachedOfflineRuntimeBeforeOpeningItsSession() = runBlocking {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        val configuration = OfflineVoiceConfig("msctts", "offline")
        val reference = OfflineVoiceRef("offline", "1")
        val voice = InstalledOfflineVoice(reference, "Offline", emptyMap(), configuration = configuration)
        val offline = mockk<OfflineSpeechRepository>()
        coEvery { offline.voices() } returns listOf(voice)
        every { offline.engineConfiguration(voice) } returns null
        every { offline.configuration(voice, null) } returns configuration
        val lease = mockk<OfflineSpeechRepository.Lease>(relaxed = true)
        every { lease.voice } returns voice
        every { lease.configuration } returns configuration
        every { lease.directory } returns files.root
        every { offline.acquire(reference) } returns lease
        val availability = mockk<OfflineAvailabilityChecker>()
        every { availability.inspect(configuration, any(), any()) } returns OfflineAvailability()
        val source = newHttpVoice(SpeechEngineConfig("msctts", "Online"))
        val repository = mockk<HttpSpeechRepository>()
        coEvery { repository.sources() } returns listOf(source)
        coEvery { repository.withSource<String>(source.definition.id, any()) } answers {
            secondArg<(SavedHttpSpeechSource, File) -> String>()(source, files.root)
        }
        mockkConstructor(OfflineSpeechSynthesizer::class, SourceBroker::class, HttpSpeechClient::class)
        try {
            coEvery { anyConstructed<OfflineSpeechSynthesizer>().open(any()) } returns "Offline"
            every { anyConstructed<OfflineSpeechSynthesizer>().maxInputLength } returns 1000
            every { anyConstructed<OfflineSpeechSynthesizer>().close() } just Runs
            every { anyConstructed<SourceBroker>().open(any(), any()) } answers {
                verify(exactly = 1) { anyConstructed<OfflineSpeechSynthesizer>().close() }
                mockk(relaxed = true)
            }
            every { anyConstructed<SourceBroker>().close() } just Runs
            every { anyConstructed<HttpSpeechClient>().updatedEngineParameters } returns null
            every { anyConstructed<HttpSpeechClient>().cancel() } just Runs
            every { anyConstructed<HttpSpeechClient>().close() } just Runs
            val caches = SpeechSynthesizerCaches()
            val provider = SpeechSynthesizers(mockk(), repository, StorageCipher.Plain, ExecutionAuthority(), mockk(),
                offline, mockk(), availability, mockk(relaxed = true), caches).synthesizer()
            try {
                provider.open(SpeechSettings(offlineVoice = reference, engineCacheCapacity = 4))
                verify(exactly = 0) { anyConstructed<OfflineSpeechSynthesizer>().close() }
                provider.open(SpeechSettings(httpSource = source.definition.id, engineCacheCapacity = 4))
                assertEquals(1, caches.count.value)
                verify(exactly = 1) { lease.close() }
            } finally { provider.close() }
        } finally {
            unmockkConstructor(OfflineSpeechSynthesizer::class, SourceBroker::class, HttpSpeechClient::class)
            unmockkStatic(android.util.Log::class)
        }
    }
}
