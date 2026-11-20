package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BangumiApiTest {
    private val server = MockWebServer()
    private lateinit var api: BangumiApi
    private val session = BangumiSession(BangumiUser(17, "test"), "generation", "test-only-token")
    @Before fun setup() { server.start(); api = BangumiApi(OkHttpClient(), server.url("/")) }
    @After fun close() { session.revoke(); server.shutdown() }

    @Test fun publicTagSearchUsesTagFilterAndNeverAnAccountCredential() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[],"total":0}"""))
        api.searchTag("实教", 10)
        val request = server.takeRequest()
        assertEquals("/v0/search/subjects?limit=10&offset=10", request.path)
        assertEquals("""{"keyword":"","sort":"heat","filter":{"type":[1],"tag":["实教"]}}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun errorsRetainStatusAndBackoffButNeverTheBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("sensitive upstream response"))
        try { api.updateVolumes(session, 123, 1); fail() }
        catch (failure: BangumiApiException) {
            assertEquals(429, failure.status)
            assertEquals(60L, failure.retryAfterSeconds)
            assertFalse(failure.toString().contains("sensitive"))
        }
    }

    @Test fun onlyOfficialSubjectLinksSupplyIds() {
        for (host in listOf("bgm.tv", "bangumi.tv", "chii.in")) for (scheme in listOf("http", "https"))
            assertEquals(123, BangumiApi.subjectId("$scheme://$host/subject/123"))
        assertNull(BangumiApi.subjectId("https://example.com/subject/123"))
        assertNull(BangumiApi.subjectId("https://bgm.tv@evil.example/subject/123"))
        assertNull(BangumiApi.subjectId("-1"))
        assertNull(BangumiApi.subjectId("https://bangumi.tv/anime/list/123"))
    }

    @Test fun redirectsCannotForwardTheAccountCredential() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirect")))
        try { api.collection(session, 123); fail() }
        catch (failure: BangumiApiException) { assertEquals(302, failure.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedResponsesNeverEscapeThroughExceptionMessages() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"private-account-data\":"))
        try { api.collection(session, 123); fail() }
        catch (failure: BangumiResponseException) {
            assertEquals("Invalid Bangumi response", failure.message)
            assertNull(failure.cause)
        }
    }
}
