package indi.renakoni.nextvol.data.update

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UpdateArtifactDownloaderTest {
    @get:Rule val directory = TemporaryFolder()
    private val server = MockWebServer()
    private val downloader by lazy { UpdateArtifactDownloader(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getCacheDir() = directory.root
    }) }
    private val progress = mutableListOf<Pair<Long, Long>>()

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun release(hash: String? = null) = object : Release {
        override val version = 1
        override val versionName = "fixture"
        override val releaseNotes = ""
        override val downloadUrl = server.url("/update").toString()
        override val sha256 = hash
    }

    private suspend fun download(release: Release = release(), files: UpdateArtifactDownloader.Files = downloader.prepare()) =
        downloader.download(release, files,
            onProgress = { copied, total -> progress += copied to total })

    @Test fun directApkReplacesOldFilesAndReportsBytesWhileUnknownLengthSkipsProgress() = runBlocking {
        val stale = downloader.prepare()
        stale.apk.writeText("old apk")
        stale.temporary.writeText("old temporary file")
        val files = downloader.prepare()
        assertEquals(File(directory.root, "updates/NextVol-update.apk"), files.apk)
        assertEquals(File(directory.root, "updates/NextVol-update.tmp"), files.temporary)
        assertFalse(files.apk.exists())
        assertFalse(files.temporary.exists())
        server.enqueue(MockResponse().setBody("apk bytes"))
        assertEquals("apk bytes", download(files = files)!!.readText())
        assertEquals(9L to 9L, progress.last())
        assertFalse(files.temporary.exists())
        progress.clear()
        server.enqueue(MockResponse().setChunkedBody("unknown length apk", 3))
        assertEquals("unknown length apk", download()!!.readText())
        assertTrue(progress.isEmpty())
    }

    @Test fun checksumFailureCleansUpAndACorrectHashAllowsRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("wrong apk"))
        val files = downloader.prepare()
        try {
            download(release("0".repeat(64)), files)
            fail("Expected checksum failure")
        } catch (expected: java.io.IOException) {
            assertEquals("Update SHA-256 mismatch", expected.message)
        }
        assertFalse(files.apk.exists())
        assertFalse(files.temporary.exists())
        server.enqueue(MockResponse().setBody("hello"))
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals("hello", download(release(expected), files)!!.readText())
    }
}
