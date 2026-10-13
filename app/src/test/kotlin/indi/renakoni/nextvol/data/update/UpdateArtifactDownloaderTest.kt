package indi.renakoni.nextvol.data.update

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UpdateArtifactDownloaderTest {
    @get:Rule val directory = TemporaryFolder()
    private val server = MockWebServer()
    private val downloader by lazy { UpdateArtifactDownloader(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getCacheDir() = directory.root
    }) }
    private val progress = mutableListOf<Pair<Long, Long>>()
    private val events = mutableListOf<String>()

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun release(transform: ((File, File) -> Unit)? = null) =
        APIParser.APIRelease(1, "fixture", "", server.url("/update").toString(), transform)

    private suspend fun download(release: Release = release(), files: UpdateArtifactDownloader.Files = downloader.prepare()) =
        downloader.download(release, files,
            onProgress = { copied, total -> progress += copied to total },
            onProcessing = { events += "processing" },
            onHttpFailure = { events += "HTTP $it" })

    @Test fun directApkReplacesOldFilesAndReportsBytesWhileUnknownLengthSkipsProgress() = runBlocking {
        val stale = downloader.prepare()
        stale.apk.writeText("old apk")
        stale.temporary.writeText("old temporary file")
        val files = downloader.prepare()
        assertEquals(File(directory.root, "updates/LightNovelReader-update.apk"), files.apk)
        assertEquals(File(directory.root, "updates/LightNovelReader-update.tmp"), files.temporary)
        assertFalse(files.apk.exists())
        assertFalse(files.temporary.exists())
        server.enqueue(MockResponse().setBody("apk bytes"))
        assertEquals("apk bytes", download(files = files)!!.readText())
        assertEquals(9L to 9L, progress.last())
        assertFalse(files.temporary.exists())
        assertTrue(events.isEmpty())
        progress.clear()
        server.enqueue(MockResponse().setChunkedBody("unknown length apk", 3))
        assertEquals("unknown length apk", download()!!.readText())
        assertTrue(progress.isEmpty())
    }

    @Test fun zipConversionUsesTheReleaseCallback() = runBlocking {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                output.putNextEntry(ZipEntry("release.apk"))
                output.write("converted apk".toByteArray())
                output.closeEntry()
            }
        }.toByteArray()
        val zipped = release { source, target ->
            ZipFile(source).use { archive ->
                val apk = archive.getEntry("release.apk")
                archive.getInputStream(apk).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        server.enqueue(MockResponse().setBody(Buffer().write(zip)))
        assertEquals("converted apk", download(zipped)!!.readText())
        assertEquals(listOf("processing"), events)
    }
}
