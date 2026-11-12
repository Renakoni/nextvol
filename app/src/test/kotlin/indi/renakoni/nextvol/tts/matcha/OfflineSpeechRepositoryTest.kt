package indi.renakoni.nextvol.tts.matcha

import android.app.Application
import hnovel.speech.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class OfflineSpeechRepositoryTest {
    @get:Rule val files = TemporaryFolder()

    private val resources = listOf("model-steps-3.onnx", "hifigan_v2.onnx", "tokens.txt", "lexicon.txt", "dict/jieba.dict.utf8")

    private fun archive(token: String = "tokens", extra: String? = null, speed: Float = 1f): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "engines.yaml" to "engines: [{code: sherpa, type: inner}]",
                "sherpa/avatar/voice.png" to "image fixture",
                "config.yaml" to "sherpa: [{code: voice, name: Baker, locale: zh-CN, avatar: voice.png, sampleRate: 24000, speed: $speed, param: '{\"modelDir\":\"models/chinese\"}'}]",
            ) + resources.associate { "sherpa/models/chinese/$it" to if (it == "tokens.txt") token else "fixture" } +
                (extra?.let { mapOf(it to "must not be extracted") } ?: emptyMap())
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }

    @Test fun editsKeepIdentityAndOverridesWhileCopiesAndLeasesShareResources() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val input = archive(speed = 1.3f)
        val original = repository.install(input, plan(input))
        assertEquals(original, OfflineSpeechRepository(root).voices().single())
        assertEquals(24000, original.configuration!!.sampleRate)
        assertEquals(1f, original.configuration.speed)
        assertEquals("image fixture", repository.avatar(original)!!.readText())
        repository.recordRuntimeInfo(original.reference, OfflineVoiceRuntimeInfo(original.configuration, 3, 22050))
        val discovered = repository.voices().single()
        assertEquals(22050, discovered.configuration!!.sampleRate)
        assertEquals(listOf("0", "1", "2"), speechExtensionControls(discovered.configuration.extendUI).single().candidates.keys.toList())
        val refreshed = repository.install(input, plan(input))
        val renamed = repository.save(refreshed.copy(name = "My voice"))
        assertEquals(refreshed.reference.id, renamed.reference.id)
        val edited = repository.save(renamed.copy(configuration = renamed.configuration!!.copy(speed = 1.2f)))
        assertEquals(1.2f, edited.configuration!!.speed)
        assertEquals(original.reference.id, edited.reference.id)
        assertNotEquals(original.reference.revision, edited.reference.revision)
        assertEquals(refreshed.resourceReference, edited.resourceReference)
        val copy = repository.save(edited.copy(name = "Copy"), copy = true)
        val lease = repository.acquire(copy.reference)
        repository.enable(setOf(copy.reference), false)
        val disabled = repository.voices().single { it.reference.id == copy.reference.id }
        assertThrows(IllegalStateException::class.java) { repository.acquire(disabled.reference) }
        val replacement = archive("after!", speed = 1.5f)
        // Same-sized resource changes are accepted without a model digest comparison.
        val imported = repository.install(replacement, plan(archive("tokens", speed = 1.5f)))
        assertEquals("My voice", imported.name)
        assertEquals(1f, imported.configuration!!.speed)
        assertEquals(1f, imported.configuration.sherpa!!.speed)
        assertEquals(1f, imported.defaults!!.configuration.speed)
        assertEquals(original.reference.id, imported.reference.id)
        assertEquals("tokens", File(lease.directory, "tokens.txt").readText())
        repository.acquire(imported.reference).use { assertEquals("after!", File(it.directory, "tokens.txt").readText()) }
        val reopened = OfflineSpeechRepository(root).voices()
        assertEquals(2, reopened.size)
        assertTrue(reopened.all { repository.avatar(it)?.readText() == "image fixture" })
        assertTrue(lease.directory.exists())
        repository.delete(setOf(disabled.reference, imported.reference))
        assertTrue(repository.voices().isEmpty())
        assertThrows(NoSuchElementException::class.java) { repository.acquire(imported.reference) }
        assertTrue(lease.directory.exists())
        lease.close()
        assertFalse(lease.directory.exists())
        val reimported = repository.install(replacement, plan(replacement))
        assertNotEquals(imported.reference.revision, reimported.reference.revision)
        repository.acquire(reimported.reference).use { assertTrue(it.directory.isDirectory) }
    }

    @Test fun configurationOnlyImportResetsSpeedWithoutReplacingModelsOrOtherUserOverrides() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val archive = archive(speed = 1.3f)
        val installed = repository.install(archive, plan(archive))
        val edited = repository.save(installed.copy(name = "My voice",
            configuration = installed.configuration!!.copy(speed = 1.1f)))
        val yaml = files.newFile("config.yaml").apply { writeText("""
            sherpa:
            - code: voice
              name: Updated package name
              speed: 1.5
              sampleRate: 22050
              note: Imported note
              param: '{"modelDir":"models/chinese","speakerId":2}'
            - {code: voice, name: Copy, type: 1, param: '{"modelDir":"models/chinese","speakerId":1}'}
        """.trimIndent()) }
        val preview = previewSpeechPackage(yaml)
        val engine = preview.engines.single()
        val voice = engine.voices.first()
        assertEquals(MatchaPackageError.Ambiguous, previewOfflineVoice(preview, engine, voice).error)
        val draft = repository.previewConfiguration(engine, voice)!!
        assertEquals(listOf(edited), repository.voices())
        val lease = repository.acquire(edited.reference)
        val imported = repository.importConfiguration(draft)
        assertEquals(edited.reference.id, imported.reference.id)
        assertNotEquals(edited.reference.revision, imported.reference.revision)
        assertEquals(edited.resourceReference, imported.resourceReference)
        assertEquals(edited.sizes, imported.sizes)
        assertEquals("My voice", imported.name)
        assertEquals(1f, imported.configuration!!.speed)
        assertEquals(2, imported.configuration.sherpa!!.speakerId)
        assertEquals(1f, imported.defaults!!.configuration.speed)
        assertEquals("Imported note", imported.metadata.note)
        assertEquals("tokens", File(lease.directory, "tokens.txt").readText())
        assertEquals(imported, OfflineSpeechRepository(root).voices().single())
        lease.close()
        yaml.writeText("sherpa: [{code: voice, type: 1, param: '{\"modelDir\":\"models/chinese\"}'}]")
        val otherType = previewSpeechPackage(yaml).engines.single()
        assertNull(repository.previewConfiguration(otherType, otherType.voices.single()))
        yaml.writeText("sherpa: [{code: voice, param: '{\"modelDir\":\"other-model\"}'}]")
        val otherModel = previewSpeechPackage(yaml).engines.single()
        try { repository.previewConfiguration(otherModel, otherModel.voices.single()); fail("Expected a different model directory") }
        catch (failure: IllegalArgumentException) { assertEquals("modelDir", failure.message) }
        assertEquals(listOf(imported), repository.voices())
    }

    @Test fun audioConfigurationCannotReferenceAFileOutsideTheInstalledResources() = runBlocking {
        val repository = OfflineSpeechRepository(files.newFolder())
        val input = files.newFile()
        ZipOutputStream(input.outputStream()).use { zip ->
            mapOf("config.yaml" to "audio: [{code: sound, name: Sound, param: known.wav}]",
                "audio/known.wav" to "audio").forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val preview = previewSpeechPackage(input)
        val engine = preview.engines.single()
        val installed = repository.install(input, previewOfflineVoice(preview, engine, engine.voices.single()).plan!!)
        val yaml = files.newFile("audio-config.yaml").apply { writeText("audio: [{code: sound, name: Sound, param: missing.wav}]") }
        val changed = previewSpeechPackage(yaml).engines.single()
        try { repository.previewConfiguration(changed, changed.voices.single()); fail("Expected a missing audio file") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(installed), repository.voices())
    }

    @Test fun newAndEditedSpeakersMatchResolvedResourcesWithoutCopyingOtherSpeakerSettings() = runBlocking {
        val repository = OfflineSpeechRepository(files.newFolder())
        fun input(code: String) = files.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                mapOf("config.yaml" to "audio: [{code: $code, name: $code, speed: 1.7, param: $code.wav}]",
                    "audio/$code.wav" to code).forEach { (path, value) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
                }
            }
        }
        val firstInput = input("first")
        val first = repository.install(firstInput, plan(firstInput))
        val secondInput = input("second")
        val second = repository.install(secondInput, plan(secondInput))
        val draft = InstalledOfflineVoice(OfflineVoiceRef("new", "revision"), "My speaker", emptyMap(),
            configuration = OfflineVoiceConfig("audio", "custom", rawParameters = "@file",
                extendUI = """[{"code":"file","value":"second.wav"}]""", speed = 1.2f))
        val created = repository.create(draft)
        assertEquals(second.resourceReference, created.resourceReference)
        assertEquals("My speaker", created.name)
        assertEquals(1.2f, created.configuration!!.speed)
        val edited = repository.save(created.copy(configuration = created.configuration.copy(rawParameters = "first.wav", extendUI = "")))
        assertEquals(first.resourceReference, edited.resourceReference)
        assertEquals("first.wav", edited.configuration!!.parameters)
        assertEquals(1.2f, edited.configuration.speed)
    }

    @Test fun sherpaSpeakerWithoutModelsCanBeEditedAndCopiedAfterCreation() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val configuration = OfflineVoiceConfig("sherpa", "custom", rawParameters = """{"modelDir":"missing"}""")
        val created = repository.create(InstalledOfflineVoice(OfflineVoiceRef("new", "revision"), "My speaker",
            emptyMap(), configuration = configuration))
        val edited = OfflineSpeechRepository(root).save(created.copy(name = "Renamed", metadata = OfflineVoiceMetadata(note = "Note")))
        assertEquals(created.reference, edited.reference)
        assertEquals("Renamed", edited.name)
        assertEquals("Note", edited.metadata.note)
        assertEquals(configuration, edited.configuration)
        assertTrue(edited.sizes.isEmpty())
        val copied = repository.save(edited.copy(name = "Copy"), copy = true)
        assertNotEquals(edited.reference.id, copied.reference.id)
        assertEquals(configuration, copied.configuration)
        assertEquals(listOf(edited, copied), OfflineSpeechRepository(root).voices())
    }

    @Test fun googleDirectoryRefreshAddsDeclaredSpeakersWithoutReplacingEditsOrCopyingModels() = runBlocking {
        val input = files.newFile()
        ZipOutputStream(input.outputStream()).use { zip ->
            mapOf("engines.yaml" to "engines: [{code: google, type: inner}]",
                "config.yaml" to "google: [{code: cmn-cn-x-a, name: First, locale: zh-CN}]",
                "google/cmn-cn/multi/pipeline.pb" to "model",
                "google/cmn-cn/multi/backend_pipeline.textproto" to
                    """speakers { speaker: "a" gender: "female" } speakers { speaker: "b" gender: "male" }"""
            ).forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        val repository = OfflineSpeechRepository(files.newFolder())
        val original = repository.install(input, plan(input))
        repository.save(original.copy(name = "My name"))
        repository.refreshCatalog()
        repository.refreshCatalog()
        val voices = repository.voices()
        assertEquals(2, voices.size)
        assertEquals("My name", voices.single { it.reference.id == original.reference.id }.name)
        val discovered = voices.single { it.reference.id != original.reference.id }
        assertEquals("cmn-cn-x-b", discovered.configuration!!.code)
        assertEquals("zh-CN", discovered.configuration.locale)
        assertEquals(1, discovered.configuration.gender)
        assertEquals(original.resourceReference, discovered.resourceReference)
    }
    private fun plan(file: File): OfflinePackagePlan {
        val preview = previewSpeechPackage(file)
        return previewOfflineVoice(preview, preview.engines.single(), preview.engines.single().voices.single()).plan!!
    }

    @Test fun filteredOrderKeepsHiddenSlotsAndSurvivesReimportWhilePartialDeletionRetainsSharedResources() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val input = archive()
        val first = repository.install(input, plan(input))
        val hidden = repository.save(first.copy(name = "Hidden"), copy = true)
        val last = repository.save(first.copy(name = "Last"), copy = true)
        repository.orderVoices(listOf(last.reference.id, first.reference.id))
        val expected = listOf(last.reference.id, hidden.reference.id, first.reference.id)
        assertEquals(expected, OfflineSpeechRepository(root).voices().map { it.reference.id })
        assertEquals(first.reference, repository.voices().last().reference)
        val refreshed = repository.install(input, plan(input))
        repository.refreshCatalog()
        assertEquals(expected, OfflineSpeechRepository(root).voices().map { it.reference.id })
        repository.delete(setOf(last.reference, refreshed.reference))
        assertEquals(listOf(hidden.reference.id), repository.voices().map { it.reference.id })
        repository.acquire(hidden.reference).use { assertTrue(it.directory.exists()) }
    }

    @Test fun changedPreviewAndEscapedArchiveNeverPublishOrLeaveStagingFiles() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val original = archive("before")
        val changed = archive("different resource length")
        try { repository.install(changed, plan(original)); fail() }
        catch (error: OfflineInstallException) { assertEquals(OfflineInstallError.ChangedPackage, error.error) }
        val escaped = archive(extra = "../outside")
        try { repository.install(escaped, plan(escaped)); fail() }
        catch (error: OfflineInstallException) { assertEquals(OfflineInstallError.InvalidArchive, error.error) }
        assertTrue(repository.voices().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
        assertFalse(File(root.parentFile, "outside").exists())
    }

    @Test fun appDirectoryAliasesAreResolvedBeforeCheckingManagedResourcePaths() = runBlocking {
        val root = files.newFolder()
        val alias = File(files.root, "app-data-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), root.toPath())
        val repository = OfflineSpeechRepository(alias)
        val input = archive()
        val voice = repository.install(input, plan(input))
        repository.acquire(voice.reference).use { lease ->
            assertTrue(lease.resources.directory.toPath().startsWith(root.toPath()))
        }
        assertEquals(voice, repository.voices().single())
    }

    @Test fun microsoftCaseAliasesShareModelDataWithoutReplacingDeclaredFiles() = runBlocking {
        val input = files.newFile()
        ZipOutputStream(input.outputStream()).use { zip ->
            mapOf(
                "engines.yaml" to "engines: [{code: microsoft, type: inner}]",
                "config.yaml" to "microsoft: [{code: voice, name: Example, param: 'Example Voice'}]",
                "microsoft/voice/Language.INI" to "language",
                "microsoft/voice/Acoustic.apm" to "model",
                "microsoft/voice/Voice.INI" to "UPPER",
                "microsoft/voice/Voice.ini" to "lower",
                "microsoft/voice/Tokens.xml" to "<Tokens><String value=\"[INSTALLDIR]MSTTSLoczhCN.dat\"/></Tokens>",
                "microsoft/voice/MSTTSLocZhCN.dat" to "language data",
                "microsoft/voice/MSTTSLocZhCN.INI" to "language config",
                "microsoft/voice/Model.zhCN.voiceAssistant.BR2" to "prosody",
                "microsoft/voice/NUSData/Model.voiceAssistant.unt" to "domain",
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        val repository = OfflineSpeechRepository(files.newFolder())
        val voice = repository.install(input, plan(input))
        repository.acquire(voice.reference).use { lease ->
            val directory = File(lease.directory, "voice")
            assertEquals("language", File(directory, "Language.ini").readText())
            assertEquals("lower", File(directory, "Voice.ini").readText())
            assertEquals("language data", File(directory, "MSTTSLoczhCN.dat").readText())
            assertEquals("language config", File(directory, "MSTTSLoczhCN.ini").readText())
            assertEquals("prosody", File(directory, "Model.ZhCN.voiceAssistant.BR2").readText())
            assertEquals("domain", File(directory, "NUSData/Model.voiceassistant.UNT").readText())
            assertTrue(java.nio.file.Files.isSameFile(File(directory, "Acoustic.apm").toPath(),
                File(directory, "Acoustic.APM").toPath()))
        }
    }
}
