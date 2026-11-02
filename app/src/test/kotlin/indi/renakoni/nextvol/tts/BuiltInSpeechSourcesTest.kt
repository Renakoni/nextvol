package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.ContextWrapper
import hnovel.execution.*
import hnovel.network.*
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.previewHttpSpeech
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BuiltInSpeechSourcesTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = directory.root
    } }
    private val authority = ExecutionAuthority()
    private fun repository() = HttpSpeechRepository(context, StorageCipher.Plain, authority)

    @Test fun translatorFullCatalogSurvivesEditingReimportReopenAndEngineRevocation() = runBlocking {
        val engine = SpeechEngineConfig("mstrans", "Translator", "inner")
        val draft = newHttpVoice(engine)
        val voices = (0 until 579).map { index ->
            editHttpVoice(draft, buildJsonObject {
                put("code", "voice-$index"); put("name", "Voice $index"); put("locale", "ja-JP")
                put("param", "ja-JP-NanamiNeural,Default,Default,1.0")
            }, true, true).copy(isVoiceCopy = false)
        }
        val repository = repository()
        repository.save(voices)
        val first = repository.sources().first { !it.isBuiltIn }
        repository.saveVoice(editHttpVoice(first, buildJsonObject { put("name", "Edited"); put("locale", "de-DE") }, true, false))
        repository.save(voices)
        val restored = repository().sources().filterNot { it.isBuiltIn }
        assertEquals(579, restored.size)
        val selected = restored.single { it.definition.id == first.definition.id }
        assertEquals("Edited", selected.definition.name)
        assertEquals("de-DE", selected.speakerData()["locale"]!!.jsonPrimitive.content)
        assertTrue(selected.definition.usesMicrosoftTranslator)
        val ticket = authority.issue(selected.definition.id, "http-tts", selected.definition.revision, "speech")
        val updated = repository.engines().single().copy(parameters = "{\"authHost\":\"auth.example.org\"}")
        repository.saveEngine(updated)
        assertFalse(authority.accepts(ticket))
        assertEquals("https://auth.example.org/apps/endpoint?api-version=1.0",
            repository().sources().single { it.definition.id == selected.definition.id }.definition.url)
        repository.saveEngine(updated.copy(deleted = true, enabled = false))
        assertTrue(repository().sources().filterNot { it.isBuiltIn }.none { it.available })
    }

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
        assertEquals("edited", refreshed.speakerData().getValue("param").jsonPrimitive.content)
        assertEquals("3", refreshed.speakerDefaults!!.getValue("volume").jsonPrimitive.content)
        assertEquals(copy.definition.raw, voices.single { it.definition.id == copy.definition.id }.definition.raw)
        assertEquals(image, repository.avatar(refreshed))
        repository.delete(first.definition.id)
        assertTrue(image.exists())
        repository.delete(copy.definition.id)
        assertFalse(image.exists())
    }

    @Test fun importedEngineEditUpdatesAllItsVoicesAndKeepsTheirIndependentSettings() = runBlocking {
        val base = "https://example.org:443"
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
        val url = "$base/updated?voice={{voiceCode}}&text={{speakText}}"
        val header = """{"X-Engine":"edited"}"""
        repository.saveEngine(repository.engines().single { it.code == "shared" }.copy(name = "Edited engine", url = url, header = header))
        val restored = repository().sources()
        assertFalse(authority.accepts(oldTicket))
        assertEquals(originals[2].definition.raw, restored.single { it.definition.id == "c" }.definition.raw)
        for (original in originals.take(2)) {
            val source = restored.single { it.definition.id == original.definition.id }
            assertEquals("Edited engine", source.category)
            assertEquals(original.definition.name, source.definition.name)
            assertEquals(original.origins, source.origins)
            assertEquals(original.speakerData(), source.speakerData())
            assertEquals(url, source.importedEngine!!.getValue("url").jsonPrimitive.content)
            assertTrue(source.definition.url.contains("/updated"))
            assertEquals(header, source.definition.text("header"))
        }
    }
    @Test fun engineCanExistWithoutSpeakersAndEditsPreserveAuthorizationUntilExecutionChanges() = runBlocking {
        val repository = repository()
        val engine = SpeechEngineConfig("http_shared", "Shared", "http", url = "https://example.org/audio?voice={{voiceCode}}")
        repository.saveEngine(engine, creating = true)
        assertEquals(listOf(engine), repository().engines())
        assertTrue(repository.sources().all { it.isBuiltIn })
        val first = newHttpVoice(engine)
        repository.saveVoice(first)
        repository.saveVoice(editHttpVoice(first, buildJsonObject { put("name", "Second"); put("code", "second") }, true, true))
        val ticket = repository.withSource(first.definition.id) { source, _ ->
            authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        }!!
        val renamed = engine.copy(name = "Renamed", note = "Notes", order = 3)
        repository.saveEngine(renamed)
        assertTrue(authority.accepts(ticket))
        assertTrue(repository.sources().filterNot { it.isBuiltIn }.all { it.category == "Renamed" })
        repository.saveEngine(renamed.copy(enabled = false))
        assertFalse(authority.accepts(ticket))
        assertTrue(repository().sources().filterNot { it.isBuiltIn }.all { it.enabled && !it.available })
        repository.saveEngine(renamed.copy(deleted = true, enabled = false))
        assertEquals(2, repository().sources().count { it.engineDeleted })
        repository.saveEngine(renamed)
        assertTrue(repository().sources().filterNot { it.isBuiltIn }.all { it.available })
        repository.delete(repository.sources().filterNot { it.isBuiltIn }.map { it.definition.id }.toSet())
        assertEquals(listOf(renamed), repository().engines())
    }

    @Test fun reimportRetainsEngineEditsAndRestoresDeletedEngines() = runBlocking {
        val engine = SpeechEngineConfig("http_package", "Package", "http", url = "https://example.org/old")
        val voice = newHttpVoice(engine).copy(isVoiceCopy = false)
        val repository = repository()
        repository.save(listOf(voice))
        val edited = repository.engines().single().copy(name = "Mine", url = "https://example.org/edited", header = "{\"X-Test\":\"custom\"}")
        repository.saveEngine(edited)
        repository.save(listOf(voice))
        assertEquals(edited, repository().engines().single())
        val updated = repository.sources().single { !it.isBuiltIn }
        assertEquals(voice.definition.id, updated.definition.id)
        assertTrue(updated.definition.url.contains("/edited"))
        repository.saveEngine(edited.copy(header = ""))
        assertFalse(repository.sources().single { !it.isBuiltIn }.definition.raw.containsKey("header"))
        repository.saveEngine(edited.copy(deleted = true, enabled = false))
        repository.save(listOf(voice))
        assertTrue(repository().sources().single { !it.isBuiltIn }.available)
    }

}
