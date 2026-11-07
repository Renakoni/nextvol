package indi.renakoni.nextvol.tts.matcha

import android.util.Log
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.offline.OfflinePcmFile
import hnovel.speech.discoverSherpaVoiceConfig
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MatchaSpeechSynthesizerTest {
    @get:Rule val files = TemporaryFolder()

    @Before fun mockLog() { mockkStatic(Log::class); every { Log.d(any(), any()) } returns 0 }
    @After fun restoreLog() { unmockkStatic(Log::class) }

    @Test fun cancelledInitializationKeepsTheSlotUntilNativeReturnsAndDeletesExactlyOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val native = object : FakeNative() {
            override fun create(configuration: MatchaNativeConfig): Long {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return 7
            }
            override fun destroy(handle: Long) { assertEquals(7L, handle); destroyed.incrementAndGet() }
        }
        val engine = MatchaSpeechSynthesizer(resources()) { native }
        val nextEntered = CompletableDeferred<Unit>()
        val next = MatchaSpeechSynthesizer(resources()) {
            object : FakeNative() {
                override fun create(configuration: MatchaNativeConfig): Long { nextEntered.complete(Unit); return 8 }
            }
        }
        val opening = launch { engine.open(SpeechSettings()) }
        var replacement: Job? = null
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            engine.cancel()
            opening.cancel()
            replacement = launch(start = CoroutineStart.UNDISPATCHED) { next.open(SpeechSettings()) }
            assertFalse(nextEntered.isCompleted)
            assertEquals(0, destroyed.get())
            release.countDown()
            withTimeout(10_000) { opening.join(); replacement.join() }
            assertTrue(nextEntered.isCompleted)
            assertEquals(1, destroyed.get())
        } finally {
            release.countDown()
            opening.cancelAndJoin()
            replacement?.cancelAndJoin()
            withContext(Dispatchers.IO) { engine.close(); next.close() }
        }
        assertEquals(1, destroyed.get())
    }

    @Test fun closeWaitsForCancelledGenerationAndNoPartialFileIsCommitted() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = CompletableDeferred<Unit>()
        val native = object : FakeNative() {
            override fun generate(handle: Long, text: String, speaker: Int, speed: Float,
                path: String, cancellation: MatchaCancellation) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                assertTrue(cancellation.isCancelled())
                File(path).writeText("late incomplete output")
            }
            override fun destroy(handle: Long) { destroyed.complete(Unit) }
        }
        val engine = MatchaSpeechSynthesizer(resources()) { native }
        val output = File(files.root, "cancelled.wav")
        engine.open(SpeechSettings())
        val generation = launch { engine.synthesize("测试取消", output) }
        var closing: Job? = null
        try {
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            engine.cancel()
            generation.cancel()
            val closeEntered = CompletableDeferred<Unit>()
            closing = launch(Dispatchers.IO) { closeEntered.complete(Unit); engine.close() }
            closeEntered.await()
            assertFalse(destroyed.isCompleted)
            release.countDown()
            withTimeout(10_000) { generation.join(); closing.join() }
            assertTrue(destroyed.isCompleted)
            assertFalse(output.exists())
            assertFalse(File(output.path + ".part").exists())
        } finally {
            release.countDown()
            generation.cancelAndJoin()
            closing?.join()
            withContext(Dispatchers.IO) { engine.close() }
        }
    }

    @Test fun escapedResourceIsRejectedBeforeNativeInitialization() = runBlocking {
        val resources = resources()
        val token = File(resources.directory, "tokens.txt")
        assertTrue(token.delete())
        val outside = files.newFile("outside.txt").apply { writeText("resource") }
        java.nio.file.Files.createSymbolicLink(token.toPath(), outside.toPath())
        val calls = AtomicInteger()
        val engine = MatchaSpeechSynthesizer(resources) {
            calls.incrementAndGet()
            FakeNative()
        }
        assertTrue(runCatching { engine.open(SpeechSettings()) }.isFailure)
        assertEquals(0, calls.get())
        engine.close()
    }

    @Test fun sharedModelKeepsSpeakerIdentityAndNativeRateWithoutApplyingSpeakerAdjustments() = runBlocking {
        val original = resources()
        val model = File(original.directory, "custom-acoustic.onnx")
        assertTrue(File(original.directory, original.configuration.model).renameTo(model))
        val configuration = original.configuration.copy(model = model.name, speakerId = 3,
            numThreads = 6, noiseScale = .4f, lengthScale = 1.2f, speed = .8f, pitch = 1.1f, volume = .6f)
        var received: MatchaNativeConfig? = null
        var created = 0
        var destroyed = 0
        val requests = mutableListOf<List<Number>>()
        val native = object : FakeNative() {
            override fun create(configuration: MatchaNativeConfig): Long {
                created++
                received = configuration
                return 1
            }
            override fun generate(handle: Long, text: String, speaker: Int, speed: Float,
                path: String, cancellation: MatchaCancellation) {
                assertEquals(1L, handle)
                assertFalse(cancellation.isCancelled())
                requests += listOf(speaker, speed)
                OfflinePcmFile(File(path), 22_050).use { audio ->
                    audio.append(byteArrayOf(1, 2), 2)
                    audio.finish(22_050)
                }
            }
            override fun destroy(handle: Long) { destroyed++ }
        }
        val engine = MatchaSpeechSynthesizer(MatchaResources(original.directory, configuration)) { native }
        val secondConfiguration = configuration.copy(speakerId = 5, speed = 1.5f, pitch = .9f, volume = 1.2f)
        val second = MatchaSpeechSynthesizer(MatchaResources(original.directory, secondConfiguration)) { native }
        try {
            engine.open(SpeechSettings(rate = 0f, pitch = 1.25f, volume = .5f))
            withTimeout(10_000) { second.open(SpeechSettings(rate = 1.06f)) }
            val actual = checkNotNull(received)
            assertEquals(model.path, actual.model)
            assertEquals(6, actual.numThreads)
            assertEquals(.4f, actual.noiseScale)
            assertEquals(1.2f, actual.lengthScale)
            assertEquals(1, created)
            engine.synthesize("第一位人物", File(files.root, "first.wav"))
            second.synthesize("第二位人物", File(files.root, "second.wav"))
            engine.close()
            assertEquals(0, destroyed)
            second.synthesize("第二位人物继续", File(files.root, "again.wav"))
            assertEquals(listOf(listOf(3, 0f), listOf(5, 53f / 34f), listOf(5, 53f / 34f)), requests)
        } finally { engine.close(); second.close() }
        assertEquals(1, destroyed)
    }

    private fun resources(): MatchaResources {
        val directory = files.newFolder()
        for (name in listOf("model-steps-3.onnx", "hifigan_v2.onnx", "tokens.txt", "lexicon.txt",
            "date.fst", "number.fst", "phone.fst", "dict/jieba.dict.utf8", "dict/hmm_model.utf8",
            "dict/user.dict.utf8", "dict/idf.utf8", "dict/stop_words.utf8")) {
            File(directory, name).apply { parentFile!!.mkdirs(); writeText("resource") }
        }
        return MatchaResources(directory, discoverSherpaVoiceConfig(directory.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toList()))
    }

    private open class FakeNative : MatchaNativeApi {
        override fun create(configuration: MatchaNativeConfig) = 1L
        override fun generate(handle: Long, text: String, speaker: Int, speed: Float,
            path: String, cancellation: MatchaCancellation) = Unit
        override fun destroy(handle: Long) = Unit
    }
}
