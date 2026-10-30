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

    private fun archive(token: String = "tokens", extra: String? = null): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "engines.yaml" to "engines: [{code: sherpa, type: inner}]",
                "config.yaml" to "sherpa: [{code: voice, name: Baker, locale: zh-CN, param: '{\"modelDir\":\"models/chinese\"}'}]",
            ) + ChineseMatchaProfile.files.keys.associate { "sherpa/models/chinese/$it" to if (it == "tokens.txt") token else "fixture" } +
                (extra?.let { mapOf(it to "must not be extracted") } ?: emptyMap())
            entries.forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
    }
    private fun plan(file: File): MatchaPackagePlan {
        val preview = previewSpeechPackage(file)
        return previewMatchaVoice(preview, preview.engines.single(), preview.engines.single().voices.single()).plan!!
    }

    @Test fun replacementAndDeletionRetainLeasedResourcesUntilCloseAndPersistSelectionIdentity() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root, emptyMap())
        val firstPackage = archive()
        val first = repository.install(firstPackage, plan(firstPackage))
        assertEquals(first, OfflineSpeechRepository(root, emptyMap()).voices().single())
        assertEquals(first.reference, repository.install(firstPackage, plan(firstPackage)).reference)
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
        // Re-importing a retired revision must also cancel its pending deletion.
        assertEquals(next.reference, repository.install(replacement, plan(replacement)).reference)
        current.close()
        assertTrue(current.resources.directory.isDirectory)
        repository.delete(next.reference)
        assertFalse(current.resources.directory.exists())
    }

    @Test fun changedPreviewAndEscapedArchiveNeverPublishOrLeaveStagingFiles() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root, emptyMap())
        val original = archive("before")
        val changed = archive("after!") // Same size, different CRC.
        try { repository.install(changed, plan(original)); fail() }
        catch (error: OfflineInstallException) { assertEquals(OfflineInstallError.ChangedPackage, error.error) }
        val escaped = archive(extra = "../outside")
        try { repository.install(escaped, plan(escaped)); fail() }
        catch (error: OfflineInstallException) { assertEquals(OfflineInstallError.InvalidArchive, error.error) }
        assertTrue(repository.voices().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
        assertFalse(File(root.parentFile, "outside").exists())
    }

    @Test fun correctFilenamesDoNotMakeAnUnknownGraphPairSupported() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val unknown = archive()
        try { repository.install(unknown, plan(unknown)); fail() }
        catch (error: OfflineInstallException) { assertEquals(OfflineInstallError.UnsupportedModel, error.error) }
        assertTrue(repository.voices().isEmpty())
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun appDirectoryAliasesAreResolvedBeforeCheckingManagedResourcePaths() = runBlocking {
        val root = files.newFolder()
        val alias = File(files.root, "app-data-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), root.toPath())
        val repository = OfflineSpeechRepository(alias, emptyMap())
        val input = archive()
        val voice = repository.install(input, plan(input))
        repository.acquire(voice.reference).use { lease ->
            assertTrue(lease.resources.directory.toPath().startsWith(root.toPath()))
        }
        assertEquals(voice, repository.voices().single())
    }
}
