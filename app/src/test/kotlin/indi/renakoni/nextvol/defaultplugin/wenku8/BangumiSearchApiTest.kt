package indi.renakoni.nextvol.defaultplugin.wenku8

import indi.renakoni.nextvol.data.bangumi.*
import indi.renakoni.nextvol.defaultplugin.wenku8.search.BangumiSearchApi
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BangumiSearchApiTest {
    private val official = mockk<BangumiApi>()
    private val mirror = mockk<BangumiApi>()
    private val reader = BangumiSearchApi(official, mirror)
    private val page = BangumiSearchPage(listOf(BangumiSubject(19441, nameCn = "春物")), 1)

    @Test fun slowOfficialReadStartsMirrorAndCancelsLosingRequest() = runTest {
        var cancelled = false
        coEvery { official.searchTag("春物", 10) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        coEvery { mirror.searchTag("春物", 10) } returns page
        assertEquals(page, reader.search("春物", 10, true))
        assertEquals(750L, currentTime)
        assertTrue(cancelled)
    }

    @Test fun officialFailureStartsMirrorWithoutWaitingAndRelationsUseItToo() = runTest {
        coEvery { official.related(19441) } throws IOException("blocked")
        val related = listOf(BangumiRelatedSubject(345943, 1, relation = "续集"))
        coEvery { mirror.related(19441) } returns related
        assertEquals(related, reader.related(19441))
        assertEquals(0L, currentTime)
    }

    @Test fun mirrorFailureDoesNotCancelAnOfficialRequestThatCanStillSucceed() = runTest {
        coEvery { official.search("春物", 0) } coAnswers { delay(1_500); page }
        coEvery { mirror.search("春物", 0) } throws IOException("mirror unavailable")
        assertEquals(page, reader.search("春物", 0, false))
        assertEquals(1_500L, currentTime)
    }

    @Test fun bothSlowEndpointsTimeOutAndReleaseAllRequests() = runTest {
        var finished = 0
        coEvery { official.search(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { finished++ }
        }
        coEvery { mirror.search(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { finished++ }
        }
        try { reader.search("春物", 0, false); fail() } catch (_: IOException) { }
        assertEquals(8_750L, currentTime)
        assertEquals(2, finished)
    }

    @Test fun queryCancellationCancelsBothRoutesWithoutReturningAResult() = runTest {
        var finished = 0
        coEvery { official.search(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { finished++ }
        }
        coEvery { mirror.search(any(), any()) } coAnswers {
            try { awaitCancellation() } finally { finished++ }
        }
        val job = launch { reader.search("春物", 0, false); fail("Cancelled query returned") }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        assertEquals(2, finished)
    }

    @Test fun mirrorReceivesOnlyPublicRequestsWithoutCredentialsAndRejectsHtml() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val transport = BangumiSearchApi(official, BangumiApi(OkHttpClient(), server.url("/")))
            coEvery { official.search(any(), any()) } throws IOException("blocked")
            coEvery { official.searchTag(any(), any()) } throws IOException("blocked")
            coEvery { official.related(any()) } throws IOException("blocked")
            server.enqueue(MockResponse().setBody("""{"data":[],"total":0}"""))
            server.enqueue(MockResponse().setBody("""{"data":[],"total":0}"""))
            server.enqueue(MockResponse().setBody("[]"))
            transport.search("春物", 0, false)
            transport.search("春物", 10, true)
            transport.related(19441)
            for (path in listOf("/v0/search/subjects?limit=10&offset=0", "/v0/search/subjects?limit=10&offset=10",
                "/v0/subjects/19441/subjects")) {
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
            server.enqueue(MockResponse().setBody("<html>Browser verification required</html>"))
            try { transport.search("春物", 0, false); fail() } catch (_: IOException) { }
        } finally { server.shutdown() }
    }

    @Test fun mirrorSuccessDoesNotWaitForTheLosingOfficialResponseBody() = runBlocking {
        val officialServer = MockWebServer()
        val mirrorServer = MockWebServer()
        officialServer.start()
        mirrorServer.start()
        try {
            officialServer.enqueue(MockResponse().setBody("""{"data":[],"total":0}""")
                .setBodyDelay(3, TimeUnit.SECONDS))
            mirrorServer.enqueue(MockResponse().setBody("""{"data":[{"id":19441}],"total":1}"""))
            val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val transport = BangumiSearchApi(BangumiApi(client, officialServer.url("/")),
                BangumiApi(client, mirrorServer.url("/")))
            // The official server sends headers immediately but stalls its body after await() resumes.
            val result = withTimeout(2_000) { transport.search("春物", 0, false) }
            assertEquals(19441, result.data.single().id)
            assertEquals(1, officialServer.requestCount)
            assertEquals(1, mirrorServer.requestCount)
        } finally { officialServer.shutdown(); mirrorServer.shutdown() }
    }
}
