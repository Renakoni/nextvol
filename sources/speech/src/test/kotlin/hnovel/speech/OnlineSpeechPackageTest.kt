package hnovel.speech

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineSpeechPackageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun unavailableNativeRuntimeReturnsAnUnsupportedDependency() = runBlocking {
        val client = OnlineSpeechClient(buildJsonObject { putJsonObject("engine") { put("code", "vivo") } },
            execute = { error("Unexpected request") }, stream = { _, _ -> error("Unexpected stream") },
            createSdkBackend = { _, _, _ -> object : OnlineSpeechBackend {
                override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray =
                    throw UnsatisfiedLinkError("Unsupported ABI")
            } })
        try { client.synthesize("正文", 1f, 1f, 100, null); fail("Native linkage error escaped") }
        catch (failure: HttpSpeechException) { assertEquals(HttpSpeechError.UnsupportedDependency, failure.error) }
    }

    @Test fun dedicatedPackagesUseBrokerWithoutExecutingScripts() = runBlocking {
        fun value(text: String) = SpeechPackageValue.Scalar(text, null, false)
        SourceBroker(directory.root.toPath(), okhttp3.Dns { error("Unapproved request resolved DNS") }).use { broker ->
            for (code in listOf("ggtrans", "huoshan", "namiai", "filmora", "gemini", "xfpeiyin", "peiyinya", "readspk", "doubao")) {
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
                assertEquals(code in setOf("namiai", "doubao"), definition.supportsStreaming)
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
