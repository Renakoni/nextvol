package indi.renakoni.nextvol.data

import android.app.Application
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 35], application = Application::class)
class ExternalFilesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://qq-files/opaque/12345")

    @Test fun opaqueProviderNamesOverrideGenericAndIncorrectSenderTypes() = runBlocking {
        for ((name, format) in listOf("小说.第一卷.EPUB" to LocalBookFormat.EPUB, "小说.TXT" to LocalBookFormat.TXT,
            "NextVolData.NVBACKUP" to null, "old.lnr" to null)) {
            ShadowContentResolver.registerProviderInternal("qq-files", NamedProvider(name))
            for (mime in listOf("application/octet-stream", "application/zip", "text/plain")) {
                val file = ExternalFiles.resolve(context.contentResolver, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime))!!
                assertEquals(name, file.name)
                assertEquals(format, file.bookFormat)
            }
        }
    }

    @Test fun streamAndClipUrisWorkButSharedTextAndRemoteUrlsAreNotFiles() {
        assertEquals(uri, ExternalFiles.uri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(uri, ExternalFiles.uri(Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newRawUri("book", uri) }))
        assertNull(ExternalFiles.uri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "content://qq-files/book.txt")))
        assertNull(ExternalFiles.uri(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/book.epub"))))
        assertNull(ExternalFiles.uri(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))))
    }

    @Test fun pathAndMimeFallbacksWorkWithoutDisplayNameAndUnknownArchivesAreRejected() = runBlocking {
        val path = Uri.parse("content://no-metadata/books/novel.part.2.txt")
        assertEquals(LocalBookFormat.TXT, ExternalFiles.resolve(context.contentResolver, Intent(Intent.ACTION_VIEW, path))!!.bookFormat)
        assertEquals(LocalBookFormat.EPUB, ExternalFiles.resolve(context.contentResolver,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/epub+zip"))!!.bookFormat)
        assertNull(ExternalFiles.resolve(context.contentResolver, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip")))
        assertNull(ExternalFiles.resolve(context.contentResolver, Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/photo.jpg"))))
    }

    @Test fun installedManifestResolvesLocalOpenAndShareWithoutClaimingWebLinks() {
        fun resolves(intent: Intent) = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.name == "indi.renakoni.nextvol.MainActivity" }
        for (mime in listOf("application/epub+zip", "text/plain", ExternalFiles.BACKUP_MIME,
            "application/octet-stream", "application/zip", "application/x-zip-compressed")) {
            assertTrue(mime, resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)))
            assertTrue(mime, resolves(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)))
        }
        assertTrue(resolves(Intent(Intent.ACTION_VIEW, uri)))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/novel.part.2.EPUB"))))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.org/book.epub"), "application/epub+zip")))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")))
        assertTrue(resolves(Intent(Intent.ACTION_VIEW, Uri.parse("legado://import/bookSource?src=https://example.org/source.json"))))
    }

    @Test fun stagingOwnsTheBytesAndNeverDeletesTheSenderFile(): Unit = runBlocking {
        val original = context.cacheDir.resolve("original.nvbackup").apply { writeText("backup bytes") }
        shadowOf(context.contentResolver).registerInputStream(uri, original.inputStream())
        val staged = ExternalFiles.stageBackup(context, uri)
        try {
            assertEquals(original.readText(), staged.readText())
            assertEquals(context.filesDir.resolve("external-backups"), staged.parentFile)
            original.writeText("sender changed")
            assertEquals("backup bytes", staged.readText())
            assertThrows(IllegalArgumentException::class.java) { ExternalFiles.backupFile(context, "../original.nvbackup") }
        } finally {
            staged.delete()
            assertTrue(original.exists())
            original.delete()
        }
    }

    private class NamedProvider(private val name: String) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
