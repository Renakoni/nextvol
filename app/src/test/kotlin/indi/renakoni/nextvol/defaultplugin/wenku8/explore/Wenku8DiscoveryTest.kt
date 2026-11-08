package indi.renakoni.nextvol.defaultplugin.wenku8.explore

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Wenku8DiscoveryTest {
    private val host = "https://fixture.invalid"
    private fun cards(count: Int, page: String = "1/2") = """
        <div id="content"><table class="grid"><tr><td>${(1..count).joinToString("") { id -> """
          <div><div><a href="/book/$id.htm" title="Book $id"><img src="/cover/$id.jpg"></a></div>
          <div><b><a href="/book/$id.htm">Book $id</a></b><p>作者:Author $id/分类:电击文库</p>
          <p>更新:2026-09-09/字数:120K/已完结</p></div></div>
        """ }}</td></tr></table></div><div id="pagelink"><em>$page</em></div>
    """
    private fun home(titles: List<String>) = """<div id="centers"><div></div>${titles.joinToString("") {
        """<div><div class="blocktitle">$it</div><div class="blockcontent"><div><div>
        <a href="/book/99.htm"><img src="/99.jpg"></a><br><a href="/book/99.htm">Home book</a>
        </div></div></div></div>"""
    }}</div>"""

    @Test fun feedPreservesHomeOrderAndAllSixTargetsWithBoundedRequests() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = Wenku8Discovery(host) { url -> calls.add(url); Jsoup.parse(if (url == host) home(listOf("A", "B", "C")) else cards(9)) }
        val feed = provider.feed().get()!!
        assertEquals(listOf("home:0", "home:1", "home:2") + Wenku8Discovery.lists.keys, feed.map { it.id })
        assertEquals(listOf("A", "B", "C"), feed.take(3).map { it.title })
        assertEquals(Wenku8Discovery.lists.keys.toList(), feed.drop(3).map { it.more })
        assertTrue(feed.drop(3).all { it.books.size == 6 })
        assertEquals(7, calls.size)
        assertTrue(calls.none { "tags.php" in it })
        assertEquals("99", feed.first().books.single().remoteId)
        assertEquals("$host/99.jpg", feed.first().books.single().coverUrl)
        assertTrue(calls.last().endsWith("page=1&fullflag=1"))
    }

    @Test fun supplementalListsOverlapWithoutChangingOrder() = runBlocking {
        withTimeout(2000) {
            val started = Channel<Unit>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val provider = Wenku8Discovery(host) { url ->
                if (url == host) Jsoup.parse(home(listOf("Home"))) else {
                    started.send(Unit)
                    release.await()
                    Jsoup.parse(cards(2))
                }
            }
            val feed = async { provider.feed().get()!! }
            repeat(2) { started.receive() }
            release.complete(Unit)
            assertEquals(listOf("home:0") + Wenku8Discovery.lists.keys, feed.await().map { it.id })
        }
    }

    @Test fun duplicateHomepageListGetsMoreTargetWithoutAnotherRequest() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = Wenku8Discovery(host) { url -> calls.add(url); Jsoup.parse(if (url == host) home(listOf("热门轻小说")) else cards(2)) }
        val feed = provider.feed().get()!!
        assertEquals("allvisitBook", feed.first().more)
        assertEquals(1, feed.count { it.title == "热门轻小说" })
        assertEquals(6, calls.size)
        assertTrue(calls.none { "sort=allvisit" in it })
    }

    @Test fun emptyAndShortListsNeverIndexParallelArrays() = runBlocking {
        for (count in listOf(0, 1, 3, 5)) {
            val provider = Wenku8Discovery(host) { Jsoup.parse(cards(count)) }
            assertEquals(count, provider.page(DiscoveryRequest("allBook")).get()!!.books.size)
            assertTrue(provider.feed().get()!!.all { it.books.size == count })
        }
        val malformed = cards(2).replace("<img src=\"/cover/1.jpg\">", "")
            .replace("<p>作者:Author 1/分类:电击文库</p>", "")
        val books = Wenku8Discovery(host) { Jsoup.parse(malformed) }.page(DiscoveryRequest("allBook")).get()!!.books
        assertEquals("", books.first().author)
        assertEquals("", books.first().coverUrl)
        assertEquals("Author 2", books.last().author)
        assertEquals("$host/cover/2.jpg", books.last().coverUrl)
    }

    @Test fun categoryDirectoryDoesNotFetchPreviewsAndClickUsesGb2312() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = Wenku8Discovery(host) { url ->
            calls.add(url)
            Jsoup.parse(if (url.endsWith("tags.php")) """
                <a href="tags.php?t=%D0%A3%D4%B0">校园</a><a href="tags.php?t=%D0%A3%D4%B0">duplicate</a>
                <a href="tags.php?t=奇幻">奇幻</a><a href="tags.php">index</a>
            """ else cards(1))
        }
        val categories = provider.categories().get()!!
        assertEquals(listOf("tag:校园", "tag:奇幻"), categories.map { it.target })
        assertEquals(listOf("$host/modules/article/tags.php"), calls)
        provider.page(DiscoveryRequest(categories.first().target, filters = mapOf("sort" to "popular")))
        assertEquals("$host/modules/article/tags.php?page=1&t=%D0%A3%D4%B0&v=1", calls.last())
        assertEquals(2, calls.size)
    }

    @Test fun emptyCategoryDirectoryAndMalformedTagEncodingAreNotNetworkFailures() = runBlocking {
        assertTrue(Wenku8Discovery(host) { Jsoup.parse("<html></html>") }.categories().get()!!.isEmpty())
        assertEquals(Err(DiscoveryError.InvalidResponse), Wenku8Discovery(host) {
            Jsoup.parse("<a href='tags.php?t=%XX'>Broken tag</a>")
        }.categories())
        assertEquals(Err(DiscoveryError.Network), Wenku8Discovery(host) { throw java.io.IOException() }.categories())
    }

    @Test fun localFilteringRetainsPagingAndSortSemantics() = runBlocking {
        val calls = mutableListOf<String>()
        val provider = Wenku8Discovery(host) { url -> calls.add(url); Jsoup.parse(cards(2, if ("page=2" in url) "2/2" else "1/2")) }
        val base = DiscoveryRequest("tag:校园", filters = mapOf("completed" to "true", "publisher" to "电击文库"))
        assertEquals(2, provider.page(base).get()!!.books.size)
        val filtered = provider.page(base.copy(filters = base.filters + ("publisher" to "角川文库"))).get()!!
        assertTrue(filtered.books.isEmpty())
        assertEquals("2", filtered.nextCursor)
        assertNull(provider.page(base.copy(cursor = "2")).get()!!.nextCursor)
        provider.page(base.copy(filters = mapOf("sort" to "anime")))
        assertTrue(calls.last().endsWith("&v=3"))
        provider.page(base.copy(filters = mapOf("sort" to "default")))
        assertFalse("&v=" in calls.last())
        assertFalse(provider.filters(base.target).any { it.id == "minWords" })
        assertEquals(setOf("default", "popular", "anime"),
            (provider.filters(base.target).single { it.id == "sort" } as DiscoveryFilter.Choice).options.keys)
    }

    @Test fun invalidRequestsDoNotFetchAndMalformedPaginationIsAnError() = runBlocking {
        var calls = 0
        val provider = Wenku8Discovery(host) { calls++; Jsoup.parse(cards(1, "bad")) }
        for (request in listOf(DiscoveryRequest("unknown"), DiscoveryRequest("allBook", "0"),
            DiscoveryRequest("allBook", filters = mapOf("minWords" to "-1")), DiscoveryRequest("allBook", filters = mapOf("sort" to "anime")))) {
            assertEquals(Err(DiscoveryError.InvalidRequest), provider.page(request))
        }
        assertEquals(0, calls)
        assertEquals(Err(DiscoveryError.InvalidResponse), provider.page(DiscoveryRequest("allBook")))
        assertEquals(1, calls)
    }

    @Test fun cancellationIsNotConvertedToNetworkFailure() {
        assertThrows(CancellationException::class.java) { runBlocking {
            Wenku8Discovery(host) { throw CancellationException() }.feed()
        } }
    }

    @Test fun oneCancelledSupplementalRequestTerminatesTheFeedAndCancelsItsSiblings() = runBlocking {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val provider = Wenku8Discovery(host) { url ->
            if (url == host) Jsoup.parse(home(listOf("Home"))) else {
                active.incrementAndGet()
                try {
                    started.send(Unit)
                    if (url.endsWith("articlelist.php?page=1")) {
                        release.await()
                        throw CancellationException("Supplemental request cancelled")
                    }
                    awaitCancellation()
                } finally { active.decrementAndGet() }
            }
        }
        val feed = async { runCatching { provider.feed() }.exceptionOrNull() }
        try {
            withTimeout(5000) { repeat(Wenku8Discovery.lists.size) { started.receive() } }
            release.complete(Unit)
            val failure = withTimeoutOrNull(2000) { feed.await() }
            assertTrue("One cancelled request must terminate the feed without waiting for its siblings",
                failure is CancellationException)
            assertEquals("Supplemental request cancelled", failure?.message)
            assertEquals(0, active.get())
        } finally { release.complete(Unit); feed.cancelAndJoin() }
    }

    @Test fun supplementalFailureKeepsProgressiveContentAndCanRetryIndependently() = runBlocking {
        var fail = true
        val provider = Wenku8Discovery(host) { url ->
            if (fail && url.endsWith("sort=allvisit")) throw java.io.IOException("Unavailable list")
            Jsoup.parse(if (url == host) home(listOf("Home")) else cards(2))
        }
        val snapshots = provider.feedUpdates().toList().map { it.get()!! }
        assertEquals(7, snapshots.size)
        assertEquals("99", snapshots.first().first().books.single().remoteId)
        assertTrue(snapshots.first().drop(1).all { it.previewLoading && it.books.isEmpty() })
        assertTrue(snapshots.all { it.map { section -> section.id } == snapshots.first().map { section -> section.id } })
        val last = snapshots.last()
        assertEquals(listOf("allvisitBook"), last.filter { it.previewFailure != null }.map { it.id })
        assertTrue(last.none { it.previewLoading })
        assertTrue(last.filter { it.id != "allvisitBook" }.all { it.books.isNotEmpty() })
        fail = false
        val retried = provider.preview("allvisitBook").get()!!
        assertNull(retried.previewFailure)
        assertEquals(2, retried.books.size)
    }

    @Test fun singleBookRedirectStillProducesOneBookAndStopsPaging() = runBlocking {
        val provider = Wenku8Discovery(host) { Jsoup.parse("""
            <div id="content"><div><table><tr><td><span><b>Only book</b></span></td></tr>
            <tr><td>文库分类：电击文库</td><td>小说作者：Author</td><td>文章状态：已完结</td>
            <td>最后更新：2026-09-09</td><td>全文长度：120000字</td></tr></table>
            <img src="/only.jpg"><a href="/novel/0/42/index.htm">小说目录</a></div></div>
        """) }
        val page = provider.page(DiscoveryRequest("tag:校园", filters = mapOf("completed" to "true", "publisher" to "电击文库"))).get()!!
        assertEquals("42", page.books.single().remoteId)
        assertEquals("Only book", page.books.single().title)
        assertEquals("Author", page.books.single().author)
        assertNull(page.nextCursor)
    }
}
