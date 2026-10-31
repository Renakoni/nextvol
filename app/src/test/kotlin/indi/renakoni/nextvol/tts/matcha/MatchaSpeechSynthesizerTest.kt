package indi.renakoni.nextvol.tts.matcha

import indi.renakoni.nextvol.tts.SpeechSettings
import hnovel.speech.discoverSherpaVoiceConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MatchaSpeechSynthesizerTest {
    @get:Rule val files = TemporaryFolder()

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
            override fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation) {
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

    @Test fun selectedModelPathsAndVoiceParametersReachNativeWithoutASampleProfile() = runBlocking {
        val original = resources()
        val model = File(original.directory, "custom-acoustic.onnx")
        assertTrue(File(original.directory, original.configuration.model).renameTo(model))
        val configuration = original.configuration.copy(model = model.name, speakerId = 3,
            numThreads = 6, noiseScale = .4f, lengthScale = 1.2f, speed = .8f, pitch = 1.1f, volume = .6f)
        var received: MatchaNativeConfig? = null
        val engine = MatchaSpeechSynthesizer(MatchaResources(original.directory, configuration)) {
            object : FakeNative() {
                override fun create(configuration: MatchaNativeConfig): Long {
                    received = configuration
                    return 1
                }
            }
        }
        try {
            engine.open(SpeechSettings())
            val actual = checkNotNull(received)
            assertEquals(model.path, actual.model)
            assertEquals(3, actual.speakerId)
            assertEquals(6, actual.numThreads)
            assertEquals(.4f, actual.noiseScale)
            assertEquals(1.2f, actual.lengthScale)
            assertEquals(.8f, actual.speed)
            assertEquals(1.1f, actual.pitch)
            assertEquals(.6f, actual.volume)
        } finally { engine.close() }
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
        override fun generate(handle: Long, text: String, speed: Float, path: String, cancellation: MatchaCancellation) = Unit
        override fun destroy(handle: Long) = Unit
    }
}
