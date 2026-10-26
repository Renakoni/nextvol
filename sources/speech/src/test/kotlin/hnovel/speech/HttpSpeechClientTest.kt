package hnovel.speech

import hnovel.execution.*
import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.TimeUnit

class HttpSpeechClientTest {
    @get:Rule val directory = TemporaryFolder()
    // Transport tests only; real platform decoding is verified separately with actual audio.
    private val audio = "RIFFxxxxWAVE".toByteArray() + ByteArray(100000) { (it % 255).toByte() }
    private val text = "中文 \"quoted\" & {{40+2}} {{speakSpeed}} @js:literal <js>literal</js>"

    private fun client(owner: SourceBroker, rule: String, grants: List<NetworkGrant> = emptyList(), header: String = ""): HttpSpeechClient {
        val source = previewHttpSpeech(buildJsonObject { put("name", "Voice"); put("url", rule); put("header", header) }.toString()).sources.single()
        return HttpSpeechClient(source, owner, grants, ExecutionAuthority()) { identity, task, limits, broker ->
            val wire = ExecutionWire.encode(identity, task, limits)
            ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8),
                HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun generatedPostAndStaticJsonExpressionPreserveTextAndDynamicHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/voice").toString()
            val rules = listOf(
                "@js:${JsonPrimitive("$url,")}+JSON.stringify({method:'POST',body:{text:speakText,speed:speakSpeed}})",
                "$url," + """{"method":"POST","body":{"text":"{{speakText + ''}}","speed":{{speakSpeed}}}}""",
            )
            SourceBroker(directory.root.toPath()).use { owner ->
                for (rule in rules) {
                    server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(okio.Buffer().write(audio)))
                    client(owner, rule, listOf(NetworkGrant(server.url("/").toString(), true)), "@js:({'X-Voice':'voice-'+speakSpeed})").use { source ->
                        assertArrayEquals(audio, source.synthesize(text))
                        val sent = server.takeRequest(3, TimeUnit.SECONDS)!!
                        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
                        assertEquals(text, body.getValue("text").jsonPrimitive.content)
                        assertEquals(10, body.getValue("speed").jsonPrimitive.int)
                        assertEquals("voice-10", sent.getHeader("X-Voice"))
                        assertEquals("POST", sent.method)
                    }
                }
            }
        }
    }

    @Test fun inlineAudioLargerThanOrdinaryUrlsIsBoundedAndDoesNotResolveDns() = runBlocking {
        val rule = "@js:" + JsonPrimitive("data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(audio))
        SourceBroker(directory.root.toPath(), okhttp3.Dns { error("Inline audio must not resolve DNS") }).use { owner ->
            client(owner, rule).use { assertArrayEquals(audio, it.synthesize(text)) }
        }
        assertEquals(HttpSpeechError.TooLarge, assertThrows(HttpSpeechException::class.java) {
            decodeSpeechDataUri("data:audio/mpeg;base64," + "A".repeat((MAX_AUDIO_BYTES + 2) / 3 * 4 + 4))
        }.error)
        assertEquals(HttpSpeechError.InvalidAudio, assertThrows(HttpSpeechException::class.java) {
            decodeSpeechDataUri("data:audio/mpeg;base64,not-base64")
        }.error)
    }

    @Test fun audioRedirectsFollowGrantsAndNeverAcceptJsonOrHttpErrors() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { owner ->
                client(owner, base, listOf(NetworkGrant(base, true))).use { source ->
                    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/audio"))
                    server.enqueue(MockResponse().setBody(okio.Buffer().write(audio)))
                    assertArrayEquals(audio, source.synthesize(text))
                    server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("{\"err_no\":502,\"token\":\"private\"}"))
                    val invalid = try { source.synthesize(text); error("Expected invalid audio") } catch (e: HttpSpeechException) { e }
                    assertEquals(HttpSpeechError.InvalidAudio, invalid.error)
                    assertFalse(invalid.toString().contains("private"))
                    server.enqueue(MockResponse().setResponseCode(503).setBody("private response"))
                    val failed = try { source.synthesize(text); error("Expected network failure") } catch (e: HttpSpeechException) { e }
                    assertEquals(HttpSpeechError.Network, failed.error)
                }
                client(owner, base).use { source ->
                    val denied = try { source.synthesize(text); error("Expected permission failure") } catch (e: HttpSpeechException) { e }
                    assertEquals(HttpSpeechError.PermissionDenied, denied.error)
                    assertEquals(listOf(sourceOrigin(base)), denied.deniedOrigins)
                }
            }
        }
    }

    @Test fun cancellationAbortsThePendingDownloadAndDoesNotReturnAudio() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath()).use { owner ->
                client(owner, base, listOf(NetworkGrant(base, true))).use { source ->
                    val work = async(Dispatchers.IO) { source.synthesize(text) }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                    source.cancel()
                    work.cancel()
                    withTimeout(3000) { work.join() }
                    assertTrue(work.isCancelled)
                }
            }
        }
    }

    @Test fun retirementRejectsLateResultsBeforeNetworkTeardown() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val authority = ExecutionAuthority()
        val source = previewHttpSpeech("""{"name":"Voice","url":"https://example.org/voice"}""").sources.single()
        SourceBroker(directory.root.toPath()).use { owner ->
            HttpSpeechClient(source, owner, emptyList(), authority) { identity, _, _, _ ->
                reached.complete(Unit)
                withContext(NonCancellable) { release.await() }
                assertFalse(authority.accepts(identity))
                ExecutionResult.Success("unused retired result")
            }.use { client ->
                val work = async { client.synthesize(text) }
                try {
                    reached.await()
                    client.cancel()
                    release.complete(Unit)
                    try { work.await(); fail("Retired synthesis returned audio") }
                    catch (_: CancellationException) { }
                } finally { release.complete(Unit); work.cancelAndJoin() }
            }
        }
    }
}
