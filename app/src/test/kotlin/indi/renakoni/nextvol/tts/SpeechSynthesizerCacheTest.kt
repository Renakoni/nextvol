package indi.renakoni.nextvol.tts

import kotlinx.coroutines.*
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SpeechSynthesizerCacheTest {
    @Before fun logging() { mockkStatic(Log::class); every { Log.d(any(), any()) } returns 0 }
    @After fun restoreLogging() { unmockkStatic(Log::class) }
    private class Engine : SpeechSynthesizer {
        var opens = 0
        var closes = 0
        var work: (suspend () -> Unit)? = null
        override suspend fun open(settings: SpeechSettings): String { opens++; return settings.voice }
        override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
            work?.invoke()
            check(closes == 0)
            return emptyList()
        }
        override fun cancel() = Unit
        override fun close() { closes++ }
    }

    @Test fun switchingBackReusesTheVoiceAndCapacityEvictsTheLeastRecentlyUsed() = runBlocking {
        val engines = mutableMapOf<String, Engine>()
        val caches = SpeechSynthesizerCaches()
        val cache = CachedSpeechSynthesizer(caches, { settings ->
            SpeechRuntime(Engine().also { engines[settings.voice] = it })
        }, { 0L })
        fun voice(id: String) = SpeechSettings(voice = id, engineCacheCapacity = 3)
        try {
            cache.open(voice("A")); cache.open(voice("B")); cache.open(voice("C"))
            cache.open(voice("A")); cache.open(voice("D"))
            assertEquals(1, engines.getValue("A").opens)
            assertEquals(0, engines.getValue("A").closes)
            assertEquals(1, engines.getValue("B").closes)
            assertEquals(3, caches.count.value)
        } finally { cache.close() }
        assertEquals(0, caches.count.value)
        assertTrue(engines.values.all { it.closes == 1 })
    }

    @Test fun aSharedModelMayStayButConflictingModelsCloseBeforeTheNextOpen() = runBlocking {
        val engines = mutableListOf<Engine>()
        val cache = CachedSpeechSynthesizer(SpeechSynthesizerCaches(), { settings ->
            val engine = Engine().also(engines::add)
            SpeechRuntime(engine, "sherpa", settings.engine)
        }, { 0L })
        try {
            cache.open(SpeechSettings(engine = "model-a", voice = "speaker-1"))
            cache.open(SpeechSettings(engine = "model-a", voice = "speaker-2"))
            assertTrue(engines.all { it.closes == 0 })
            cache.open(SpeechSettings(engine = "model-b", voice = "speaker-3"))
            assertEquals(listOf(1, 1, 0), engines.map { it.closes })
        } finally { cache.close() }
    }

    @Test fun clearWaitsForSynthesisAndTheNextSegmentReopensWithoutAnExplicitVoiceChange() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engines = mutableListOf<Engine>()
        val caches = SpeechSynthesizerCaches()
        val cache = CachedSpeechSynthesizer(caches, {
            SpeechRuntime(Engine().also(engines::add))
        }, { 0L })
        try {
            cache.open(SpeechSettings(voice = "A"))
            engines.single().work = { entered.complete(Unit); release.await() }
            val synthesis = launch { cache.synthesize("text", File("unused.wav")) }
            entered.await()
            val clearing = launch(start = CoroutineStart.UNDISPATCHED) { caches.clear() }
            assertFalse(clearing.isCompleted)
            assertEquals(0, engines.single().closes)
            release.complete(Unit)
            synthesis.join(); clearing.join()
            assertEquals(1, engines.single().closes)
            assertEquals(0, caches.count.value)
            cache.synthesize("next", File("unused.wav"))
            assertEquals(2, engines.size)
            assertEquals(1, caches.count.value)
        } finally { cache.close() }
    }

    @Test fun changedRevisionsAndResumingAnExpiredVoiceOpenANewEngine() = runBlocking {
        var time = 0L
        val engines = mutableListOf<Engine>()
        val cache = CachedSpeechSynthesizer(SpeechSynthesizerCaches(), {
            SpeechRuntime(Engine().also(engines::add))
        }, { time })
        val first = SpeechSettings(httpSource = "voice", httpRevision = "1")
        try {
            cache.open(first)
            cache.open(first.copy(httpRevision = "2"))
            assertEquals(2, engines.size)
            time = 300_000L
            cache.synthesize("resume", File("unused.wav"))
            assertEquals(listOf(1, 1, 0), engines.map { it.closes })
        } finally { cache.close() }
    }
}
