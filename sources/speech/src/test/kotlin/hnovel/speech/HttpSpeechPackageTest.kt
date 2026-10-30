package hnovel.speech

import hnovel.execution.*
import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HttpSpeechPackageTest {
    @get:Rule val directory = TemporaryFolder()
    private fun fixture(name: String) = javaClass.getResource("/multitts-http/$name")!!.readText()

    private fun packageEngine(
        engines: String = fixture("engines.yaml"),
        config: String = fixture("config.yaml"),
    ): SpeechPackageEngine {
        val file = directory.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in listOf("engines.yaml" to engines, "config.yaml" to config)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
        return previewSpeechPackage(file).also { assertNull(it.error) }.engines.single()
    }

    @Test fun conversionPreservesVoiceDataAndStableIdsWithoutRunningTemplates() {
        val engine = packageEngine(engines = fixture("engines.yaml")
            .replace("{{java.encodeURI(speakText)}}", "{{(function(){throw 'not during preview';})()}}"))
        val converted = previewHttpSpeechPackage(engine)
        assertNull(converted.error)
        assertEquals(2, converted.sources.size)
        assertTrue(converted.hasLocalAudioSettings)
        assertEquals(converted.sources.map { it.id }, previewHttpSpeechPackage(engine).sources.map { it.id })
        assertEquals(2, converted.sources.map { it.id }.distinct().size)
        val source = converted.sources.first()
        val voice = source.raw.getValue("multiTts").jsonObject.getValue("voice").jsonObject
        assertEquals("1.3", voice.getValue("speed").jsonPrimitive.content)
        assertEquals("2.0", voice.getValue("volume").jsonPrimitive.content)
        assertEquals("16000", voice.getValue("sampleRate").jsonPrimitive.content)
        assertEquals(JsonNull, voice.getValue("param"))
        assertTrue(source.url.contains("not during preview"))
        assertEquals(source.raw, previewHttpSpeech(source.raw.toString()).sources.single().raw)
    }

    @Test fun convertedGetUsesTheExistingWorkerAndKeepsTextAndSynthesisRateSeparate() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            val engine = packageEngine(engines = fixture("engines.yaml").replace("https://speech.example.invalid", base))
            val definition = previewHttpSpeechPackage(engine).sources.first()
            val text = "中文 \"quoted\" & + {{throw 'not code'}} @js:literal"
            val audio = "RIFFxxxxWAVE".toByteArray() + ByteArray(128)
            SourceBroker(directory.newFolder("runtime").toPath()).use { owner ->
                fun client(grants: List<NetworkGrant>) = HttpSpeechClient(definition, owner, grants, ExecutionAuthority()) { identity, task, limits, broker ->
                    ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                }
                client(listOf(NetworkGrant(base, true))).use { source ->
                    for ((rate, expectedSpeed) in listOf(null to "1.25", 2f to "0.5")) {
                        server.enqueue(MockResponse().setBody(okio.Buffer().write(audio)))
                        assertArrayEquals(audio, source.synthesize(text, rate))
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        assertEquals("GET", request.method)
                        assertEquals("test_voice_1", request.requestUrl!!.queryParameter("voiceId"))
                        assertEquals(text, request.requestUrl!!.queryParameter("text"))
                        assertEquals(expectedSpeed, request.requestUrl!!.queryParameter("speed"))
                        assertEquals("200", request.requestUrl!!.queryParameter("volume"))
                        assertEquals("mp3", request.requestUrl!!.queryParameter("audioType"))
                        assertEquals("16000", request.requestUrl!!.queryParameter("sampleRate"))
                    }
                }
                client(emptyList()).use { source ->
                    val denied = try { source.synthesize(text); error("Expected permission failure") }
                        catch (failure: HttpSpeechException) { failure }
                    assertEquals(HttpSpeechError.PermissionDenied, denied.error)
                    assertEquals(2, server.requestCount)
                }
            }
        }
    }

    @Test fun unsupportedProtocolsAndParameterSetupAreReportedRatherThanDiscarded() {
        val inner = packageEngine(engines = fixture("engines.yaml").replace("type: http", "type: inner"))
        assertEquals(HttpSpeechPackageError.UnsupportedEngine, previewHttpSpeechPackage(inner).error)
        val scripted = packageEngine(engines = fixture("engines.yaml").replace("url: https://", "url: '@js: https://").trimEnd() + "'\n")
        assertEquals(HttpSpeechPackageError.UnsupportedRequest, previewHttpSpeechPackage(scripted).error)
        for (param in listOf("'@credential'", "{token: required}")) {
            val parameterized = packageEngine(config = fixture("config.yaml").replace("param: null", "param: $param"))
            val result = previewHttpSpeechPackage(parameterized)
            assertEquals(HttpSpeechPackageError.RequiresParameters, result.error)
            assertTrue(result.sources.isEmpty())
        }
        val structuredHeader = packageEngine(engines = fixture("engines.yaml")
            .replace("header: ''", "header: {Authorization: required}"))
        assertEquals(HttpSpeechPackageError.UnsupportedRequest, previewHttpSpeechPackage(structuredHeader).error)
    }

    @Test fun metadataTooDeepForHttpJsonRemainsPreviewableWithoutCrashingImport() {
        val nested = "[".repeat(20_000) + "0" + "]".repeat(20_000)
        val engine = packageEngine(config = fixture("config.yaml").replaceFirst("avatar: null", "avatar: $nested"))
        val result = previewHttpSpeechPackage(engine)
        assertEquals(HttpSpeechPackageError.InvalidConfiguration, result.error)
        assertTrue(result.sources.isEmpty())
    }
}
