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
        assertEquals(24000, original.configuration!!.sampleRate)
        assertEquals("image fixture", repository.avatar(original)!!.readText())
        repository.recordRuntimeInfo(original.reference, OfflineVoiceRuntimeInfo(original.configuration, 3, 22050))
        val discovered = repository.voices().single()
        assertEquals(22050, discovered.configuration!!.sampleRate)
        assertEquals(listOf("0", "1", "2"), speechExtensionControls(discovered.configuration.extendUI).single().candidates.keys.toList())
        // Catalogs installed before the editor have no recorded defaults.
        val legacy = original.copy(defaults = null, enabled = false)
        File(root, "voices.json").writeText(kotlinx.serialization.json.Json.encodeToString(listOf(legacy)))
        val refreshedLegacy = repository.install(input, plan(input))
        assertFalse(refreshedLegacy.enabled)
        val renamed = repository.save(refreshedLegacy.copy(name = "My voice", enabled = true))
        assertEquals(refreshedLegacy.reference.id, renamed.reference.id)
        val edited = repository.save(renamed.copy(configuration = renamed.configuration!!.copy(speed = 1f)))
        assertEquals(original.reference.id, edited.reference.id)
        assertNotEquals(original.reference.revision, edited.reference.revision)
        assertEquals(refreshedLegacy.resourceReference, edited.resourceReference)
        val copy = repository.save(edited.copy(name = "Copy"), copy = true)
        val lease = repository.acquire(copy.reference)
        repository.enable(setOf(copy.reference), false)
        val disabled = repository.voices().single { it.reference.id == copy.reference.id }
        assertThrows(IllegalStateException::class.java) { repository.acquire(disabled.reference) }
        val replacement = archive("after!", speed = 1.5f)
        val imported = repository.install(replacement, plan(replacement))
        assertEquals("My voice", imported.name)
        assertEquals(1f, imported.configuration!!.speed)
        assertEquals(1f, imported.configuration.sherpa!!.speed)
        assertEquals(1.5f, imported.defaults!!.configuration.speed)
        assertEquals(original.reference.id, imported.reference.id)
        assertEquals("tokens", File(lease.directory, "tokens.txt").readText())
        val reopened = OfflineSpeechRepository(root).voices()
        assertEquals(2, reopened.size)
        assertTrue(reopened.all { repository.avatar(it)?.readText() == "image fixture" })
        assertTrue(lease.directory.exists())
        repository.delete(setOf(disabled.reference, imported.reference))
        assertTrue(repository.voices().isEmpty())
        assertTrue(lease.directory.exists())
        lease.close()
        assertFalse(lease.directory.exists())
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

    @Test fun replacementAndDeletionRetainLeasedResourcesUntilCloseAndPersistSelectionIdentity() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val firstPackage = archive()
        val first = repository.install(firstPackage, plan(firstPackage))
        assertEquals(first, OfflineSpeechRepository(root).voices().single())
        val lease = repository.acquire(first.reference)
        // Same-sized replacement content is accepted without a model digest comparison.
        val replacement = archive("after!")
        val next = repository.install(replacement, plan(firstPackage))
        assertEquals(first.reference.id, next.reference.id)
        assertNotEquals(first.reference.revision, next.reference.revision)
        assertEquals("tokens", File(lease.resources.directory, "tokens.txt").readText())
        lease.close()
        assertFalse(lease.resources.directory.exists())
        val current = repository.acquire(next.reference)
        assertEquals("after!", File(current.resources.directory, "tokens.txt").readText())
        repository.delete(next.reference)
        assertTrue(repository.voices().isEmpty())
        assertTrue(current.resources.directory.isDirectory)
        assertThrows(NoSuchElementException::class.java) { repository.acquire(next.reference) }
        val reimported = repository.install(replacement, plan(replacement))
        assertNotEquals(next.reference.revision, reimported.reference.revision)
        current.close()
        assertFalse(current.resources.directory.exists())
        repository.acquire(reimported.reference).use { assertTrue(it.resources.directory.isDirectory) }
        repository.delete(reimported.reference)
        assertTrue(repository.voices().isEmpty())
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
