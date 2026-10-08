package indi.renakoni.nextvol.data.update

import android.app.Application
import indi.renakoni.nextvol.R
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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
class GithubParserTest {
    private val server = MockWebServer()
    private val apkUrl = "https://github.com/Renakoni/nextvol/releases/download/v1.4.0/NextVol-1.4.0-release.apk"

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun metadata(url: String = apkUrl) = JSONObject()
        .put("draft", false).put("prerelease", false).put("tag_name", "v1.4.0")
        .put("body", "## Changes\nA new version")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "NextVol-1.4.0-release.apk").put("browser_download_url", url)))

    private fun parse(): Release? = GithubParser.fetchRelease(
        MutableStateFlow(UpdatePhase(R.string.update_phase_not_checked)),
        OkHttpClient(), server.url("/api/"), server.url("/raw/"),
    )

    @Test fun stableReleaseReadsItsOwnTagAndPreservesMarkdown() {
        server.enqueue(MockResponse().setBody(metadata().toString()))
        server.enqueue(MockResponse().setBody("""
            android {
                defaultConfig {
                    versionCode = 1_04_00_001 // release version
                    versionName = "1.4.0"
                }
            }
        """.trimIndent()))
        val result = parse()!!
        assertEquals(10400001, result.version)
        assertEquals("1.4.0", result.versionName)
        assertEquals("## Changes\nA new version", result.releaseNotes)
        assertEquals(apkUrl, result.downloadUrl)
        assertEquals("/api/repos/Renakoni/nextvol/releases/latest", server.takeRequest().path)
        assertEquals("/raw/Renakoni/nextvol/refs/tags/v1.4.0/app/build.gradle.kts", server.takeRequest().path)
    }

    @Test fun noReleasesIsNotANetworkFailure() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(parse())
        assertEquals(1, server.requestCount)
    }

    @Test fun rateLimitsAndServerErrorsAreFailures() {
        for (code in listOf(403, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertThrows(IOException::class.java) { parse() }
        }
    }

    @Test fun draftsAndPrereleasesAreNeverOffered() {
        for (flag in listOf("draft", "prerelease")) {
            server.enqueue(MockResponse().setBody(metadata().put(flag, true).toString()))
            assertNull(parse())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun missingAndAmbiguousApksFailWithoutSelectingAnArbitraryAsset() {
        for (count in listOf(0, 2)) {
            val json = metadata()
            val asset = json.getJSONArray("assets").getJSONObject(0)
            json.put("assets", JSONArray().apply { repeat(count) { put(asset) } })
            server.enqueue(MockResponse().setBody(json.toString()))
            assertThrows(IOException::class.java) { parse() }
        }
    }

    @Test fun upstreamExternalAndInsecureDownloadsAreRejected() {
        for (url in listOf(
            apkUrl.replace("Renakoni/nextvol", "dmzz-yyhyy/LightNovelReader"),
            apkUrl.replace("github.com", "example.org"),
            apkUrl.replace("https://", "http://"),
            apkUrl.replace("/v1.4.0/", "/v1.3.0/"),
        )) {
            server.enqueue(MockResponse().setBody(metadata(url).toString()))
            assertThrows(IOException::class.java) { parse() }
        }
    }

    @Test fun invalidOrMissingTagMetadataDoesNotInventAVersion() {
        server.enqueue(MockResponse().setBody(metadata().toString()))
        server.enqueue(MockResponse().setBody("versionName = \"1.4.0\""))
        assertThrows(IOException::class.java) { parse() }
        server.enqueue(MockResponse().setBody(metadata().toString()))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(IOException::class.java) { parse() }
    }
}
