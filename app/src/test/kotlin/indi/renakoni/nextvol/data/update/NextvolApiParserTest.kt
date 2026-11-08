package indi.renakoni.nextvol.data.update

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class NextvolApiParserTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun metadata() = JSONObject().put("schemaVersion", 1)
        .put("tag", "v0.1.1").put("versionName", "0.1.1").put("versionCode", 10300010)
        .put("packageName", "indi.renakoni.nextvol").put("releaseNotes", "## Changes")
        .put("downloadUrl", "https://download.nextvolcom.com/releases/v0.1.1/NextVol-0.1.1-release.apk")
        .put("sha256", "a".repeat(64))

    private fun parse() = NextvolApiParser.fetchRelease(OkHttpClient(), server.url("/updates/stable.json"))

    @Test fun ownManifestPreservesVersionNotesAndChecksum() {
        server.enqueue(MockResponse().setBody(metadata().toString()))
        val result = parse()!!
        assertEquals(10300010, result.version)
        assertEquals("0.1.1", result.versionName)
        assertEquals("## Changes", result.releaseNotes)
        assertEquals("a".repeat(64), result.sha256)
        assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
    }

    @Test fun missingManifestIsEmptyWhileServiceFailureIsVisible() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(parse())
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(IOException::class.java) { parse() }
    }

    @Test fun incompatibleAndForeignMetadataIsRejected() {
        for ((key, value) in listOf(
            "schemaVersion" to 2, "tag" to "offline-runtime-4", "versionCode" to 0,
            "packageName" to "indi.dmzz_yyhyy.lightnovelreader",
            "sha256" to "missing", "downloadUrl" to "http://download.nextvolcom.com/update.apk",
        )) {
            server.enqueue(MockResponse().setBody(metadata().put(key, value).toString()))
            assertThrows(IOException::class.java) { parse() }
        }
    }
}
