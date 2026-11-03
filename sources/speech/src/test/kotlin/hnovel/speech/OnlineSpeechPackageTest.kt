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

    @Test fun dedicatedPackagesPreserveSpeakerIdentityAndUseBrokerWithoutExecutingScripts() = runBlocking {
        fun value(text: String) = SpeechPackageValue.Scalar(text, null, false)
        for (code in dedicatedOnlineSpeechEngines) {
            val fields = mapOf("code" to value(code), "name" to value(code)) +
                if (code in setOf("xfpeiyin", "peiyinya")) emptyMap() else mapOf("type" to value("inner"))
            val voice = SpeechPackageVoice("voice", "Speaker", SpeechPackageValue.Mapping(mapOf(
                "code" to value("voice"), "name" to value("Speaker"), "type" to value("1"),
                "locale" to value("ja-JP"), "speed" to value("1.5"), "volume" to value("3.0"),
                "param" to value("123,@emotion,50"), "extendUI" to value("[{\"code\":\"emotion\",\"name\":\"Emotion\",\"value\":\"neutral\"}]"),
            ), null))
            val engine = SpeechPackageEngine(code, code, fields["type"]?.value, SpeechPackageValue.Mapping(fields, null),
                listOf(voice), SpeechPackageSupport.HttpAdapter, emptyList(), emptyList())
            val imported = previewHttpSpeechPackage(engine)
            assertNull(code, imported.error)
            val definition = imported.sources.single()
            val reloaded = previewHttpSpeech(definition.raw.toString()).sources.single()
            assertEquals(definition.id, reloaded.id)
            assertEquals(definition.raw, reloaded.raw)
            assertEquals("ja-JP", reloaded.multiTts!!.getValue("voice").jsonObject.string("locale"))
            assertEquals(code == "namiai", definition.supportsStreaming)
            SourceBroker(directory.newFolder(code).toPath(), okhttp3.Dns { error("Unapproved request resolved DNS") }).use { broker ->
                HttpSpeechClient(reloaded, broker, emptyList(), ExecutionAuthority()) { _, _, _, _ ->
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
