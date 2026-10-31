package indi.renakoni.nextvol.tts.offline

import android.content.Context
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechException
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.validateSpeechWav
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OfflineSpeechSynthesizerTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cancelledInitializationKeepsItsSlotAndReleasesItEvenWhenTeardownThrows() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = AtomicInteger()
        val cancellationFailure = IOException("vendor cancellation failed")
        val closeFailure = IOException("vendor teardown failed")
        val engine = synthesizer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            object : FakeEngine() {
                override fun cancel() { throw cancellationFailure }
                override fun close() { destroyed.incrementAndGet(); throw closeFailure }
            }
        }
        val nextEntered = CompletableDeferred<Unit>()
        val next = synthesizer { nextEntered.complete(Unit); FakeEngine() }
        var openingFailure: Throwable? = null
        val opening = launch { openingFailure = runCatching { engine.open(SpeechSettings()) }.exceptionOrNull() }
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
            assertTrue(openingFailure is CancellationException)
            assertTrue(openingFailure!!.suppressed.contains(closeFailure))
            assertTrue(closeFailure.suppressed.contains(cancellationFailure))
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

    @Test fun cancellationWaitsForGenerationAndDestructionBeforeOpeningAnotherInstance() = runBlocking {
        val generationEntered = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val destructionEntered = CountDownLatch(1)
        val releaseDestruction = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val destroyed = AtomicInteger()
        val engine = synthesizer {
            object : FakeEngine() {
                override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
                    emit(byteArrayOf(1, 2), 2)
                    generationEntered.countDown()
                    check(releaseGeneration.await(10, TimeUnit.SECONDS))
                    assertTrue(cancelled.get())
                    emit(byteArrayOf(3, 4), 2)
                }
                override fun cancel() { cancelled.set(true) }
                override fun close() {
                    destructionEntered.countDown()
                    check(releaseDestruction.await(10, TimeUnit.SECONDS))
                    destroyed.incrementAndGet()
                }
            }
        }
        val nextEntered = CompletableDeferred<Unit>()
        val next = synthesizer { nextEntered.complete(Unit); FakeEngine() }
        val output = File(files.root, "cancelled.wav")
        engine.open(SpeechSettings())
        val generation = launch { engine.synthesize("测试取消", output) }
        var closing: Job? = null
        var replacement: Job? = null
        try {
            withContext(Dispatchers.IO) { check(generationEntered.await(10, TimeUnit.SECONDS)) }
            assertTrue(File(output.path + ".part").exists())
            engine.cancel()
            generation.cancel()
            val closeStarted = CompletableDeferred<Unit>()
            closing = launch(Dispatchers.IO) { closeStarted.complete(Unit); engine.close() }
            closeStarted.await()
            replacement = launch(start = CoroutineStart.UNDISPATCHED) { next.open(SpeechSettings()) }
            assertEquals(1L, destructionEntered.count)
            assertFalse(nextEntered.isCompleted)
            releaseGeneration.countDown()
            withContext(Dispatchers.IO) { check(destructionEntered.await(10, TimeUnit.SECONDS)) }
            assertFalse(nextEntered.isCompleted)
            assertEquals(0, destroyed.get())
            releaseDestruction.countDown()
            withTimeout(10_000) { generation.join(); closing.join(); replacement.join() }
            assertEquals(1, destroyed.get())
            assertTrue(nextEntered.isCompleted)
            assertFalse(output.exists())
            assertFalse(File(output.path + ".part").exists())
        } finally {
            releaseGeneration.countDown()
            releaseDestruction.countDown()
            generation.cancelAndJoin()
            closing?.join()
            replacement?.cancelAndJoin()
            withContext(Dispatchers.IO) { engine.close(); next.close() }
        }
    }

    @Test fun failedInitializationReleasesTheSlotAndPreservesTheCause() = runBlocking {
        val cause = IOException("runtime could not initialize")
        val engine = synthesizer { throw cause }
        val next = synthesizer { FakeEngine() }
        try {
            val failure = runCatching { engine.open(SpeechSettings()) }.exceptionOrNull()
            assertTrue(failure is SpeechException)
            assertEquals(SpeechError.OfflineUnavailable, (failure as SpeechException).error)
            assertSame(cause, failure.cause)
            withTimeout(10_000) { assertEquals("bdetts", next.open(SpeechSettings())) }
        } finally {
            withContext(Dispatchers.IO) { engine.close(); next.close() }
        }
    }

    @Test fun pcmCallbacksProduceAValidatedWavWithTheFinalSampleRate() = runBlocking {
        val engine = synthesizer {
            object : FakeEngine() {
                override var sampleRate = 16_000
                override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
                    assertEquals("正文试听", text)
                    assertEquals(1.25f, speed)
                    emit(byteArrayOf(1, 2, 3, 99), 3)
                    sampleRate = 22_050
                    emit(byteArrayOf(4, 5, 6, 99), 3)
                }
            }
        }
        val output = File(files.root, "complete.wav")
        try {
            engine.open(SpeechSettings(rate = 1.25f))
            assertTrue(engine.synthesize("正文试听", output).isEmpty())
            validateSpeechWav(output)
            val wave = output.readBytes()
            assertEquals(22_050, ByteBuffer.wrap(wave, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), wave.copyOfRange(44, wave.size))
            assertFalse(File(output.path + ".part").exists())
        } finally {
            withContext(Dispatchers.IO) { engine.close() }
        }
    }

    private fun synthesizer(factory: () -> OfflineEngine) =
        OfflineSpeechSynthesizer(mockk<Context>(), files.root, OfflineVoiceConfig("bdetts", "test"), factory)

    private open class FakeEngine : OfflineEngine {
        override val sampleRate = 16_000
        override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) = Unit
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
