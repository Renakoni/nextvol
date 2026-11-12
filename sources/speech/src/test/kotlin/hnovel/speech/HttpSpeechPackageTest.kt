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

    @Test fun withdrawnPackagesCannotImportOrFallBackToGenericHttp() {
        val supported = previewHttpSpeechPackage(packageEngine()).sources.first()
        for (code in listOf("http_lolimi", "http_sougou", "namiai", "filmora")) {
            val engine = packageEngine(fixture("engines.yaml").replace("http_fixture", code),
                fixture("config.yaml").replace("http_fixture", code))
            assertEquals(SpeechPackageSupport.Unsupported, engine.support)
            assertEquals(HttpSpeechPackageError.UnsupportedEngine, previewHttpSpeechPackage(engine).error)
            val metadata = supported.raw.getValue("multiTts").jsonObject
            val raw = JsonObject(supported.raw + ("multiTts" to JsonObject(metadata +
                ("engine" to JsonObject(metadata.getValue("engine").jsonObject + ("code" to JsonPrimitive(code)))))))
            val imported = previewHttpSpeech(raw.toString())
            assertTrue(imported.sources.isEmpty())
            assertEquals(SpeechImportError.InvalidEntry, imported.issues.single().error)
        }
    }

    @Test fun mixedMscPackageRoutesOnlyExplicitOnlineTypesAndKeepsBothParameterScopes() {
        val engine = packageEngine(
            engines = """engines: [{code: msctts, type: inner, param: '{"region":"bj"}'}]""",
            config = """
                msctts:
                - {code: voice, name: Offline, type: 0, param: 'app,region,local'}
                - {code: voice, name: Online, type: 1, param: 'app,region,cloud'}
                - {code: default, name: Default, param: 'app,region,default'}
            """.trimIndent())
        assertEquals(listOf(false, true, false), engine.voices.map(engine::isOnlineVoice))
        val offline = engine.voices.filterNot(engine::isOnlineVoice).map { readOfflineVoiceDefinition(engine, it).configuration }
        assertEquals(listOf(0, 0), offline.map { it.type })
        val converted = previewHttpSpeechPackage(engine)
        assertNull(converted.error)
        val metadata = converted.sources.single().raw.getValue("multiTts").jsonObject
        assertEquals("""{"region":"bj"}""", metadata.getValue("engine").jsonObject.getValue("param").jsonPrimitive.content)
        assertEquals("https://bj.voicecloud.cn/msp.do", converted.sources.single().url)
        assertEquals("app,region,cloud", metadata.getValue("voice").jsonObject.getValue("param").jsonPrimitive.content)
        assertEquals("Online", converted.sources.single().name)
    }

    @Test fun conversionPreservesVoiceDataAndStableIdsWithoutRunningTemplates() {
        val engine = packageEngine(engines = fixture("engines.yaml")
            .replace("{{java.encodeURI(speakText)}}", "{{(function(){throw 'not during preview';})()}}"))
        val converted = previewHttpSpeechPackage(engine)
        assertNull(converted.error)
        val engineOnly = previewHttpSpeechPackage(SpeechPackageEngine(engine.code, engine.name, engine.type, engine.raw,
            emptyList(), engine.support, engine.notices, emptyList()))
        assertNull(engineOnly.error)
        assertTrue(engineOnly.sources.isEmpty())
        assertEquals(engine.code, engineOnly.engine!!.code)
        assertEquals(2, converted.sources.size)
        assertEquals(converted.sources.map { it.id }, previewHttpSpeechPackage(engine).sources.map { it.id })
        assertEquals(2, converted.sources.map { it.id }.distinct().size)
        val source = converted.sources.first()
        val voice = source.raw.getValue("multiTts").jsonObject.getValue("voice").jsonObject
        assertEquals("1.0", voice.getValue("speed").jsonPrimitive.content)
        assertEquals("2.0", voice.getValue("volume").jsonPrimitive.content)
        assertEquals("16000", voice.getValue("sampleRate").jsonPrimitive.content)
        assertEquals(JsonNull, voice.getValue("param"))
        val withDefaults = previewHttpSpeechPackage(packageEngine(config = fixture("config.yaml")
            .replace("sampleRate: 16000", "sampleRate: 0").replace("pitch: 1.0", "pitch: 0.0"))).sources.first()
            .raw.getValue("multiTts").jsonObject.getValue("voice").jsonObject
        assertEquals("16000", withDefaults.getValue("sampleRate").jsonPrimitive.content)
        assertEquals("1.0", withDefaults.getValue("pitch").jsonPrimitive.content)
        assertTrue(source.url.contains("not during preview"))
        assertEquals(source.raw, previewHttpSpeech(source.raw.toString()).sources.single().raw)
    }

    @Test fun convertedGetUsesTheExistingWorkerAndKeepsTextAndSynthesisRateSeparate() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            val parameter = "quoted\";throw Error(1);// & literal"
            val extensions = buildJsonArray { add(buildJsonObject {
                put("code", "style"); put("name", "Style"); put("value", parameter)
                put("candidate", buildJsonObject { put(parameter, "Example") })
            }) }.toString()
            val engine = packageEngine(engines = fixture("engines.yaml").replace("https://speech.example.invalid", base)
                .trimEnd() + "&style={{java.encodeURI(params[0])}}\n",
                config = fixture("config.yaml").replace("param: null", "param: '@style'")
                    .replace("extendUI: null", "extendUI: '$extensions'"))
            val definition = previewHttpSpeechPackage(engine).sources.first()
            val text = "中文 \"quoted\" & + {{throw 'not code'}} @js:literal"
            val audio = "RIFFxxxxWAVE".toByteArray() + ByteArray(128)
            SourceBroker(directory.newFolder("runtime").toPath()).use { owner ->
                fun client(grants: List<NetworkGrant>) = HttpSpeechClient(definition, owner, grants, ExecutionAuthority()) { identity, task, limits, broker ->
                    ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                }
                client(listOf(NetworkGrant(base, true))).use { source ->
                    for ((rate, expectedSpeed) in listOf(null to "1.25", 1.06f to "1.205")) {
                        server.enqueue(MockResponse().setBody(okio.Buffer().write(audio)))
                        assertArrayEquals(audio, source.synthesize(text, rate, volume = 1.06f))
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        assertEquals("GET", request.method)
                        assertEquals("test_voice_1", request.requestUrl!!.queryParameter("voiceId"))
                        assertEquals(text.replace('\'', ' ').replace('"', ' '), request.requestUrl!!.queryParameter("text"))
                        assertEquals(expectedSpeed, request.requestUrl!!.queryParameter("speed"))
                        assertEquals("212", request.requestUrl!!.queryParameter("volume"))
                        assertEquals("mp3", request.requestUrl!!.queryParameter("audioType"))
                        assertEquals("16000", request.requestUrl!!.queryParameter("sampleRate"))
                        assertEquals(parameter, request.requestUrl!!.queryParameter("style"))
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

    @Test fun scriptsAndUnresolvedParametersRemainEditableWithoutRunningDuringImport() {
        val inner = packageEngine(engines = fixture("engines.yaml").replace("type: http", "type: inner"))
        assertEquals(HttpSpeechPackageError.UnsupportedEngine, previewHttpSpeechPackage(inner).error)
        val scripted = packageEngine(engines = fixture("engines.yaml").replace("url: https://", "url: '@js: https://").trimEnd() + "'\n")
        assertNull(previewHttpSpeechPackage(scripted).error)
        val unresolved = packageEngine(config = fixture("config.yaml").replace("param: null", "param: '@credential'"))
        assertNull(previewHttpSpeechPackage(unresolved).error)
        val structuredParameters = packageEngine(config = fixture("config.yaml").replace("param: null", "param: {token: required}"))
        assertEquals(HttpSpeechPackageError.RequiresParameters, previewHttpSpeechPackage(structuredParameters).error)
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
