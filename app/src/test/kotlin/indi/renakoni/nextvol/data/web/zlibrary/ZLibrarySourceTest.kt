package indi.renakoni.nextvol.data.web.zlibrary

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.network.*
import indi.renakoni.nextvol.data.explore.ExploreRepository
import indi.renakoni.nextvol.data.explore.searchFailure
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
class ZLibrarySourceTest {
    @get:Rule val folder = TemporaryFolder()
    private inner class Fixture : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val broker = SourceBroker(folder.newFolder().toPath())
        val session = broker.open(SourceScope("builtin", "zlibrary", "zlibrary-eapi"),
            listOf(NetworkGrant(server.url("/").toString(), true)))
        val client = ZLibraryClient(session, server.url("/").toString())
        val source = ZLibrarySource(RuntimeEnvironment.getApplication(), session, client)
        override fun close() { broker.close(); server.close() }
    }

    private fun book(id: Int = 1, hash: String = "abcdef") = buildJsonObject {
        put("id", id); put("hash", hash); put("title", "三体"); put("author", "刘慈欣")
        put("language", "Chinese"); put("extension", "epub"); put("year", 2020)
        put("filesizeString", "2 MB"); put("publisher", "Example"); put("description", "<p>简介</p>")
        put("cover", "https://covers.invalid/book.jpg")
        put("href", "https://untrusted.invalid/book/changing-title-slug")
        put("readOnlineUrl", "https://untrusted.invalid/token-that-must-not-be-used")
    }
    private fun page(current: Int, next: Int?, vararg ids: Int) = buildJsonObject {
        put("success", 1); put("books", JsonArray(ids.map { book(it) }))
        put("pagination", buildJsonObject { put("current", current); put("next", next?.let(::JsonPrimitive) ?: JsonPrimitive(false)) })
    }
    private fun response(data: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(data.toString())

    @Test fun utf8FilterPaginationAndVersionsPassThroughTheSourceBoundSearchContract() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(response(page(1, 2, 1, 2)))
            f.server.enqueue(response(page(2, null, 2, 3)))
            val registry = WebSourceRegistry()
            registry.register(f.source, ZLibrarySources.METADATA)
            try {
                val search = ExploreRepository(registry).open(ZLibrarySources.ID).get()!!
                val mode = search.types.single { it.type == "chinese-epub" }
                val events = search.search(mode, "三体 + 刘慈欣").toList()
                val books = events.filterIsInstance<SearchResult.MultipleBook>().map { SourceBookId.fromStorageKey(it.bookId) }
                assertEquals(listOf("1/abcdef", "2/abcdef", "3/abcdef"), books.map { it.remoteId })
                assertTrue(books.all { it.sourceId == ZLibrarySources.ID })
                assertTrue(events.last() is SearchResult.End)
                for (index in 1..2) {
                    val request = f.server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method)
                    assertEquals("/eapi/book/search", request.path)
                    val form = request.body.readUtf8().split('&').associate { field ->
                        val pair = field.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
                    }
                    assertEquals("三体 + 刘慈欣", form["message"])
                    assertEquals("chinese", form["languages[0]"])
                    assertEquals("EPUB", form["extensions[0]"])
                    assertEquals(index.toString(), form["page"])
                }
            } finally { registry.unregister(ZLibrarySources.ID) }
        }
    }

    @Test fun informationUsesOnlyIdHashAndMirrorChangesKeepTheSameBook() = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            for (f in listOf(first, second)) {
                f.server.enqueue(response(buildJsonObject { put("success", 1); put("book", book(17)) }))
                val information = f.source.getBookInformation("17/abcdef").get()!!
                assertEquals("17/abcdef", information.id)
                assertEquals("三体", information.title)
                assertEquals("刘慈欣", information.author)
                assertEquals("简介", information.description)
                assertEquals("Chinese · EPUB · 2020 · 2 MB", information.subtitle)
                assertEquals(listOf("Chinese", "EPUB"), information.tags)
                assertEquals("/eapi/book/17/abcdef", f.server.takeRequest().path)
            }
            assertTrue(first.source.getBookInformation("https://other.invalid/book/17").isErr)
            assertEquals(1, first.server.requestCount)
            second.server.enqueue(response(buildJsonObject { put("success", 1); put("book", book(18)) }))
            assertTrue(second.source.getBookInformation("17/abcdef").isErr)
        } }
    }

    @Test fun pagedSearchReturnsBoundMetadataAndRequestsOnlyTheChosenPage() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(response(page(1, 2, 1, 2)))
            f.server.enqueue(response(page(2, null, 3)))
            val registry = WebSourceRegistry()
            registry.register(f.source, ZLibrarySources.METADATA)
            try {
                val search = ExploreRepository(registry).open(ZLibrarySources.ID).get()!!
                assertTrue(search.hasPages)
                val first = search.page(search.types.first(), "三体", 1).first()
                assertEquals(1, f.server.requestCount)
                assertEquals(2, first.nextPage)
                assertEquals("三体", first.books.first().information!!.title)
                assertEquals(first.books.first().bookId, first.books.first().information!!.id)
                assertEquals(ZLibrarySources.ID, SourceBookId.fromStorageKey(first.books.first().bookId).sourceId)
                val second = search.page(search.types.first(), "三体", first.nextPage!!).first()
                assertEquals(2, f.server.requestCount)
                assertNull(second.nextPage)
                assertEquals("3/abcdef", SourceBookId.fromStorageKey(second.books.single().bookId).remoteId)
            } finally { registry.unregister(ZLibrarySources.ID) }
        }
    }

    @Test fun emptyNetworkNonJsonAuthenticationAndRateLimitStayDistinct() = runBlocking {
        Fixture().use { f ->
            val mode = f.source.searchProvider.searchTypes.first()
            f.server.enqueue(response(page(1, null)))
            val empty = f.source.searchProvider.search(mode, "empty").toList()
            assertTrue(empty.first() is SearchResult.Empty)
            val cases = listOf(
                MockResponse().setResponseCode(429).setBody("slow down") to DiscoveryError.RateLimited,
                MockResponse().setResponseCode(401).setBody("sign in") to DiscoveryError.AuthenticationRequired,
                MockResponse().setResponseCode(200).setBody("<html>private response</html>") to DiscoveryError.InvalidResponse,
                MockResponse().setResponseCode(503).setBody("upstream unavailable") to DiscoveryError.Network,
                MockResponse().setBody("""{"success":0,"error":{"message":"Please login; private response"}}""") to DiscoveryError.AuthenticationRequired,
                MockResponse().setBody("""{"success":0,"error":"rate limit exceeded"}""") to DiscoveryError.RateLimited,
                MockResponse().setBody("""{"success":1,"books":{},"pagination":{}}""") to DiscoveryError.InvalidResponse,
                MockResponse().setBody("""{"success":{},"books":[]}""") to DiscoveryError.InvalidResponse,
            )
            for ((response, expected) in cases) {
                f.server.enqueue(response)
                val event = f.source.searchProvider.search(mode, "test").toList().single() as SearchResult.Error
                assertEquals(expected, searchFailure(event.error).error)
                assertFalse(event.error.toString().contains("private response"))
            }
        }
    }

    @Test fun badAndRepeatedCursorsCannotLoopOrMasqueradeAsNoResults() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(response(page(1, 2, 1)))
            f.server.enqueue(response(page(2, 3, 1)))
            val events = f.source.searchProvider.search(f.source.searchProvider.searchTypes.first(), "test").toList()
            assertEquals(1, events.filterIsInstance<SearchResult.MultipleBook>().size)
            assertEquals(DiscoveryError.InvalidResponse, searchFailure((events.last() as SearchResult.Error).error).error)
            f.server.enqueue(response(page(1, 1, 2)))
            val failure = runCatching { f.client.search("test", 1) }.exceptionOrNull() as SourceRequestException
            assertEquals(DiscoveryError.InvalidResponse, failure.error)
            assertEquals(3, f.server.requestCount)
        }
    }

    @Test fun apiAndCoverOriginsRequireSeparateApprovalAndOnlyApprovedImagesReachTheServer() = runBlocking {
        Fixture().use { f -> MockWebServer().use { cdn ->
            cdn.start()
            val image = cdn.url("/cover.jpg?signature=not-for-display").toString()
            val failure = runCatching { f.client.image(image) }.exceptionOrNull() as SourceRequestException
            assertEquals(DiscoveryError.PermissionDenied, failure.error)
            assertEquals(OriginDenial(sourceOrigin(image)!!, ResourceKind.Image), failure.denial)
            assertEquals(0, cdn.requestCount)
            val missing = ZLibraryClient(f.session, "https://unapproved.invalid/")
            val apiFailure = runCatching { missing.search("test", 1) }.exceptionOrNull() as SourceRequestException
            assertEquals(ResourceKind.Api, apiFailure.denial!!.kind)
            SourceBroker(folder.newFolder().toPath()).use { broker ->
                val session = broker.open(SourceScope("builtin", "zlibrary", "zlibrary-eapi"), listOf(NetworkGrant(cdn.url("/").toString(), true)))
                cdn.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
                assertArrayEquals(byteArrayOf(1, 2, 3), ZLibraryClient(session, f.server.url("/").toString()).image(image))
                assertEquals(1, cdn.requestCount)
            }
        } }
    }

    @Test fun sourceRetirementCancelsPendingHttpWithoutReportingAnEmptySearch() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val registry = WebSourceRegistry()
            registry.register(f.source, ZLibrarySources.METADATA)
            try {
                val runtime = (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime
                val events = mutableListOf<SearchResult>()
                val pending = launch { runtime.search.search(runtime.search.searchTypes.first(), "waiting").collect { events += it } }
                withContext(Dispatchers.IO) { assertNotNull(f.server.takeRequest(5, TimeUnit.SECONDS)) }
                registry.unregister(ZLibrarySources.ID)
                withTimeout(5000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertTrue(events.isEmpty())
                assertFalse(runtime.isAvailable)
            } finally { registry.unregister(ZLibrarySources.ID) }
        }
    }
}
