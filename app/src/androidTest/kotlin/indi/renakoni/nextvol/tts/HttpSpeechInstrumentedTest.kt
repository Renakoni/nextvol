package indi.renakoni.nextvol.tts

import android.content.ContextWrapper
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.network.NetworkGrant
import hnovel.network.SourceBroker
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResult
import hnovel.network.SourceScope
import hnovel.network.SourceSession
import hnovel.speech.HttpSpeechClient
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.data.web.AndroidSourceStorageCipher
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class HttpSpeechInstrumentedTest {
    @Test fun httpsPreviewCleanupSurvivesCompletionReplacementAndShutdown(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "https-speech-cleanup-${UUID.randomUUID()}").apply { mkdirs() }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val transports = mutableListOf<SourceSession>()
        val closed = Channel<Unit>(Channel.UNLIMITED)
        try {
            MockWebServer().use { server ->
                val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
                server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setBody(okio.Buffer().write(wave()))
                }
                server.start()
                val url = server.url("/voice").toString()
                val source = previewHttpSpeech(buildJsonObject { put("name", "HTTPS voice"); put("url", url) }.toString()).sources.single()
                val grants = listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true))
                withContext(Dispatchers.Main) {
                    val policy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(policy).detectNetwork().penaltyDeathOnNetwork().build())
                    val player = ExoPlayer.Builder(context).build()
                    val audio = File(directory, "audio")
                    val session = ReadAloudSession(scope, SpeechChapterSource { _, _ -> error("Preview does not load books") },
                        { SpeechSettings() }, object : SpeechProgressStore {
                            override suspend fun load(bookId: String): SpeechBookmark? = null
                            override suspend fun save(bookmark: SpeechBookmark) = error("Preview does not save progress")
                        }, {
                            object : SpeechSynthesizer {
                                private var owner: SourceBroker? = null
                                private var client: HttpSpeechClient? = null
                                override suspend fun open(settings: SpeechSettings): String = withContext(Dispatchers.IO) {
                                    val broker = SourceBroker(File(directory, UUID.randomUUID().toString()).toPath())
                                    owner = broker
                                    val transport = broker.open(SourceScope("speech", source.id, "http-tts"), grants)
                                    transports += transport
                                    // Trust only this fixture certificate through the production source policy.
                                    val rejected = transport.execute(BrokerRequest("certificate", url))
                                    check(rejected is BrokerResult.Failure && rejected.certificate != null) {
                                        "Fixture certificate was not reported: $rejected"
                                    }
                                    transport.approveCertificate(checkNotNull(rejected.certificate))
                                    client = HttpSpeechClient(source, broker, grants, authority, executor::execute)
                                    source.name
                                }
                                override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
                                    val bytes = checkNotNull(client).synthesize(text)
                                    withContext(Dispatchers.IO) { output.writeBytes(bytes); validateHttpSpeechAudio(output) }
                                    return emptyList()
                                }
                                override fun cancel() { client?.cancel() }
                                override fun close() {
                                    assertNotSame("TLS teardown must leave the main thread", Looper.getMainLooper().thread, Thread.currentThread())
                                    try { client?.close() } finally { owner?.close() }
                                    closed.trySend(Unit).getOrThrow()
                                }
                            }
                        }, ExoSpeechPlayback(player, context), audio)
                    suspend fun awaitPhase(phase: SpeechPhase) = withTimeout(20_000) {
                        val state = session.state.first { it.phase == phase || it.phase == SpeechPhase.Failed }
                        assertEquals(state.error?.toString(), phase, state.phase)
                    }
                    suspend fun awaitCleanup() = withTimeout(10_000) {
                        closed.receive()
                        scope.coroutineContext[Job]!!.children.toList().joinAll()
                        assertTrue(audio.listFiles().orEmpty().isEmpty())
                    }
                    fun preview(text: String) = session.start(SpeechRequest("", "", previewText = text))
                    try {
                        preview("Natural completion.")
                        awaitPhase(SpeechPhase.Playing)
                        awaitPhase(SpeechPhase.Completed)
                        awaitCleanup()
                        preview("Old preview.")
                        awaitPhase(SpeechPhase.Playing)
                        preview("Latest preview.")
                        withTimeout(10_000) { closed.receive() }
                        awaitPhase(SpeechPhase.Playing)
                        assertEquals("Latest preview.", session.state.value.request?.previewText)
                        assertEquals(3, transports.size)
                        scope.cancel()
                        session.stop()
                        awaitCleanup()
                        assertEquals(SpeechPhase.Stopped, session.state.value.phase)
                        assertNull(session.state.value.error)
                        assertTrue(transports.all { it.closed })
                        assertEquals(3, server.requestCount)
                    } finally {
                        session.stop()
                        scope.cancel()
                        try { withTimeout(10_000) { scope.coroutineContext[Job]!!.join() } }
                        finally {
                            try { player.release() } finally { StrictMode.setThreadPolicy(policy) }
                        }
                    }
                }
            }
        } finally { executor.close(); directory.deleteRecursively() }
    }

    @Test fun isolatedSpeechPostPreservesTextAndPlaybackSpeedChangesDurationWithoutAnotherRequest(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "http-speech-test-${UUID.randomUUID()}").apply { mkdirs() }
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            MockWebServer().use { server ->
                server.start()
                val url = server.url("/").toString()
                val rule = "@js:" + JsonPrimitive("$url,") + "+JSON.stringify({method:'POST',body:{text:speakText,speed:speakSpeed}})"
                val source = previewHttpSpeech(buildJsonObject { put("name", "Device voice"); put("url", rule) }.toString()).sources.single()
                // This deterministic source ignores the requested synthesis rate.
                val audio = wave(seconds = 4)
                server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(audio)))
                SourceBroker(File(directory, "runtime").toPath()).use { owner ->
                    HttpSpeechClient(source, owner, listOf(NetworkGrant(url, true)), authority, executor::execute).use { client ->
                        val text = "中文 {{40+2}} {{speakSpeed}} @js:literal <js>literal</js>"
                        val file = File(directory, "speech.wav").apply { writeBytes(client.synthesize(text)) }
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        assertEquals(text, body.getValue("text").jsonPrimitive.content)
                        assertEquals(10, body.getValue("speed").jsonPrimitive.int)
                        assertArrayEquals(audio, file.readBytes())
                        validateHttpSpeechAudio(file)
                        withContext(Dispatchers.Main) {
                            val player = ExoPlayer.Builder(context).build()
                            try {
                                val playback = ExoSpeechPlayback(player, context)
                                val clip = SpeechClip(prepareSpeechPlan(SpeechChapter("", "", "", "", text),
                                    SpeechSettings()) {}, 0, file)
                                val durations = mutableListOf<Long>()
                                for (speed in listOf(1f, 2f)) {
                                    var startedAt: Long? = null
                                    withTimeout(10_000) {
                                        val playing = launch {
                                            playback.play(clip, false, onRange = {}) { phase, _ ->
                                                if (phase == SpeechPhase.Playing && startedAt == null)
                                                    startedAt = SystemClock.elapsedRealtime()
                                            }
                                        }
                                        while (player.playbackState != Player.STATE_READY) delay(10)
                                        val pausedAt = player.currentPosition
                                        playback.setSpeed(speed)
                                        delay(150)
                                        assertFalse(player.playWhenReady)
                                        assertFalse(player.isPlaying)
                                        assertEquals(pausedAt, player.currentPosition)
                                        assertEquals(speed, player.playbackParameters.speed)
                                        assertEquals(1f, player.playbackParameters.pitch)
                                        playback.resume()
                                        playing.join()
                                    }
                                    assertNotNull(startedAt)
                                    durations += SystemClock.elapsedRealtime() - requireNotNull(startedAt)
                                }
                                assertTrue("Normal playback must consume the four-second audio: $durations", durations[0] >= 3000)
                                assertTrue("2x must take about half the real time: $durations",
                                    durations[1].toDouble() / durations[0] in 0.35..0.75)
                                assertEquals(1, server.requestCount)
                            } finally { player.release() }
                        }
                        file.writeText("{\"error\":\"not audio\"}")
                        assertThrows(SpeechException::class.java) { validateHttpSpeechAudio(file) }
                    }
                }
            }
        } finally { executor.close(); directory.deleteRecursively() }
    }

    @Test fun importedDefinitionsUseTheProductionCipherAndSurviveReopening() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "http-speech-store-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val raw = """{"id":123,"name":"Voice","url":"https://example.org","header":{"Authorization":"private-test-token"},"unknown":{"value":42}}"""
            val source = previewHttpSpeech(raw).sources.single()
            val authority = ExecutionAuthority()
            HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority).save(listOf(SavedHttpSpeechSource(source, listOf("https://example.org:443"))))
            assertFalse(File(directory, "http-speech.enc").readBytes().toString(Charsets.UTF_8).contains("private-test-token"))
            val reopened = HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority)
            assertEquals(source.raw, reopened.sources().single { it.definition.id == source.id }.definition.raw)
            val oldTicket = reopened.withSource(source.id) { _, runtime ->
                runtime.mkdirs()
                File(runtime, "test-cache").writeText("old state")
                authority.issue(source.id, "http-tts", source.revision, "speech")
            }!!
            reopened.recordDeniedOrigins(source.id, listOf("https://audio.example.org:443", "https://audio.example.org/private?token=secret"))
            assertEquals(listOf("https://audio.example.org:443"), reopened.deniedOrigins.value[source.id])
            reopened.save(listOf(reopened.sources().single { it.definition.id == source.id }.copy(origins = listOf("https://example.org:443", "https://audio.example.org:443"))))
            assertFalse(authority.accepts(oldTicket))
            reopened.withSource(source.id) { _, runtime -> assertFalse(runtime.exists()) }
            assertTrue(reopened.deniedOrigins.value[source.id].isNullOrEmpty())
            reopened.delete(source.id)
            assertEquals(99, reopened.sources().size)
            assertTrue(reopened.sources().all { it.isBuiltIn })
            assertNull(reopened.withSource(source.id) { _, _ -> error("Deleted source cannot reopen") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun builtInCredentialsUseTheProductionCipherWithoutSavingTheWholeCatalog() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "builtin-speech-store-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
        try {
            val authority = ExecutionAuthority()
            val repository = HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority)
            assertEquals(99, repository.sources().size)
            assertFalse(File(directory, "http-speech.enc").exists())
            val source = repository.sources().first { it.group == "起点" }
            repository.configure(source.definition.id, mapOf("Token" to "synthetic-private-token"))
            val bytes = File(directory, "http-speech.enc").readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains("synthetic-private-token"))
            assertTrue(bytes.size < 4096)
            val reopened = HttpSpeechRepository(isolated, AndroidSourceStorageCipher(), authority)
            assertEquals(99, reopened.sources().size)
            assertTrue(reopened.sources().filter { it.group == source.group }.all { it.credentials["Token"] == "synthetic-private-token" })
            reopened.configure(source.definition.id, emptyMap())
            assertTrue(reopened.sources().filter { it.group == source.group }.all { !it.isConfigured })
            assertEquals(99, reopened.sources().size)
        } finally { directory.deleteRecursively() }
    }

    private fun wave(seconds: Int = 1): ByteArray {
        val rate = 16000
        val samples = ShortArray(rate * seconds) { (sin(it * 2 * Math.PI * 440 / rate) * 4000).toInt().toShort() }
        return ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size * 2); samples.forEach(::putShort)
        }.array()
    }
}
