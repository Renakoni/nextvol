package hnovel.network

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files

class WebsiteChallengeTest {
    @Test fun aliyunWafDocumentRequiresVerification() {
        val html = "<meta name='aliyun_waf_aa' content='fixture'><script name='aliyunwaf_fixture'></script>"
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/book", html)))
    }

    @Test fun wafScriptAndCookieBootstrapAreVerificationButQuotedChapterTextIsNot() {
        val script = "<title>loading fixture.example</title><script src='/@wafjs?fixture'></script>"
        for (status in listOf(200, 401, 403)) {
            assertEquals(BrowserChallengeKind.SiteVerification,
                websiteChallenge(response("/search", script).copy(status = status)))
        }
        val refresh = response("/search", "<meta http-equiv=refresh content=0>",
            mapOf("Set-Cookie" to listOf("_wa_=fixture; HttpOnly; Path=/; Max-Age=30"))).copy(status = 401)
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(refresh))
        assertNull(websiteChallenge(refresh.copy(headers = emptyMap())))
        assertNull(websiteChallenge(refresh.copy(status = 200)))
        assertNull(websiteChallenge(response("/chapter", "<article>&lt;script src='/@wafjs'&gt; _wa_</article>")))
        assertNull(websiteChallenge(response("/chapter", "<script src='https://other.example/@wafjs'></script>")))
        assertNull(websiteChallenge(response("/chapter", "<script src='/@wafjs-example'></script>")))
        val captcha = "<form action='/?_waform' method='post'><input name='__input'><button>Continue</button></form>"
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/search", captcha).copy(status = 401)))
        assertNull(websiteChallenge(response("/chapter", captcha.replace("/?_waform", "/search"))))
        assertNull(websiteChallenge(response("/chapter", captcha.replace("/?_waform", "https://other.example/?_waform"))))
        assertNull(websiteChallenge(response("/chapter", captcha.replace("__input", "search"))))
    }

    @Test fun aChallengeCachedByARawApiRequestCannotBecomeAnEmptyDocumentResult() = runBlocking {
        val root = Files.createTempDirectory("cached-waf")
        try { MockWebServer().use { server ->
            server.start()
            val html = "<script src='/@wafjs'></script>"
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody("<article>Readable</article>"))
            SourceBroker(root).use { broker ->
                val session = broker.open(SourceScope("fixture", "cached-waf", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("book", server.url("/book").toString(), cache = CacheMode.ReadThrough)
                assertEquals(html, (session.execute(request.copy(kind = ResourceKind.Api)) as BrokerResult.Success).response.text())
                assertEquals(FailureCode.CacheMiss, (session.execute(request.copy(cache = CacheMode.Only)) as BrokerResult.Failure).code)
                val readable = (session.execute(request) as BrokerResult.Success).response
                assertFalse(readable.fromCache)
                assertEquals("<article>Readable</article>", readable.text())
                assertEquals(2, server.requestCount)
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cookieBootstrapRetriesOnlyGetAndPreservesTheFailedPostForVerification() = runBlocking {
        val root = Files.createTempDirectory("waf-challenge")
        try { MockWebServer().use { server ->
            server.start()
            fun bootstrap() = MockResponse().setResponseCode(401)
                .addHeader("Set-Cookie", "_wa_=fixture; HttpOnly; Path=/; Max-Age=30")
                .setBody("<meta http-equiv=refresh content=0>")
            server.enqueue(bootstrap())
            server.enqueue(MockResponse().setBody("<article>Readable</article>"))
            server.enqueue(bootstrap())
            server.enqueue(bootstrap())
            server.enqueue(MockResponse().setResponseCode(401).setBody("<script src='/@wafjs?fixture'></script>"))
            SourceBroker(root).use { broker ->
                val session = broker.open(SourceScope("fixture", "waf", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val get = BrokerRequest("book", server.url("/book").toString())
                assertEquals(200, (session.execute(get) as BrokerResult.Success).response.status)
                server.takeRequest()
                assertTrue(server.takeRequest().getHeader("Cookie").orEmpty().contains("_wa_=fixture"))
                val post = get.copy(id = "search", method = "POST", body = "keyword=fixture")
                val failedPost = session.execute(post) as BrokerResult.Failure
                assertEquals(BrowserChallengeKind.SiteVerification, failedPost.challenge)
                assertEquals(post, failedPost.verificationRequest)
                assertEquals("POST", server.takeRequest().method)
                val failedGet = session.execute(get) as BrokerResult.Failure
                assertEquals(BrowserChallengeKind.SiteVerification, failedGet.challenge)
                assertEquals(get, failedGet.verificationRequest)
                assertEquals(5, server.requestCount)
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun rawLoginApiAndBrowserSubrequestsRemainReadableWhileDocumentExtractionRequiresVerification() = runBlocking {
        val root = Files.createTempDirectory("http-challenge")
        try { MockWebServer().use { server ->
            server.start()
            val html = "<html><title>Verify Yourself</title><form id='ui-form'>Slide to Unlock</form></html>"
            repeat(3) { server.enqueue(MockResponse().setBody(html)) }
            SourceBroker(root).use { broker ->
                val session = broker.open(SourceScope("fixture", "challenge", "legado"),
                    listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("toc", server.url("/WAF/VERIFY/CAPTCHA").toString())
                assertEquals(html, (session.execute(request.copy(kind = ResourceKind.Api)) as BrokerResult.Success).response.text())
                assertEquals(html, (session.executeHttp(request, RequestCommitGuard { it() }) as BrokerResult.Success).response.text())
                assertEquals(BrowserChallengeKind.SiteVerification, (session.execute(request) as BrokerResult.Failure).challenge)
            }
        } } finally { root.toFile().deleteRecursively() }
    }

    private fun response(path: String, html: String, headers: Map<String, List<String>> = emptyMap()) =
        BrokerResponse(200, "https://fixture.example$path", headers, html.toByteArray(), "UTF-8", 0)

    @Test fun recognizesSuccessfulStatusVerificationPagesWithoutMatchingChapterProse() {
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/WAF/VERIFY/CAPTCHA?from=/toc?page=14",
            "<html><title>Verify Yourself</title><form id='ui-form'>Slide to Unlock</form></html>")))
        assertEquals(BrowserChallengeKind.Cloudflare, websiteChallenge(response("/book", "<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/test'></script>")))
        assertEquals(BrowserChallengeKind.Cloudflare, websiteChallenge(response("/api", "", mapOf("CF-Mitigated" to listOf("challenge")))))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/antibot", "<html>Verification</html>")))
        assertEquals(BrowserChallengeKind.Login, websiteChallenge(response("/login", "<form><input type='password'></form>")))
        assertNull(websiteChallenge(response("/chapter", "<title>Verify Yourself</title><article>Slide to Unlock, CAPTCHA and Just a moment</article>")))
        assertNull(websiteChallenge(response("/chapter", "<title>Just a moment</title><article>A normal chapter</article>")))
        assertNull(websiteChallenge(response("/api", """{"content":"<form id='J_ManMachineVerify'>sample</form>"}""")))
        val gbk = "<html><title>人机校验</title><form>请验证</form></html>".toByteArray(charset("GB18030"))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/book", "").copy(body = gbk, charset = "GB18030")))
        assertEquals(BrowserChallengeKind.SiteVerification, websiteChallenge(response("/book", "\uFEFF<html><title>人机校验</title></html>")))
    }
}
