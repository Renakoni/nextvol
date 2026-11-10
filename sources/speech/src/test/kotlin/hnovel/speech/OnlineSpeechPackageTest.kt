package hnovel.speech

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import hnovel.network.SourceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineSpeechPackageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun plannedTextReachesStreamingAndCompleteBackendsUnchanged() = runBlocking {
        val text = "甲".repeat(119) + "。" + "乙".repeat(119) + "。"
        val synthesized = mutableListOf<String>()
        val packet = ByteArray(MAX_AUDIO_BYTES / 2 + 3)
        val received = mutableListOf<Pair<Int, Int>>()
        val backend = object : StreamingOnlineSpeechBackend {
            override suspend fun stream(text: String, rate: Float, volume: Float, receive: (ByteArray, Int, Int) -> Unit) {
                synthesized += text
                repeat(2) { receive(packet, 1, packet.size - 2) }
            }
            override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
                synthesized += text
                return text.toByteArray()
            }
        }
        val definition = HttpSpeechDefinition.parse(buildJsonObject {
            put("name", "Native voice"); put("url", "https://tts-v2.vivo.com.cn/tts")
            putJsonObject("multiTts") { putJsonObject("engine") { put("code", "vivo") } }
        })
        SourceBroker(directory.root.toPath()).use { broker ->
            HttpSpeechClient(definition, broker, emptyList(), ExecutionAuthority(),
                createSdkBackend = { _, _, _ -> backend }, run = { _, _, _, _ -> error("Unexpected script") }).use { client ->
                assertTrue(client.synthesize(text, 1.5f, 2f) { bytes, offset, count ->
                    assertSame(packet, bytes)
                    received += offset to count
                }.isEmpty())
                assertEquals(List(2) { 1 to packet.size - 2 }, received)
                assertArrayEquals(text.toByteArray(), client.synthesize(text, 1.5f, 2f))
                assertEquals(listOf(text, text), synthesized)
            }
        }
    }

    @Test fun nativeFailureIsMappedAndVendorCancellationCannotSkipSessionCleanup(): Unit = runBlocking {
        val definition = HttpSpeechDefinition.parse(buildJsonObject {
            put("name", "Native voice"); put("url", "https://tts-v2.vivo.com.cn/tts")
            putJsonObject("multiTts") { putJsonObject("engine") { put("code", "vivo") } }
        })
        var cancellations = 0
        var closed = false
        SourceBroker(directory.root.toPath()).use { broker ->
            val client = HttpSpeechClient(definition, broker, emptyList(), ExecutionAuthority(),
                createSdkBackend = { _, _, _ -> object : OnlineSpeechBackend {
                    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray =
                        throw UnsatisfiedLinkError("Unsupported ABI")
                    override fun cancel() { cancellations++; error("Vendor cancellation failed") }
                    override fun close() { try { cancel() } finally { closed = true } }
                } }, run = { _, _, _, _ -> error("Unexpected script") })
            val session = broker.open(SourceScope("speech", definition.id, "http-tts"), emptyList())
            try { client.synthesize("正文"); fail("Native linkage error escaped") }
            catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.UnsupportedDependency, failure.error) }
            assertThrows(IllegalStateException::class.java) { client.close() }
            assertEquals(1, cancellations)
            assertTrue(closed)
            assertThrows(CancellationException::class.java) { session.permissionFailure(definition.url) }
        }
    }

    @Test fun dedicatedPackagesUseBrokerWithoutExecutingScripts() = runBlocking {
        fun value(text: String) = SpeechPackageValue.Scalar(text, null, false)
        SourceBroker(directory.root.toPath(), okhttp3.Dns { error("Unapproved request resolved DNS") }).use { broker ->
            for (code in listOf("ggtrans", "huoshan", "gemini", "xfpeiyin", "peiyinya", "readspk", "doubao")) {
                val fields = mapOf("code" to value(code), "name" to value(code)) +
                    (if (code in setOf("xfpeiyin", "peiyinya")) emptyMap() else mapOf("type" to value("inner"))) +
                    (if (code == "doubao") mapOf("param" to value("{\"cookie\":\"test-session\"}")) else emptyMap())
                val voice = SpeechPackageVoice("voice", "Speaker", SpeechPackageValue.Mapping(mapOf(
                    "code" to value("voice"), "name" to value("Speaker"), "type" to value("1"),
                ), null))
                val engine = SpeechPackageEngine(code, code, fields["type"]?.value, SpeechPackageValue.Mapping(fields, null),
                    listOf(voice), SpeechPackageSupport.HttpAdapter, emptyList(), emptyList())
                val imported = previewHttpSpeechPackage(engine)
                assertNull(code, imported.error)
                val definition = imported.sources.single()
                assertEquals(code == "doubao", definition.supportsStreaming)
                HttpSpeechClient(definition, broker, emptyList(), ExecutionAuthority()) { _, _, _, _ ->
                    error("Dedicated backend executed a script")
                }.use { client ->
                    val error = try {
                        if (code == "doubao") client.refreshVoices() else client.synthesize("A real sentence.")
                        error("Unapproved request succeeded")
                    }
                        catch (failure: HttpSpeechException) { failure }
                    assertEquals(code, HttpSpeechError.PermissionDenied, error.error)
                }
            }
        }
    }
}
