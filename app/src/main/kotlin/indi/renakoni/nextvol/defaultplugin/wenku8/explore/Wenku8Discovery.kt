package indi.renakoni.nextvol.defaultplugin.wenku8.explore

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8RouteUnavailableException
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Stateless adapter. The injected fetcher owns credentials, decoding and cancellation. */
class Wenku8Discovery(private val host: String, private val fetch: suspend (String) -> Document) : DiscoveryPreviewProvider {
    override val hasFeed = true
    override val hasCategories = true

    override suspend fun feed() = feedUpdates().last()

    override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
        val home = request { Ok(Wenku8DiscoveryParser.home(fetch(host), host)) }
            .getOrElse { emit(Err(it)); return@flow }.map { section ->
            section.copy(more = lists.entries.firstOrNull { it.value == section.title }?.key)
        }
        // Keep homepage order; a matching list gets a more target, not a second section.
        val pending = lists.filterKeys { id -> home.none { it.more == id } }.toList()
        val sections = (home + pending.map { (id, title) ->
            DiscoverySection(id, title, emptyList(), id, previewLoading = true)
        }).toMutableList()
        emit(Ok(sections.toList()))
        coroutineScope {
            val completed = Channel<Pair<Int, DiscoverySection>>(Channel.BUFFERED)
            try {
                pending.forEachIndexed { index, (id, title) ->
                    launch {
                        try {
                            val section = preview(id).getOrElse {
                                DiscoverySection(id, title, emptyList(), id,
                                    previewFailure = DiscoveryPreviewFailure(it), previewLoading = false)
                            }
                            completed.send(index to section)
                        } catch (cancelled: CancellationException) {
                            // A child cancellation alone does not cancel coroutineScope;
                            // wake the collector so it cannot wait for a missing result.
                            completed.cancel(cancelled)
                            throw cancelled
                        }
                    }
                }
                repeat(pending.size) {
                    val (index, section) = completed.receive()
                    sections[home.size + index] = section
                    emit(Ok(sections.toList()))
                }
            } finally { completed.cancel() }
        }
    }

    override suspend fun preview(id: String): Result<DiscoverySection, DiscoveryError> {
        val title = lists[id] ?: return Err(DiscoveryError.InvalidRequest)
        return request {
            Ok(DiscoverySection(id, title, Wenku8DiscoveryParser.cards(fetch(url(id, 1, emptyMap())), host)
                .take(6).map { it.book }, id))
        }
    }

    override suspend fun categories(): Result<List<DiscoveryCategory>, DiscoveryError> = request {
        val document = fetch("$host/modules/article/tags.php")
        try { Ok(Wenku8DiscoveryParser.categories(document)) }
        catch (_: IllegalArgumentException) { Err(DiscoveryError.InvalidResponse) }
    }

    override fun filters(target: String): List<DiscoveryFilter> = buildList {
        add(DiscoveryFilter.Toggle("completed", "仅已完结"))
        if (target.startsWith("tag:")) add(DiscoveryFilter.Choice("sort", "排序", linkedMapOf(
            "default" to "默认", "popular" to "按热度排序", "anime" to "仅动画化",
        ), "default"))
        add(DiscoveryFilter.Choice("publisher", "文库", publishers.associateWith { it }, publishers.first()))
    }

    override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
        val page = request.cursor?.toIntOrNull() ?: if (request.cursor == null) 1 else return Err(DiscoveryError.InvalidRequest)
        if (page < 1 || !validTarget(request.target) || !validFilters(request)) return Err(DiscoveryError.InvalidRequest)
        return request {
            val soup = fetch(url(request.target, page, request.filters))
            val cards = Wenku8DiscoveryParser.cards(soup, host).ifEmpty {
                listOfNotNull(Wenku8DiscoveryParser.singleBook(soup, host))
            }
            val books = cards.filter {
                (request.filters["completed"] != "true" || it.completed) &&
                    (request.filters["publisher"].let { value -> value == null || value == publishers.first() || value == it.publisher })
            }.map { it.book }
            val total = soup.selectFirst("#pagelink em")?.text()?.substringAfter('/')?.trim()?.toIntOrNull()
            if (soup.selectFirst("#pagelink em") != null && (total == null || total < page)) {
                return@request Err(DiscoveryError.InvalidResponse)
            }
            // Empty filtered pages may have a next page; an empty remote page ends the list.
            Ok(DiscoveryPage(books, if (cards.isNotEmpty() && total != null && page < total) (page + 1).toString() else null))
        }
    }

    private fun validTarget(target: String) = target in lists || (target.startsWith("tag:") && target.length > 4)
    private fun validFilters(request: DiscoveryRequest): Boolean {
        val definitions = filters(request.target).associateBy { it.id }
        return request.filters.all { (id, value) -> when (val filter = definitions[id]) {
            is DiscoveryFilter.Choice -> value in filter.options
            is DiscoveryFilter.Toggle -> value == "true" || value == "false"
            is DiscoveryFilter.Number -> value.toIntOrNull()?.let { it in filter.min..filter.max } == true
            is DiscoveryFilter.Text -> value.length <= 4096
            null -> false
        } }
    }

    private fun url(target: String, page: Int, filters: Map<String, String>): String {
        val base = "$host/modules/article"
        return when {
            target == "allBook" -> "$base/articlelist.php?page=$page"
            target == "allCompletedBook" -> "$base/articlelist.php?page=$page&fullflag=1"
            target.startsWith("tag:") -> "$base/tags.php?page=$page&t=${URLEncoder.encode(target.removePrefix("tag:"), "GB2312")}" +
                when (filters["sort"]) { "popular" -> "&v=1"; "anime" -> "&v=3"; else -> "" }
            else -> "$base/toplist.php?page=$page&sort=${target.removeSuffix("Book")}"
        }
    }

    private suspend fun <T> request(block: suspend () -> Result<T, DiscoveryError>): Result<T, DiscoveryError> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Wenku8RouteUnavailableException) {
        Err(DiscoveryError.RouteUnavailable)
    } catch (_: Exception) {
        Err(DiscoveryError.Network)
    }

    companion object {
        val lists: Map<String, String> = linkedMapOf(
            "allBook" to "轻小说列表", "allvisitBook" to "热门轻小说", "animeBook" to "动画化作品",
            "lastupdateBook" to "今日更新", "postdateBook" to "新书一览", "allCompletedBook" to "完结全本",
        )
        private val publishers = listOf("全部轻小说", "电击文库", "富士见文库", "角川文库", "MF文库J", "Fami通文库",
            "GA文库", "HJ文库", "一迅社", "集英社", "小学馆", "讲谈社", "少女文库", "其他文库", "游戏剧本")
    }
}

/** Parse each card as a unit: missing author/cover nodes cannot shift another book's data. */
internal object Wenku8DiscoveryParser {
    data class Card(val book: DiscoveryBook, val publisher: String, val completed: Boolean)
    private val bookPath = Regex("/book/([0-9]+)\\.htm")

    fun cards(soup: Document?, host: String): List<Card> = soup
        ?.select("#content > table > tbody > tr > td > div")?.mapNotNull { card ->
            val link = card.selectFirst("a[href*=/book/]") ?: return@mapNotNull null
            val id = bookPath.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = card.selectFirst("b > a")?.text()?.substringBefore('(')
                ?: link.attr("title").substringBefore('(')
            val paragraphs = card.select("p").map { it.text() }
            fun field(name: String) = paragraphs.firstOrNull { it.contains(name) }
                ?.substringAfter(name)?.trimStart(':', '：', ' ')?.substringBefore('/')?.trim().orEmpty()
            Card(DiscoveryBook(id, title, field("作者"), cover(card, host)), field("分类").ifEmpty { field("文库") },
                paragraphs.any { it.contains("已完结") || it.contains("已完成") || it.contains("全本") })
        }?.distinctBy { it.book.remoteId }.orEmpty()

    fun home(soup: Document?, host: String): List<DiscoverySection> = (0..2).mapNotNull { index ->
        val block = soup?.selectFirst("#centers > div:nth-child(${index + 2})") ?: return@mapNotNull null
        val title = block.selectFirst("div.blocktitle")?.text()?.substringBefore('(') ?: return@mapNotNull null
        val books = block.select("div.blockcontent > div > div").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/book/]") ?: return@mapNotNull null
            val id = bookPath.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val name = card.select("a[href*=/book/]").lastOrNull { it.text().isNotBlank() }?.text()
                ?.substringBefore('(') ?: link.attr("title")
            DiscoveryBook(id, name, coverUrl = cover(card, host))
        }.distinctBy { it.remoteId }
        DiscoverySection("home:$index", title, books)
    }

    fun categories(soup: Document): List<DiscoveryCategory> = soup.select("a[href*=tags.php]").mapNotNull { link ->
        val value = link.attr("href").substringAfter('?', "").split('&').firstOrNull { it.startsWith("t=") }
            ?.removePrefix("t=")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val tag = URLDecoder.decode(value, "GB2312")
        DiscoveryCategory("tag:$tag", link.text().ifBlank { tag }, "tag:$tag")
    }.distinctBy { it.id }

    // Wenku8 can redirect a one-book result to the book's information page.
    fun singleBook(soup: Document, host: String): Card? {
        val menu = soup.select("#content a[href]").firstOrNull { it.text().contains("小说目录") } ?: return null
        val id = Regex("/novel/[0-9]+/([0-9]+)/index\\.htm").find(menu.attr("href"))?.groupValues?.get(1) ?: return null
        val info = soup.selectFirst("#content > div > table") ?: return null
        val title = info.selectFirst("span > b")?.text()?.substringBefore('(') ?: return null
        val fields = info.select("tr").lastOrNull()?.select("td").orEmpty().map { it.text().substringAfter('：') }
        return Card(DiscoveryBook(id, title, fields.getOrNull(1).orEmpty(), cover(soup.selectFirst("#content")!!, host)),
            fields.getOrNull(0).orEmpty(),
            fields.getOrNull(2)?.contains("已完结") == true)
    }

    private fun cover(card: Element, host: String): String = card.selectFirst("img[src]")?.attr("src")
        ?.takeIf { it.isNotBlank() }?.let { URI(host).resolve(it).toString() }.orEmpty()
}
