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

    private fun archive(token: String = "tokens", extra: String? = null): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "engines.yaml" to "engines: [{code: sherpa, type: inner}]",
                "config.yaml" to "sherpa: [{code: voice, name: Baker, locale: zh-CN, param: '{\"modelDir\":\"models/chinese\"}'}]",
            ) + resources.associate { "sherpa/models/chinese/$it" to if (it == "tokens.txt") token else "fixture" } +
                (extra?.let { mapOf(it to "must not be extracted") } ?: emptyMap())
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
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
        val replacement = archive("updated tokens")
        val next = repository.install(replacement, plan(replacement))
        assertEquals(first.reference.id, next.reference.id)
        assertNotEquals(first.reference.revision, next.reference.revision)
        assertEquals("tokens", File(lease.resources.directory, "tokens.txt").readText())
        lease.close()
        assertFalse(lease.resources.directory.exists())
        val current = repository.acquire(next.reference)
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

    @Test fun installationAcceptsModelContentsWithoutDigestComparisonAndReimportPublishesNewRevision() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val original = archive("before")
        val changed = archive("after!")
        val first = repository.install(changed, plan(original))
        repository.acquire(first.reference).use { assertEquals("after!", File(it.resources.directory, "tokens.txt").readText()) }
        val next = repository.install(changed, plan(changed))
        assertEquals(first.reference.id, next.reference.id)
        assertNotEquals(first.reference.revision, next.reference.revision)
        assertEquals(next, repository.voices().single())
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
