package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.ContextWrapper
import hnovel.execution.*
import hnovel.network.*
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.previewHttpSpeech
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BuiltInSpeechSourcesTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = directory.root
    } }
    private val authority = ExecutionAuthority()
    private val offline by lazy { OfflineSpeechRepository(File(directory.root, "offline")) }
    private fun repository() = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline,
        SpeechEngineRepository(context, StorageCipher.Plain))

    @Test fun onlineRegistrationKeepsSharedEngineStorageAndReimportDoesNotOverwriteOrNotify() = runBlocking {
        val engines = SpeechEngineRepository(context, StorageCipher.Plain)
        val repository = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        val offlineEngine = SpeechEngineConfig("sherpa", "Sherpa")
        val shared = SpeechEngineConfig("msctts", "My MSC", parameters = "{\"region\":\"bj\"}", enabled = false, order = 2)
        val online = SpeechEngineConfig("azure", "Azure")
        engines.register(offlineEngine)
        repository.registerEngine(shared)
        repository.registerEngine(online)
        val changes = engines.changes.value to repository.changes.value

        repository.registerEngine(SpeechEngineConfig("msctts", "Package defaults"))
        assertEquals(changes, engines.changes.value to repository.changes.value)
        assertEquals(setOf(online, shared), repository().engines().toSet())

        val httpStore = Json.parseToJsonElement(File(context.filesDir, "http-speech.enc").readText()).jsonObject
        assertEquals(listOf(online), Json.decodeFromJsonElement<List<SpeechEngineConfig>>(httpStore.getValue("engines")))
        assertEquals(listOf(offlineEngine, shared), Json.decodeFromString<List<SpeechEngineConfig>>(
            File(context.filesDir, "offline-engines.enc").readText()))
    }

    @Test fun engineEditingComparesTheNormalizedKeyAgainstTheLoadedList() = runBlocking {
        val engines = SpeechEngineRepository(context, StorageCipher.Plain)
        val repository = HttpSpeechRepository(context, StorageCipher.Plain, authority, offline, engines)
        val engine = SpeechEngineConfig("http_restored", "Restored", "http", url = "https://example.org/audio")
        repository.saveEngine(engine, creating = true)
        val voice = newHttpVoice(engine)
        repository.saveVoice(voice)
        val deleted = engine.copy(deleted = true)
        repository.saveEngine(deleted)
        val controller = mockk<ReadAloudController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(ReadAloudState(
            SpeechRequest("", "", "Preview", SpeechSettings(httpSource = voice.definition.id)), SpeechPhase.Playing))
        val editing = SpeechEngineEditing(engines, repository, offline, mockk(), controller)
        val draft = engine.copy(code = " restored ")

        // The loaded list differs from storage: comparison must not re-read the deleted record.
        editing.save(draft, creating = true, loadedEngines = listOf(engine))
        assertEquals(listOf(engine), repository.engines())
        verify(exactly = 0) { controller.command(any()) }

        repository.saveEngine(deleted)
        // Match after trim/prefix handling, and include type in the key.
        editing.save(draft, creating = true, loadedEngines = listOf(engine.copy(type = "inner"), deleted))
        assertEquals(listOf(engine), repository.engines())
        verify(exactly = 1) { controller.command(SpeechAction.Stop) }
    }

    @Test fun catalogTicketsRespectEngineEditsAndRetirementWithAndWithoutSavedVoices() = runBlocking {
        val repository = repository()
        val engine = SpeechEngineConfig("azure", "Azure", parameters = "{\"region\":\"eastus\"}")
        repository.saveEngine(engine, creating = true)
        val draftTicket = repository.withCatalogSource(engine) { source, _ ->
            assertTrue(source.origins.isNotEmpty())
            authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        }!!
        val edited = engine.copy(parameters = "{\"region\":\"westus\"}")
        repository.saveEngine(edited)
        assertFalse(authority.accepts(draftTicket))
        assertNull(repository.withCatalogSource(engine) { _, _ -> fail("Stale engine issued a catalog ticket") })
        val saved = newHttpVoice(edited)
        repository.saveVoice(saved)
        val savedTicket = repository.withCatalogSource(edited) { source, _ ->
            assertEquals(saved.definition.id, source.definition.id)
            authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        }!!
        val disabled = edited.copy(enabled = false)
        repository.saveEngine(disabled)
        assertFalse(authority.accepts(savedTicket))
        assertNull(repository.withCatalogSource(edited) { _, _ -> fail("Disabled engine issued a catalog ticket") })
        assertNull(repository.withCatalogSource(disabled) { _, _ -> fail("Current disabled engine issued a catalog ticket") })
        repository.saveEngine(edited)
        repository.delete(saved.definition.id)
        val reopened = repository.withCatalogSource(edited) { source, _ ->
            authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        }!!
        assertEquals(draftTicket.sourceId, reopened.sourceId)
        val deleted = edited.copy(deleted = true)
        repository.saveEngine(deleted)
        assertFalse(authority.accepts(reopened))
        assertNull(repository.withCatalogSource(deleted) { _, _ -> fail("Deleted engine issued a catalog ticket") })
    }

    @Test fun catalogLanguageFilterOnlyChangesIncomingVoicesAndKeepsSavedEditsAndReferences() = runBlocking {
        val repository = repository()
        val engine = SpeechEngineConfig("azure", "Azure", parameters = "{\"region\":\"eastus\"}")
        repository.saveEngine(engine, creating = true)
        fun voice(code: String, locale: String) = editHttpVoice(newHttpVoice(engine), buildJsonObject {
            put("code", code); put("name", code); put("locale", locale)
        }, true, false).copy(isVoiceCopy = false, speakerDefaults = null)
        val japanese = voice("japanese", "ja-JP")
        val chinese = voice("chinese", "zh-CN")
        val english = voice("english", "en-US")
        val french = voice("french", "fr-FR")
        repository.save(listOf(japanese, chinese))
        val selected = repository.sources().single { it.definition.id == chinese.definition.id }
        repository.saveVoice(editHttpVoice(selected, buildJsonObject { put("name", "My voice") }, true, false))
        val copy = editHttpVoice(japanese, buildJsonObject { put("name", "My copy") }, true, true)
        repository.saveVoice(copy)
        val incoming = listOf(chinese, english, french).map { it.definition }
        val filtered = filterSpeechCatalog(engine.code, incoming, true, Locale.CHINA)
        assertEquals(listOf(chinese.definition, english.definition), filtered)
        assertSame(incoming, filterSpeechCatalog("vivo", incoming, true, Locale.CHINA))
        assertEquals(incoming, filterSpeechCatalog(engine.code, incoming, true, Locale("zh")))
        repository.saveCatalog(engine, filtered, emptyList(), null)
        val refreshed = repository().sources().filterNot { it.isBuiltIn }.associateBy { it.definition.id }
        assertEquals(setOf(japanese.definition.id, chinese.definition.id, english.definition.id, copy.definition.id), refreshed.keys)
        assertEquals("My voice", refreshed.getValue(chinese.definition.id).definition.name)
        assertEquals(japanese.definition.raw, refreshed.getValue(japanese.definition.id).definition.raw)
        assertEquals(copy.definition.raw, refreshed.getValue(copy.definition.id).definition.raw)
        repository.saveCatalog(engine, filterSpeechCatalog(engine.code, incoming, false, Locale.CHINA), emptyList(), null)
        assertTrue(repository().sources().any { it.definition.id == french.definition.id })
    }

    @Test fun translatorVoicesSurviveEditingReimportReopenAndEngineRevocation() = runBlocking {
        val engine = SpeechEngineConfig("mstrans", "Translator", "inner")
        val draft = newHttpVoice(engine)
        val voices = (0 until 2).map { index ->
            editHttpVoice(draft, buildJsonObject {
                put("code", "voice-$index"); put("name", "Voice $index"); put("locale", "ja-JP")
                put("param", "ja-JP-NanamiNeural,Default,Default,1.0")
            }, true, true).copy(isVoiceCopy = false)
        }
        val repository = repository()
        repository.saveEngine(engine, creating = true)
        repository.save(voices)
        val first = repository.sources().first { !it.isBuiltIn }
        assertEquals(voices.first().definition.id, first.definition.id)
        repository.saveVoice(editHttpVoice(first, buildJsonObject { put("name", "Edited"); put("locale", "de-DE") }, true, false))
        repository.save(voices)
        val restored = repository().sources().filterNot { it.isBuiltIn }
        assertEquals(2, restored.size)
        val selected = restored.single { it.definition.id == first.definition.id }
        assertEquals("Edited", selected.definition.name)
        assertEquals("de-DE", selected.speakerData()["locale"]!!.jsonPrimitive.content)
        assertTrue(selected.definition.usesMicrosoftTranslator)
        val ticket = authority.issue(selected.definition.id, "http-tts", selected.definition.revision, "speech")
        val renamed = repository.engines().single().copy(name = "Renamed")
        repository.saveEngine(renamed)
        assertTrue(authority.accepts(ticket))
        val updated = renamed.copy(parameters = "{\"authHost\":\"auth.example.org\"}")
        repository.saveEngine(updated)
        assertFalse(authority.accepts(ticket))
        assertEquals("https://auth.example.org/apps/endpoint?api-version=1.0",
            repository().sources().single { it.definition.id == selected.definition.id }.definition.url)
        repository.saveEngine(updated.copy(deleted = true, enabled = false))
        assertTrue(repository().sources().filterNot { it.isBuiltIn }.none { it.available })
        val discovered = "{\"authHost\":\"auth.example.org\",\"userId\":\"7\"}"
        repository.saveDiscoveredEngineParameters(updated.code, updated.parameters, discovered)
        val savedEngine = repository().engines().single()
        assertEquals(updated.copy(deleted = true, enabled = false, parameters = discovered), savedEngine)
        val changes = repository.changes.value
        repository.saveDiscoveredEngineParameters(updated.code, updated.parameters, "{\"userId\":\"stale\"}")
        assertEquals(changes, repository.changes.value)
        assertEquals(savedEngine, repository().engines().single())
    }

    @Test fun freshInstallAndNoOpResetKeepTheCompleteCatalogWithoutWriting() = runBlocking {
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
        assertFalse(File(directory.root, "http-speech.enc").exists())
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

    @Test fun filteredVoiceOrderSurvivesEditsReimportAndReopenWithoutRevokingPlayback() = runBlocking {
        val repository = repository()
        val engine = SpeechEngineConfig("http_order", "Order", "http", url = "https://example.org/audio?voice={{voiceCode}}")
        val original = newHttpVoice(engine).copy(isVoiceCopy = false)
        val second = editHttpVoice(original, buildJsonObject { put("name", "Second"); put("code", "second") }, true, true)
        val third = editHttpVoice(original, buildJsonObject { put("name", "Third"); put("code", "third") }, true, true)
        repository.save(listOf(original, second, third))
        val ticket = repository.withSource(original.definition.id) { source, _ ->
            authority.issue(source.definition.id, "http-tts", source.definition.revision, "speech")
        }!!
        repository.orderVoices(listOf(third.definition.id, original.definition.id))
        val order = listOf(third.definition.id, second.definition.id, original.definition.id)
        assertEquals(order, repository().sources().filterNot { it.isBuiltIn }.map { it.definition.id })
        assertTrue(authority.accepts(ticket))
        repository.enable(setOf(second.definition.id), false)
        repository.save(listOf(original))
        assertEquals(order, repository().sources().filterNot { it.isBuiltIn }.map { it.definition.id })
        val sources = repository.sources()
        val presets = sources.filter { it.group == sources.first().group }.take(2)
        repository.orderVoices(presets.reversed().map { it.definition.id })
        assertEquals(presets.reversed().map { it.definition.id }, repository().sources().take(2).map { it.definition.id })
    }

}
