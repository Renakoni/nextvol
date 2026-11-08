package indi.renakoni.nextvol.data.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import io.mockk.*
import indi.renakoni.nextvol.ui.book.detail.EpubShareActivity as LegacyEpubShareActivity
import indi.renakoni.nextvol.ui.export.EpubShareActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class EpubShareTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun providerPaths() {
        // AndroidX checks canonical roots with '/', which cannot match Windows File paths.
        // Linux CI and the instrumentation test exercise the actual FileProvider.
        if (File.separatorChar == '\\') {
            mockkStatic(FileProvider::class)
            every { FileProvider.getUriForFile(any(), any(), any<File>()) } answers {
                val file = thirdArg<File>()
                Uri.parse("content://${secondArg<String>()}/epub_exports/${file.parentFile!!.name}/${Uri.encode(file.name)}").also {
                    shadowOf(context.contentResolver).registerInputStream(it, file.inputStream())
                }
            }
        } else {
            // Robolectric gives each test a new cache directory; attachInfo resets AndroidX's static root cache.
            val info = context.packageManager.resolveContentProvider("${context.packageName}.provider", 0)!!
            FileProvider().attachInfo(context, info)
        }
    }

    @After fun resetProvider() { if (File.separatorChar == '\\') unmockkStatic(FileProvider::class) }

    private fun publication(count: Int = 1): UUID {
        val id = UUID.randomUUID()
        val staging = File(context.cacheDir, "share-test-$id").apply { mkdirs() }
        repeat(count) { File(staging, "$it - Book.epub").writeText("epub-$it") }
        EpubShareFiles.publish(context, id, staging)
        return id
    }

    @Suppress("DEPRECATION")
    @Test fun singleFileAndVolumeArchiveHaveMatchingStreamsClipDataMimeAndReadOnlyGrants() {
        for (count in listOf(1, 3)) {
            val id = publication(count)
            val chooser = EpubShareFiles.chooser(context, id)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, share.action)
            assertEquals(if (count == 1) "application/epub+zip" else "application/zip", share.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                share.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(1, share.clipData!!.itemCount)
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            assertEquals("content", uri.scheme)
            assertTrue(uri.path!!.startsWith("/epub_exports/$id/"))
            context.contentResolver.openInputStream(uri)!!.use { input ->
                if (count == 1) assertEquals("epub-0", input.bufferedReader().readText())
                else java.util.zip.ZipInputStream(input).use { zip ->
                    repeat(count) { index ->
                        assertEquals("$index - Book.epub", zip.nextEntry.name)
                        assertEquals("epub-$index", zip.readBytes().toString(Charsets.UTF_8))
                    }
                    assertNull(zip.nextEntry)
                }
            }
        }
    }

    @Test fun closingShareActivityKeepsArchivesReadableAndAutomaticDeliveryIsConsumed() {
        val id = publication()
        val automaticIntent = EpubShareActivity.intent(context, id, automatic = true)
        assertEquals(LegacyEpubShareActivity::class.java.name, automaticIntent.component!!.className)
        assertEquals("nextvol-epub:$id", automaticIntent.dataString)
        val first = Robolectric.buildActivity(LegacyEpubShareActivity::class.java,
            automaticIntent).create()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(first.get()).nextStartedActivity.action)
        assertTrue(first.get().isFinishing)
        first.destroy()
        assertTrue(EpubShareFiles.wasShared(context, id))
        assertEquals("epub-0", EpubShareFiles.files(context, id).single().readText())

        val duplicate = Robolectric.buildActivity(LegacyEpubShareActivity::class.java,
            EpubShareActivity.intent(context, id, automatic = true)).create()
        assertNull(shadowOf(duplicate.get()).nextStartedActivity)
        val manual = Robolectric.buildActivity(LegacyEpubShareActivity::class.java,
            EpubShareActivity.intent(context, id)).create()
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(manual.get()).nextStartedActivity.action)
    }

    @Test fun recreationAndMissingArtifactsDoNotCrashOrOpenASecondChooser() {
        val recreated = Robolectric.buildActivity(LegacyEpubShareActivity::class.java,
            EpubShareActivity.intent(context, publication())).create(Bundle())
        assertNull(shadowOf(recreated.get()).nextStartedActivity)
        val expired = Robolectric.buildActivity(LegacyEpubShareActivity::class.java,
            EpubShareActivity.intent(context, UUID.randomUUID())).create()
        assertTrue(expired.get().isFinishing)
        assertNull(shadowOf(expired.get()).nextStartedActivity)
    }

    @Test fun laterExportsCleanExpiredArtifactsAndLeaveRecentRecipientsFilesAlone() {
        val expired = publication()
        val recent = publication()
        assertTrue(EpubShareFiles.directory(context, expired).setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000))
        publication()
        assertTrue(EpubShareFiles.files(context, expired).isEmpty())
        assertEquals("epub-0", EpubShareFiles.files(context, recent).single().readText())
    }

    @Test fun cancellingVolumeArchiveCreationDoesNotPublishPartialFiles() {
        val id = UUID.randomUUID()
        val staging = File(context.cacheDir, "share-test-$id").apply { mkdirs() }
        repeat(2) { File(staging, "$it.epub").writeBytes(ByteArray(128 * 1024)) }
        var checks = 0
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                EpubShareFiles.publish(context, id, staging) { if (++checks == 3) throw java.util.concurrent.CancellationException() }
            }
            assertTrue(EpubShareFiles.files(context, id).isEmpty())
        } finally { staging.deleteRecursively() }
    }

    @Test fun partiallyEvictedVolumesCannotBeSharedAsASmallerSelection() {
        val id = publication(2)
        assertTrue(EpubShareFiles.files(context, id).first().delete())
        assertTrue(EpubShareFiles.files(context, id).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { EpubShareFiles.chooser(context, id) }
    }
}
