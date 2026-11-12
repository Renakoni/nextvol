package indi.renakoni.nextvol.defaultplugin.wenku8.book

import androidx.core.net.toUri
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.coroutineBinding
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.mapError
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.data.book.UNKNOWN_BOOK_UPDATE_TIME
import indi.renakoni.nextvol.data.web.requestErrorKind
import hnovel.content.parseBookUpdateTime
import hnovel.content.parseBookWordCount
import indi.renakoni.nextvol.data.explore.SearchPage
import indi.renakoni.nextvol.data.web.SourceRequestException
import indi.renakoni.nextvol.data.web.SourceRequestOwner
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import indi.renakoni.nextvol.utils.network.selectFirstXpath
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Wenku8WebsiteDataSource(
    val host: String,
    val wenku8Api: Wenku8Api
): Wenku8BookDataSource {
    private val searchLock = Mutex()
    private var nextSearchAt = 0L
    private data class DirectoryKey(val owner: Identifier?, val host: String, val bookId: String)
    private val titleRegex = Regex("(.*) ?[(（](.*)[)）] ?$")
    private fun url(string: String) = "$host/$string"

    private fun <T> Result<T, Throwable>.requestError() = mapError { failure ->
        val content = failure as? hnovel.content.SourceContentException
        val kind = content?.requestErrorKind() ?: WebRequestErrorKind.Other
        WebRequestError("网络请求失败", "网络请求时出现了错误", failure, kind)
    }

    override suspend fun getBookInformation(id: String): Result<BookInformation, WebRequestError> = coroutineBinding {
        val soup = wenku8Api.getWithWenku8Cookie(url("book/$id.htm"))
            .requestError()
            .bind()
        val titleGroup = soup
            .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[1]/td/table/tbody/tr/td[1]/span/b")
            ?.text()
            ?.let { titleRegex.find(it)?.groups }
        val title = titleGroup
            ?.get(1)?.value
            ?: soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[1]/td/table/tbody/tr/td[1]/span/b")
                ?.text()
            ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind()
        if (soup.text().contains("因版权问题")) Err(WebRequestError("版权错误", "由于「$title」为Wenku8上具有版权文件的书籍的章节, 我们无法提供其数据")).bind()
        return@coroutineBinding BookInformation(
            id = id,
            title = titleGroup
                ?.get(1)?.value
                ?: soup
                    .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[1]/td/table/tbody/tr/td[1]/span/b")
                    ?.text()
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            subtitle = titleGroup
                ?.get(2)
                ?.value
                ?: "",
            coverUri = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[1]/img")
                ?.attr("src")
                ?.toUri()
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            author = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[2]")
                ?.text()
                ?.replace("小说作者：", "")
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            description = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[2]/span[6]")
                ?.text()
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            tags = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[2]/tbody/tr/td[2]/span[1]/b")
                ?.text()
                ?.replace("作品Tags：", "")
                ?.split(" ")
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            publishingHouse = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[1]")
                ?.text()
                ?.replace("文库分类：", "")
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind(),
            wordCount = WordCount(soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[5]")
                ?.text()
                ?.replace("全文长度：", "")
                ?.let(::parseBookWordCount) ?: 0),
            lastUpdated = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[4]")
                ?.text()
                ?.replace("最后更新：", "")
                ?.let(::parseBookUpdateTime) ?: UNKNOWN_BOOK_UPDATE_TIME,
            isComplete = soup
                .selectFirstXpath("//*[@id=\"content\"]/div[1]/table[1]/tbody/tr[2]/td[3]")
                ?.text()
                ?.contains("已完结")
                ?: Err(WebRequestError("解析错误", "无法解析该书本的信息(id=$id)")).bind()
        )
    }

    override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> = coroutineBinding {
        val soup = wenku8Api.getWithWenku8Cookie(url("novel/${id.toInt() / 1000}/$id/index.htm"))
            .requestError()
            .bind()
        val trs = soup.selectXpath("/html/body/table/tbody/tr")
        val volumes = mutableListOf<Volume>()
        var volume: Volume? = null
        var chapters = mutableListOf<ChapterInformation>()
        trs.forEach { tr ->
            if (tr.selectFirst("td")?.attr("class") == "vcss") {
                volume?.let(volumes::add)
                val td = tr.selectFirst("td")
                val vId = td?.attr("vid") ?: Err(WebRequestError("解析错误", "无法解析该书本的目录(id=$id)")).bind()
                val title = td.text().ifEmpty { Err(WebRequestError("解析错误", "无法解析该书本的目录(id=$id)")).bind() }
                chapters = mutableListOf()
                volume = Volume(vId, title, chapters)
                return@forEach
            }
            for (td in tr.select("td > a")) {
                val id = td
                    .attr("href")
                    .split(".")
                    .firstOrNull()
                    ?: Err(WebRequestError("解析错误", "无法解析该书本的目录(id=$id)")).bind()
                val title = td.text().ifEmpty { Err(WebRequestError("解析错误", "无法解析该书本的目录(id=$id)")).bind() }
                chapters.add(ChapterInformation(id, title))
            }
        }
        volume?.let(volumes::add)
        val result = BookVolumes(id, volumes)
        val key = DirectoryKey(currentCoroutineContext()[SourceRequestOwner]?.id, host, id)
        synchronized(wenku8Api.cache) { wenku8Api.cache.cache(key, result) }
        return@coroutineBinding result
    }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String
    ): Result<ChapterContent, WebRequestError> = coroutineBinding {
        val soup = wenku8Api.getWithWenku8Cookie(url("novel/${bookId.toInt() / 1000}/$bookId/$chapterId.htm"))
            .requestError()
            .bind()
        val title = soup.selectFirstXpath("//*[@id=\"title\"]")?.text() ?: Err(WebRequestError("解析错误", "无法解析该章节的标题(id=$chapterId)")).bind()
        if (soup.text().contains("因版权问题")) Err(WebRequestError("版权错误", "由于「$title」为Wenku8上具有版权文件的书籍的章节, 我们无法提供其数据")).bind()
        val content = soup.selectFirstXpath("//*[@id=\"content\"]") ?: Err(WebRequestError("解析错误", "无法解析该章节的内容(id=$chapterId)")).bind()
        val jsonObject = ContentBuilder().apply {
            var text = ""
            for (node in content.childNodes()) {
                when (node) {
                    is TextNode -> text += node.wholeText.replace(" ", "  ")
                    is Element if node.`is`("div.divimage") -> {
                        simpleText(text)
                        text = ""
                        node
                            .selectFirst("img")
                            ?.attr("src")
                            ?.toUri()
                            ?.let(::image)
                    }
                }
            }
            if (text.isNotEmpty()) {
                simpleText(text)
            }
        }.build()
        val prevChapter = soup.selectFirstXpath("//*[@id=\"foottext\"]/a[3]").let {
            it ?: return@let null
            if (it.attr("href") == "index.htm" || it.attr("href").contains("article")) null
            else it.attr("href").split(".").firstOrNull()
        }
        val nextChapter = soup.selectFirstXpath("//*[@id=\"foottext\"]/a[4]").let {
            it ?: return@let null
            if (it.attr("href") == "index.htm" || it.attr("href").contains("article")) null
            else it.attr("href").split(".").firstOrNull()
        }
        val key = DirectoryKey(currentCoroutineContext()[SourceRequestOwner]?.id, host, bookId)
        val bookVolumes = synchronized(wenku8Api.cache) {
            wenku8Api.cache.getCache<BookVolumes>(key)
        } ?: getBookVolumes(bookId).get()
        val neoTitle = bookVolumes?.volumes?.firstNotNullOfOrNull { volume ->
            volume.chapters.firstOrNull { it.id == chapterId }?.title
        }
        return@coroutineBinding ChapterContent(
            id = chapterId,
            title = neoTitle ?: title,
            content = jsonObject,
            prevChapter = prevChapter,
            nextChapter = nextChapter
        )
    }

    internal suspend fun searchPage(searchType: String, keyword: String, page: Int): SearchPage =
        requestSearchPage(searchType, keyword, page).page

    private data class SearchResponse(val page: SearchPage, val directBook: String? = null)

    private suspend fun requestSearchPage(searchType: String, keyword: String, page: Int): SearchResponse {
        val encodedKeyword = URLEncoder.encode(keyword, "gb2312")
        while (true) {
            currentCoroutineContext().ensureActive()
            val soup = searchLock.withLock {
                delay((nextSearchAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                nextSearchAt = android.os.SystemClock.elapsedRealtime() + 5_000
                wenku8Api.getWithWenku8Cookie(
                    url("modules/article/search.php?searchtype=$searchType&searchkey=$encodedKeyword&page=$page")
                ).getOrElse { throw it }
            }
            if (soup.text().contains("错误原因：对不起，两次搜索的间隔时间不得少于 5 秒")) {
                delay(5.seconds)
                continue
            }
            val menu = soup.selectFirstXpath("//*[@id=\"content\"]/div[1]/div[4]/div/span[1]/fieldset/div/a")
            if (menu != null && menu.text().contains("小说目录")) {
                val id = menu.attr("href").split("/").getOrNull(3)
                    ?: throw SourceRequestException(DiscoveryError.InvalidResponse)
                return SearchResponse(SearchPage(listOf(SearchResult.MultipleBook(id)), null), id)
            }
            val lastPage = soup.selectFirstXpath("//*[@id=\"pagelink\"]/em")?.text()?.split("/")
                ?.getOrNull(1)?.toIntOrNull() ?: throw SourceRequestException(DiscoveryError.InvalidResponse)
            val books = wenku8Api.getBookInformationListFromBookCards(
                soup.selectXpath("//*[@id=\"content\"]/table/tbody/tr/td/div")
            ).map { (id, information) ->
                synchronized(wenku8Api.cache) { wenku8Api.cache.cache(id.hashCode(), information) }
                SearchResult.MultipleBook(id, information.get())
            }
            return SearchResponse(SearchPage(books, if (books.isNotEmpty() && page < lastPage) page + 1 else null))
        }
    }

    override fun search(searchType: String, keyword: String): Flow<SearchResult> = flow {
        var page: Int? = 1
        val seen = hashSetOf<String>()
        while (page != null) {
            val response = requestSearchPage(searchType, keyword, page)
            if (response.directBook != null) {
                emit(SearchResult.SingleBook(response.directBook))
                return@flow
            }
            val additions = response.page.books.filter { seen.add(it.bookId) }
            additions.forEach { emit(it) }
            if (additions.isEmpty()) break
            page = response.page.nextPage
            if (page != null) delay(5.seconds)
        }
        if (seen.isEmpty()) emit(SearchResult.Empty())
        emit(SearchResult.End())
    }.catch { error ->
        currentCoroutineContext().ensureActive()
        emit(SearchResult.Error(error))
    }.flowOn(Dispatchers.IO)
}
