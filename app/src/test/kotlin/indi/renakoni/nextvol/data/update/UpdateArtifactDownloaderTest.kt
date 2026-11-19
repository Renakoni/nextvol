package indi.renakoni.nextvol.data.update

import android.app.Application
import android.content.ContextWrapper
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.TimeUnit

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

    private fun release(body: String) = Release(1, "fixture", "", server.url("/update").toString(),
        java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) })

    private suspend fun download(release: Release = release("apk bytes"), files: UpdateArtifactDownloader.Files = downloader.prepare()) =
        downloader.download(release, files,
            onProgress = { copied, total -> progress += copied to total })

    @Test fun runtimeProbesRaceHeadRequestsAndIgnoreFailedSources() = runBlocking {
        MockWebServer().use { mirror ->
            mirror.start()
            val urls = listOf(server.url("/pack.zip").toString(), mirror.url("/pack.zip").toString())
            server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS))
            val winner = async { downloader.firstReachable(urls) }
            withContext(Dispatchers.IO) {
                assertEquals("HEAD", server.takeRequest(3, TimeUnit.SECONDS)?.method)
                mirror.enqueue(MockResponse())
            }
            assertEquals(urls[1], withTimeout(4000) { winner.await() })
            assertEquals("HEAD", mirror.takeRequest(1, TimeUnit.SECONDS)?.method)

            server.enqueue(MockResponse().setResponseCode(404))
            mirror.enqueue(MockResponse().setResponseCode(503))
            assertNull(downloader.firstReachable(urls))

            server.enqueue(MockResponse())
            mirror.enqueue(MockResponse().setResponseCode(503))
            assertEquals(urls[0], downloader.firstReachable(urls))
        }
    }

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
        assertEquals("unknown length apk", download(release("unknown length apk"))!!.readText())
        assertTrue(progress.isEmpty())
    }

    @Test fun cancelledAndUnverifiedDownloadsCleanUpAndAllowRetry() = runBlocking {
        val files = downloader.prepare()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val stalled = launch(Dispatchers.IO) { download(files = files) }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        withTimeout(5000) { stalled.cancelAndJoin() }

        server.enqueue(MockResponse().setBody("wrong apk"))
        try {
            download(release("expected apk"), files)
            fail("Expected checksum failure")
        } catch (expected: java.io.IOException) {
            assertEquals("SHA-256 mismatch", expected.message)
        }
        assertFalse(files.apk.exists())
        assertFalse(files.temporary.exists())
        server.enqueue(MockResponse().setBody("hello"))
        assertEquals("hello", download(release("hello"), files)!!.readText())
    }
}
