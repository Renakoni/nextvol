package indi.renakoni.nextvol

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.data.export.EpubShareFiles
import indi.renakoni.nextvol.ui.export.EpubShareActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android component identity and FileProvider, including delayed reads after chooser preparation. */
@RunWith(AndroidJUnit4::class)
class EpubShareInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun epubAndVolumeArchiveRemainReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = context.packageManager.getActivityInfo(
            EpubShareActivity.intent(context, UUID.randomUUID()).component!!, 0)
        assertEquals("indi.renakoni.nextvol.ui.book.detail.EpubShareActivity", entry.name)
        assertFalse(entry.exported)
        for (count in listOf(1, 2)) {
            val id = UUID.randomUUID()
            val staging = File(context.cacheDir, "share-test-$id").apply { mkdirs() }
            repeat(count) { File(staging, "$it.epub").writeText("epub-$it") }
            try {
                EpubShareFiles.publish(context, id, staging)
                val chooser = EpubShareFiles.chooser(context, id)
                val share = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, share.action)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    share.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                EpubShareFiles.markShared(context, id)
                val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals(1, share.clipData!!.itemCount)
                assertEquals(uri, share.clipData!!.getItemAt(0).uri)
                assertEquals(if (count == 1) "application/epub+zip" else "application/zip", context.contentResolver.getType(uri))
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    if (count == 1) assertEquals("epub-0", input.bufferedReader().readText())
                    else java.util.zip.ZipInputStream(input).use { zip ->
                        repeat(count) { index ->
                            assertEquals("$index.epub", zip.nextEntry.name)
                            assertEquals("epub-$index", zip.readBytes().toString(Charsets.UTF_8))
                        }
                        assertNull(zip.nextEntry)
                    }
                }
            } finally {
                staging.deleteRecursively()
                EpubShareFiles.directory(context, id).deleteRecursively()
            }
        }
    }
}
