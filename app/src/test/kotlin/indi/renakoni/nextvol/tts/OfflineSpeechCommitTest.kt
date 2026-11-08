package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import hnovel.speech.previewOfflineVoice
import hnovel.speech.previewSpeechPackage
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class OfflineSpeechCommitTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun cancellationAfterPublicationRepairsReferencesWithoutReportingCompletion() = runBlocking {
        val root = files.newFolder()
        val repository = OfflineSpeechRepository(root)
        val originalArchive = archive("Original")
        val original = repository.install(originalArchive, plan(originalArchive))
        val stored = mutableMapOf<String, String?>()
        val dao = mockk<UserDataDao>()
        coEvery { dao.get(any()) } answers { stored[firstArg()] }
        every { dao.getFlow(any()) } returns MutableStateFlow<String?>(null)
        coEvery { dao.insert(any(), any(), "String", any()) } answers { stored[firstArg()] = arg(3) }
        val data = UserDataRepository(dao)
        val settings = SpeechSettingsRepository(data)
        val pairs = SpeechVoicePoolRepository(data)
        val reference = SpeechVoiceRef(offlineVoice = original.reference, label = original.name, externalId = "msctts_voice")
        settings.update { it.copy(offlineVoice = original.reference, voiceItems = listOf(SpeechVoiceItem("pool", reference))) }
        pairs.setManual(SpeechVoicePair("book", "person", "pool", reference))

        val archive = archive("Updated")
        val plan = plan(archive)
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val descriptor = mockk<ParcelFileDescriptor>()
        val uri = Uri.parse("content://packages/voice.zip")
        every { context.contentResolver } returns resolver
        every { resolver.openFileDescriptor(uri, "r") } returns descriptor
        every { descriptor.fd } returns 42
        // Use a real archive/catalog while adapting the Android descriptor path for the host JVM.
        val offline = mockk<OfflineSpeechRepository>()
        coEvery { offline.install(File("/proc/self/fd/42"), plan) } coAnswers { repository.install(archive, plan) }
        coEvery { offline.voices() } coAnswers { repository.voices() }
        val published = CompletableDeferred<Unit>()
        val returnFromIo = CountDownLatch(1)
        every { descriptor.close() } answers {
            // Installation has returned, but the outer IO call has not returned to its caller.
            published.complete(Unit)
            check(returnFromIo.await(10, TimeUnit.SECONDS))
        }
        val commit = OfflineSpeechCommit(context, offline, SpeechVoiceReferences(settings, pairs))
        var reportedCompletion = false
        val installing = async {
            commit.install(uri, plan)
            reportedCompletion = true
        }
        try {
            published.await()
            installing.cancel()
        } finally {
            returnFromIo.countDown()
        }
        installing.join()
        try { installing.await(); fail("Expected cancellation") }
        catch (_: CancellationException) { }

        val installed = OfflineSpeechRepository(root).voices().single()
        assertEquals(original.reference.id, installed.reference.id)
        assertNotEquals(original.reference.revision, installed.reference.revision)
        assertEquals("Updated", File(repository.directory(installed.resourceReference), "app/voice.jet").readText())
        val replacement = reference.copy(offlineVoice = installed.reference, label = "Updated")
        assertEquals(installed.reference, settings.get().offlineVoice)
        assertEquals(replacement, settings.get().voiceItems.single().value)
        assertEquals(replacement, pairs.pairs().single().value)
        assertFalse(reportedCompletion)
        verify(exactly = 1) { descriptor.close() }
    }

    private fun archive(name: String): File = files.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "config.yaml" to "msctts: [{code: voice, name: $name, locale: zh-CN, type: 0, param: 'app,region,voice'}]",
                "msctts/app/common.jet" to "common",
                "msctts/app/voice.jet" to name,
            ).forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }

    private fun plan(file: File) = previewSpeechPackage(file).let { preview ->
        val engine = preview.engines.single()
        previewOfflineVoice(preview, engine, engine.voices.single()).plan!!
    }
}
