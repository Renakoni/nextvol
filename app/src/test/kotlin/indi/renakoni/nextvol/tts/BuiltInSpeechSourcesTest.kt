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
        assertEquals(96, initial.size)
        assertTrue(initial.all { it.isConfigured })
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
        assertEquals(97, repository.sources().size)
        try { repository.save(listOf(repository.sources().first())); fail("Built-in IDs must be reserved") }
        catch (_: IllegalArgumentException) { }
        repository.delete(source.id)
        assertEquals(96, repository.sources().size)
    }

    @Test fun refreshedPackageKeepsEditedSpeakerIdentityAndDoesNotOverwriteIndependentCopies() = runBlocking {
        fun imported(name: String, volume: String, note: String, type: Int = 0) = SavedHttpSpeechSource(previewHttpSpeech(buildJsonObject {
            put("name", name); put("url", "https://example.org/voice")
            put("multiTts", buildJsonObject {
                put("engine", buildJsonObject {
                    put("code", "fixture"); put("name", "Fixture"); put("type", "http")
                    put("url", "https://example.org/audio?style={{params[0]}}")
                })
                put("voice", buildJsonObject {
                    put("code", "one"); put("avatar", "one.png"); put("type", type); put("name", name); put("volume", volume); put("note", note); put("param", "original")
                })
            })
        }.toString()).sources.single(), listOf("https://example.org:443"))
        val repository = repository()
        val archive = directory.newFile()
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("fixture/avatar/one.png")); zip.write("avatar".toByteArray()); zip.closeEntry()
        }
        repository.save(listOf(imported("First", "2", "Old note")), archive)
        val first = repository.sources().single { !it.isBuiltIn }
        val image = repository.avatar(first)!!
        assertEquals("avatar", image.readText())
        val edited = editHttpVoice(first, buildJsonObject { put("name", "Mine"); put("volume", "1"); put("param", "edited"); put("type", 1) }, false, false)
        repository.saveVoice(edited)
        val copy = editHttpVoice(edited, buildJsonObject { put("name", "Copy") }, true, true)
        repository.saveVoice(copy)
        repository.save(listOf(imported("Updated", "3", "New note"), imported("Another type", "1", "", type = 2)))
        val voices = repository().sources().filterNot { it.isBuiltIn }
        assertEquals(3, voices.size)
        val refreshed = voices.single { it.definition.id == first.definition.id }
        assertEquals("Mine", refreshed.definition.name)
        assertFalse(refreshed.enabled)
        assertEquals("1", refreshed.speakerData().getValue("volume").jsonPrimitive.content)
        assertEquals(1, refreshed.speakerData().getValue("type").jsonPrimitive.int)
        assertEquals("New note", refreshed.speakerData().getValue("note").jsonPrimitive.content)
        assertTrue(refreshed.definition.url.contains("edited"))
        assertEquals("3", refreshed.speakerDefaults!!.getValue("volume").jsonPrimitive.content)
        assertEquals(copy.definition.raw, voices.single { it.definition.id == copy.definition.id }.definition.raw)
        assertEquals(image, repository.avatar(refreshed))
        repository.delete(first.definition.id)
        assertTrue(image.exists())
        repository.delete(copy.definition.id)
        assertFalse(image.exists())
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
            assertEquals(96, restored.count { it.isBuiltIn })
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
}
