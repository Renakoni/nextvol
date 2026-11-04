package hnovel.speech

import hnovel.execution.ExecutionAuthority
import hnovel.network.SourceBroker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineSpeechPackageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun dedicatedPackagesUseBrokerWithoutExecutingScripts() = runBlocking {
        fun value(text: String) = SpeechPackageValue.Scalar(text, null, false)
        SourceBroker(directory.root.toPath(), okhttp3.Dns { error("Unapproved request resolved DNS") }).use { broker ->
            for (code in listOf("ggtrans", "huoshan", "namiai", "filmora", "gemini", "xfpeiyin", "peiyinya")) {
                val fields = mapOf("code" to value(code), "name" to value(code)) +
                    if (code in setOf("xfpeiyin", "peiyinya")) emptyMap() else mapOf("type" to value("inner"))
                val voice = SpeechPackageVoice("voice", "Speaker", SpeechPackageValue.Mapping(mapOf(
                    "code" to value("voice"), "name" to value("Speaker"), "type" to value("1"),
                ), null))
                val engine = SpeechPackageEngine(code, code, fields["type"]?.value, SpeechPackageValue.Mapping(fields, null),
                    listOf(voice), SpeechPackageSupport.HttpAdapter, emptyList(), emptyList())
                val imported = previewHttpSpeechPackage(engine)
                assertNull(code, imported.error)
                val definition = imported.sources.single()
                assertEquals(code == "namiai", definition.supportsStreaming)
                HttpSpeechClient(definition, broker, emptyList(), ExecutionAuthority()) { _, _, _, _ ->
                    error("Dedicated backend executed a script")
                }.use { client ->
                    val error = try { client.synthesize("A real sentence."); error("Unapproved request succeeded") }
                        catch (failure: HttpSpeechException) { failure }
                    assertEquals(code, HttpSpeechError.PermissionDenied, error.error)
                }
            }
        }
    }
}
