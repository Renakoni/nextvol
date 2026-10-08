package indi.renakoni.nextvol.benchmark.bookshelf

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real SAF selection and reader flows against the minified app on a disposable device. */
@LargeTest
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class LocalBookImportTest : UiAutomatorTest() {
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val documentsPackage by lazy {
        shell("cmd package resolve-activity --brief -a android.intent.action.OPEN_DOCUMENT -c android.intent.category.OPENABLE -t '*/*'")
            .trim().lineSequence().last().substringBefore('/')
    }

    @Test fun txtEncodingAndChapterRuleCanBeRepairedBeforeImportAndReadAfterRestart() {
        val name = "NextVol TXT ${UUID.randomUUID()}.txt"
        val text = "Chapter 1 First\nFirst local paragraph\nChapter 2 Second\nSecond local paragraph"
        val uri = document(name, "text/plain", text.toByteArray(Charsets.UTF_16LE))
        try {
            openPicker()
            selectDocument(name)
            assertText("Unable to import")
            clickText("Text encoding")
            clickText("UTF-16LE")
            scrollToText("Contents · 2 chapters")
            scrollToTop()
            device.findObject(By.clazz("android.widget.EditText")).click()
            setFirstTextField("Imported TXT book")
            hideKeyboard()
            clickScrolledText("Chapter rule")
            scrollToText("Title regular expression")
            setRule("(")
            scrollToText("Unable to import")
            var confirm = assertText("Import")
            while (!confirm.isClickable && confirm.parent != null) confirm = confirm.parent
            assertFalse("An invalid rule must disable import", confirm.isEnabled)
            scrollToTop()
            scrollToText("Title regular expression")
            setRule("")
            scrollToText("Contents · 1 chapter")
            scrollToTop()
            clickScrolledText("Restore default rule")
            scrollToText("Contents · 2 chapters")
            clickText("Import")
            assertText("Local Books")
            assertText("Imported TXT book")
            assertEquals(1, resolver.delete(uri, null, null))
            restartApp()
            openBottomNavigation("Bookshelf")
            clickText("Local Books")
            clickText("Imported TXT book")
            clickScrolledText("Chapter 2 Second")
            assertTextContains("Second local paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            clickDescription("Contents")
            clickText("Chapter 1 First")
            pressBack()
            assertTextContains("First local paragraph")
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun epubUsesItsReadingOrderAndRemainsReadableAfterTheOriginalIsRemoved() {
        val name = "NextVol EPUB ${UUID.randomUUID()}.epub"
        val uri = document(name, "application/epub+zip", epub())
        try {
            openPicker()
            selectDocument(name)
            scrollToText("Contents · 2 chapters")
            assertText("Opening")
            assertText("Ending")
            assertTextNotVisible("Text encoding")
            clickText("Import")
            assertText("Imported illustrated book")
            assertEquals(1, resolver.delete(uri, null, null))
            restartApp()
            openBottomNavigation("Bookshelf")
            clickText("Local Books")
            clickText("Imported illustrated book")
            assertText("Local author")
            clickScrolledText("Opening")
            assertTextContains("First EPUB paragraph")
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            clickDescription("Contents")
            clickText("Ending")
            pressBack()
            assertTextContains("Final EPUB paragraph")
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun cancellingThePickerOrPreviewDoesNotAddABook() {
        val name = "NextVol cancelled ${UUID.randomUUID()}.epub"
        val uri = document(name, "application/epub+zip", epub())
        try {
            openPicker()
            repeat(3) { if (device.currentPackageName == documentsPackage) pressBack() }
            assertText("Benchmark Shelf")
            clickDescription("More options")
            clickText("Import Local Book")
            selectDocument(name)
            assertText("Imported illustrated book")
            clickDescription("Cancel")
            assertText("Benchmark Shelf")
            restartApp()
            openBottomNavigation("Bookshelf")
            assertTextNotVisible("Local Books")
            assertTextNotVisible("Imported illustrated book")
            resolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun openPicker() {
        launchApp()
        openBottomNavigation("Bookshelf")
        clickDescription("More options")
        clickText("Import Local Book")
        assertTrue("SAF picker did not open", device.wait(Until.hasObject(By.pkg(documentsPackage)), TIMEOUT))
    }

    private fun selectDocument(name: String) {
        device.waitForIdle()
        val rootsSelector = By.res(documentsPackage, "roots_list")
        if (!device.hasObject(rootsSelector)) clickDescription("Show roots")
        device.waitForIdle()
        val roots = device.wait(Until.findObject(rootsSelector), TIMEOUT)
        assertNotNull("DocumentsUI navigation drawer was not visible", roots)
        val downloads = roots.findObject(By.text("Downloads"))
        assertNotNull("Downloads root was not visible", downloads)
        clickCenter(downloads)
        device.waitForIdle()
        val list = device.findObject(By.res(documentsPackage, "dir_list"))
        repeat(10) { if (list?.scroll(Direction.DOWN, 0.9f) != true) return@repeat }
        val displayName = name.substringBeforeLast('.')
        var file = device.findObject(By.textContains(displayName))
        var attempts = 0
        while (file == null && attempts++ < 20) {
            list?.scroll(Direction.UP, 0.8f)
            device.waitForIdle()
            file = device.findObject(By.textContains(displayName))
        }
        assertNotNull("The fixture was not visible in DocumentsUI: $name", file)
        file!!.click()
        assertText("Import Local Book")
    }

    private fun scrollToTop() {
        repeat(10) {
            val list = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
            if (list?.scroll(Direction.DOWN, 0.8f) != true) return
            device.waitForIdle()
        }
    }

    private fun setRule(value: String) {
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("The chapter rule field was not visible", fields.isNotEmpty())
        fields.last().click()
        fields.last().text = value
        hideKeyboard()
    }

    private fun hideKeyboard() {
        device.waitForIdle()
        if ("mInputShown=true" in shell("dumpsys input_method")) device.pressBack()
        device.waitForIdle()
    }

    private fun document(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun epub(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            // ZIP order deliberately differs from the declared reading order.
            entry("OPS/end.xhtml", "<html><body><h1>Ending</h1><p>Final EPUB paragraph</p></body></html>")
            entry("OPS/start.xhtml", "<html><body><h1>Opening</h1><p>First EPUB paragraph</p><img src=\"cover.png\"/></body></html>")
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>")
            entry("OPS/book.opf", """<package><metadata><title>Imported illustrated book</title><creator>Local author</creator></metadata>
                <manifest><item id="start" href="start.xhtml" media-type="application/xhtml+xml"/>
                <item id="end" href="end.xhtml" media-type="application/xhtml+xml"/>
                <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest>
                <spine><itemref idref="start"/><itemref idref="end"/></spine></package>""")
            zip.putNextEntry(ZipEntry("OPS/cover.png"))
            zip.write(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAwAAAASCAIAAADgy6hbAAAAG0lEQVR4nGPUCPZkIASYCKoYVTQaBGBAvaQCAC3LAOgrKfT7AAAAAElFTkSuQmCC", android.util.Base64.DEFAULT))
            zip.closeEntry()
        }
    }.toByteArray()
}
