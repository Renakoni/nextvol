package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.ContextWrapper
import hnovel.execution.*
import hnovel.network.*
import hnovel.rhino.HostBridge
import hnovel.speech.HttpSpeechClient
import hnovel.speech.previewHttpSpeech
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BuiltInSpeechSourcesTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = directory.root
    } }
    private val authority = ExecutionAuthority()
    private fun repository() = HttpSpeechRepository(context, StorageCipher.Plain, authority)

    @Test fun freshInstallReopenAndResetKeepTheCompleteCatalogWithoutImporting() = runBlocking {
        val initial = repository().sources()
        assertEquals(99, initial.size)
        assertEquals(96, initial.count { it.isConfigured })
        assertEquals(26, initial.count { it.definition.usesMicrosoftTranslator })
        assertTrue(initial.all { it.isBuiltIn && it.origins.isNotEmpty() && it.origins.all { origin -> sourceOrigin(origin) == origin } })
        assertEquals(initial.size, initial.map { it.definition.id }.toSet().size)
        assertFalse(File(directory.root, "http-speech.enc").exists())
        val repository = repository()
        val voice = initial.first()
        repository.delete(voice.definition.id)
        assertEquals(initial.map { it.definition.raw to it.origins }, repository().sources().map { it.definition.raw to it.origins })
        assertTrue(File(directory.root, "http-speech.enc").delete())
        assertEquals(initial.map { it.definition.raw to it.origins }, repository().sources().map { it.definition.raw to it.origins })
    }

    @Test fun legacyImportsRemainSeparateAndCannotReplaceBuiltIns() = runBlocking {
        val repository = repository()
        val source = previewHttpSpeech("""{"id":24,"name":"My voice","url":"https://example.org"}""").sources.single()
        val imported = SavedHttpSpeechSource(source, listOf("https://example.org:443"))
        repository.save(listOf(imported))
        val restored = repository().sources().single { !it.isBuiltIn }
        assertEquals(imported.definition.raw, restored.definition.raw)
        assertEquals(imported.origins, restored.origins)
        assertEquals(100, repository.sources().size)
        try { repository.save(listOf(repository.sources().first())); fail("Built-in IDs must be reserved") }
        catch (_: IllegalArgumentException) { }
        repository.delete(source.id)
        assertEquals(99, repository.sources().size)
    }

    @Test fun credentialsApplyToTheServiceAndChangesRevokeSessionsAndCachedTokens() = runBlocking {
        val repository = repository()
        val source = repository.sources().first { it.group == "起点" }
        assertEquals(SpeechError.HttpLogin, assertThrows(SpeechException::class.java) { source.playbackDefinition() }.error)
        val token = "synthetic'\"&token"
        repository.configure(source.definition.id, mapOf("Token" to token))
        assertTrue(repository().sources().filter { it.group == source.group }.all { it.isConfigured && it.credentials["Token"] == token })
        assertFalse(repository().sources().joinToString().contains(token))
        val ticket = repository.withSource(source.definition.id) { saved, runtime ->
            runtime.mkdirs(); File(runtime, "token").writeText("cached")
            authority.issue(saved.definition.id, "http-tts", saved.playbackDefinition().revision, "speech")
        }!!
        repository.configure(source.definition.id, emptyMap())
        assertFalse(authority.accepts(ticket))
        repository.withSource(source.definition.id) { _, runtime -> assertFalse(runtime.exists()) }
        assertTrue(repository().sources().filter { it.group == source.group }.all { !it.isConfigured })
        assertEquals(99, repository.sources().size)
    }

    @Test fun packageVoicesKeepTheirCategoryAndParametersAfterSaveReopenAndRepeatImport() = runBlocking {
        val definition = previewHttpSpeech("""{"name":"Package voice","url":"https://speech.example.invalid/audio",
            "multiTts":{"engine":{"code":"http_fixture","name":"思必驰"},
            "voice":{"code":"test_voice","speed":"1.3","volume":"2.0","pitch":"1.0"}}}""").sources.single()
        val imported = SavedHttpSpeechSource(definition, listOf("https://speech.example.invalid:443"))
        val repository = repository()
        repository.save(listOf(imported))
        repository.save(listOf(imported))
        val restored = repository().sources().single { !it.isBuiltIn }
        assertEquals("思必驰", restored.category)
        assertEquals(imported.definition.raw, restored.playbackDefinition().raw)
        assertEquals(imported.origins, restored.origins)
    }

    @Test fun partialOrUnexpectedKeysDoNotReplaceSavedCredentials() = runBlocking {
        val repository = repository()
        val source = repository.sources().single { it.group == "阿里云" }
        val valid = mapOf("AppKey" to "test-app", "AccessKeyId" to "test-id", "AccessKeySecret" to "test-secret")
        repository.configure(source.definition.id, valid)
        for (invalid in listOf(mapOf("AppKey" to "partial"), valid + ("unexpected" to "value"), valid + ("AccessKeySecret" to "bad\nvalue"))) {
            try { repository.configure(source.definition.id, invalid); fail("Invalid credentials must fail before writing") }
            catch (_: IllegalArgumentException) { }
            assertEquals(valid, repository().sources().single { it.definition.id == source.definition.id }.credentials)
        }
    }

    @Test fun importedEngineEditUpdatesAllItsVoicesAndKeepsTheirIndependentSettings() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            fun voice(code: String, engine: String = "shared") = SavedHttpSpeechSource(previewHttpSpeech(buildJsonObject {
                put("id", code); put("name", "My $code"); put("url", "$base/old")
                put("multiTts", buildJsonObject {
                    put("engine", buildJsonObject {
                        put("code", engine); put("name", "Same display name"); put("type", "http"); put("url", "$base/old")
                    })
                    put("voice", buildJsonObject { put("code", code); put("name", "Package $code"); put("speed", "1.3") })
                })
            }.toString()).sources.single(), listOf(base))
            val originals = listOf(voice("a"), voice("b"), voice("c", "other"))
            val repository = repository()
            repository.save(originals)
            val oldTicket = repository.withSource("b") { source, _ ->
                authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
            }!!
            repository.editImportedEngine("shared", "Edited engine", "$base/updated?voice={{voiceCode}}&text={{speakText}}",
                """{"X-Engine":"edited"}""")
            val restored = repository().sources()
            assertFalse(authority.accepts(oldTicket))
            assertEquals(originals[2].definition.raw, restored.single { it.definition.id == "c" }.definition.raw)
            assertEquals(99, restored.count { it.isBuiltIn })
            for (original in originals.take(2)) {
                val source = restored.single { it.definition.id == original.definition.id }
                assertEquals("Edited engine", source.category)
                assertEquals(original.definition.name, source.definition.name)
                assertEquals(original.origins, source.origins)
                assertEquals(original.definition.raw["multiTts"]!!.jsonObject["voice"], source.definition.raw["multiTts"]!!.jsonObject["voice"])
                server.enqueue(MockResponse().setBody(okio.Buffer().write("RIFFxxxxWAVE".toByteArray() + ByteArray(100))))
                SourceBroker(directory.newFolder("engine-${source.definition.id}").toPath()).use { owner ->
                    HttpSpeechClient(source.playbackDefinition(), owner, listOf(NetworkGrant(base, true)), authority) { identity, task, limits, broker ->
                        ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
                            HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                    }.use { client -> client.synthesize("正文") }
                }
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("/updated", request.requestUrl!!.encodedPath)
                assertEquals(source.definition.id, request.requestUrl!!.queryParameter("voice"))
                assertEquals("正文", request.requestUrl!!.queryParameter("text"))
                assertEquals("edited", request.getHeader("X-Engine"))
            }
        }
    }

    @Test fun proxyCredentialsAndTextAreDataRatherThanScriptOrQueryParameters() = runBlocking {
        val original = repository().sources().first { it.group == "起点" }
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            val raw = JsonObject(original.definition.raw + ("url" to JsonPrimitive(original.definition.url.replace("https://shenmobook.top", base))))
            val token = "'\";throw new Error(1);//&v=unexpected"
            val text = "中文 {{40+2}} &t=other"
            val configured = original.copy(definition = previewHttpSpeech(raw.toString()).sources.single(), credentials = mapOf("Token" to token))
            val audio = "RIFFxxxxWAVE".toByteArray() + ByteArray(100)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(audio)))
            SourceBroker(directory.newFolder("proxy").toPath()).use { owner ->
                HttpSpeechClient(configured.playbackDefinition(), owner, listOf(NetworkGrant(base, true)), authority) { identity, task, limits, broker ->
                    ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                }.use { client ->
                    assertArrayEquals(audio, client.synthesize(text))
                    val request = server.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!
                    assertEquals(token, request.queryParameter("token"))
                    assertEquals(text, request.queryParameter("t"))
                    assertEquals(listOf("6001"), request.queryParameterValues("v"))
                }
            }
        }
    }

    @Test fun aliyunUsesSignedTokenExchangeCachesItAndPreservesLiteralText() = runBlocking {
        val original = repository().sources().single { it.group == "阿里云" }
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().removeSuffix("/")
            val raw = JsonObject(original.definition.raw + ("url" to JsonPrimitive(original.definition.url
                .replace("https://nls-meta.cn-shanghai.aliyuncs.com", base)
                .replace("https://nls-gateway.cn-shanghai.aliyuncs.com", base))))
            val configured = original.copy(definition = previewHttpSpeech(raw.toString()).sources.single(),
                credentials = mapOf("AppKey" to "test-app", "AccessKeyId" to "test-id", "AccessKeySecret" to "test-secret"))
            val audio = "RIFFxxxxWAVE".toByteArray() + ByteArray(100)
            server.enqueue(MockResponse().setBody("""{"Token":{"Id":"synthetic-token","ExpireTime":4102444800}}"""))
            repeat(2) { server.enqueue(MockResponse().setBody(okio.Buffer().write(audio))) }
            SourceBroker(directory.newFolder("runtime").toPath()).use { owner ->
                HttpSpeechClient(configured.playbackDefinition(), owner, listOf(NetworkGrant(base, true)), authority) { identity, task, limits, broker ->
                    ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray(Charsets.UTF_8))
                }.use { client ->
                    val text = "中文 \" & {{40+2}} @js:literal"
                    assertArrayEquals(audio, client.synthesize(text, 1f))
                    val authentication = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("CreateToken", authentication.requestUrl!!.queryParameter("Action"))
                    assertFalse(authentication.path!!.contains("test-secret"))
                    assertFalse(authentication.requestUrl!!.queryParameter("Signature").isNullOrBlank())
                    val first = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("synthetic-token", first.getHeader("X-NLS-Token"))
                    val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
                    assertEquals(text, body.getValue("text").jsonPrimitive.content)
                    assertEquals(0, body.getValue("speech_rate").jsonPrimitive.int)
                    assertArrayEquals(audio, client.synthesize(text, 2f))
                    val second = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("/stream/v1/tts", second.path)
                    assertEquals(500, Json.parseToJsonElement(second.body.readUtf8()).jsonObject.getValue("speech_rate").jsonPrimitive.int)
                    assertEquals(3, server.requestCount)
                }
            }
        }
    }
}
