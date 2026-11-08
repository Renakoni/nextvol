package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class LongCatalogueTest {
    @Test fun shorterRetryKeepsPreviouslyReadableChapters() = shorterRetry()
    @Test fun informationRefreshKeepsPreviouslyReadableChapters() = shorterRetry(refreshInformation = true)
    @Test fun shorterRetryKeepsAReverseCatalogue() = shorterRetry(reverse = true)
    @Test fun changedChapterIdsDoNotReuseAnOlderPartialDirectory() = changedDirectory(tocChanged = false)
    @Test fun changedTocUrlDoesNotReuseAnOlderPartialDirectory() = changedDirectory(tocChanged = true)
    @Test fun changedTocUrlWithoutPrefetchDoesNotReuseAnOlderPartialDirectory() = changedDirectory(tocChanged = true, metadataAvailable = true)

    private fun shorterRetry(refreshInformation: Boolean = false, reverse: Boolean = false) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val baseRules = catalogue(fixture, chapters = 6, perPage = 2)
            val rules = { raw: JsonObject ->
                val definition = baseRules(raw)
                if (reverse) JsonObject(definition + ("ruleToc" to JsonObject(definition.getValue("ruleToc").jsonObject +
                    ("chapterList" to JsonPrimitive("-li"))))) else definition
            }
            val original = fixture.server.dispatcher
            var failedPage = 3
            var status = 502
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/$failedPage")
                    MockResponse().setResponseCode(status) else original.dispatch(request)
            }
            val id = fixture.server.url("/book/one").toString()
            val chapterId = fixture.server.url("/c/3").toString()
            fixture.source(customize = rules).use { source ->
                val first = runCatching { source.directory(id) }.exceptionOrNull() as PartialDirectoryException
                assertEquals(4, first.chapters.size)
                assertEquals(502, first.httpStatus)
                assertTrue(source.content(id, chapterId).parts.any { it.text == "Body 3" })
                failedPage = 2
                status = 503
                if (refreshInformation) source.information(id) else {
                    val retried = runCatching { source.directory(id) }.exceptionOrNull() as PartialDirectoryException
                    assertEquals(first.chapters.map { it.id }, retried.chapters.map { it.id })
                    assertEquals(503, retried.httpStatus)
                    assertEquals(503, retried.failure.httpStatus)
                }
                val content = source.content(id, chapterId)
                assertTrue(content.parts.any { it.text == "Body 3" })
                assertTrue(content.parts.any { it.text == "Chapter 3" })
            }
            fixture.source(customize = rules).use { source ->
                val requests = fixture.server.requestCount
                assertTrue(source.content(id, chapterId).parts.any { it.text == "Chapter 3" })
                assertEquals(requests + 1, fixture.server.requestCount)
                val failure = runCatching { source.directory(id) }.exceptionOrNull() as PartialDirectoryException
                assertEquals(4, failure.chapters.size)
                failedPage = 0
                assertEquals(6, source.directory(id).size)
                assertTrue(source.content(id, fixture.server.url("/c/6").toString()).parts.any { it.text == "Body 6" })
            }
        }
    }

    private fun changedDirectory(tocChanged: Boolean, metadataAvailable: Boolean = false) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val baseRules = catalogue(fixture, chapters = 6, perPage = 2)
            val rules = { raw: JsonObject ->
                val definition = baseRules(raw)
                if (metadataAvailable) JsonObject(definition + ("ruleBookInfo" to JsonObject(definition.getValue("ruleBookInfo").jsonObject +
                    ("lastChapter" to JsonPrimitive("h2@text"))))) else definition
            }
            val original = fixture.server.dispatcher
            var changed = false
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/toc/3") return MockResponse().setResponseCode(502)
                    if (!changed) return original.dispatch(request)
                    if (tocChanged && request.path == "/book/one") return MockResponse().setBody(
                        "<h1>Same title</h1><b>Same author</b><h2>Latest</h2><a class='toc' href='/other/1'>toc</a>")
                    val prefix = if (tocChanged) "/other" else "/toc"
                    if (request.path == "$prefix/2") return MockResponse().setResponseCode(503)
                    if (request.path != "$prefix/1") return original.dispatch(request)
                    return MockResponse().setBody(buildString {
                        for (index in if (tocChanged) 1..2 else 7..8)
                            append("<li><a href='/c/$index'>Chapter $index</a></li>")
                        append("<a class='next' href='$prefix/2'>next</a>")
                    })
                }
            }
            fixture.source(customize = rules).use { source ->
                val id = fixture.server.url("/book/one").toString()
                val first = runCatching { source.directory(id) }.exceptionOrNull() as PartialDirectoryException
                assertEquals(4, first.chapters.size)
                changed = true
                if (tocChanged) {
                    val requests = fixture.server.requestCount
                    val information = source.information(id)
                    if (metadataAvailable) {
                        assertEquals("Latest", information.latestChapter)
                        assertEquals(requests + 1, fixture.server.requestCount)
                    }
                }
                val failure = runCatching { source.directory(id) }.exceptionOrNull() as PartialDirectoryException
                val expected = if (tocChanged) listOf("Chapter 1", "Chapter 2") else listOf("Chapter 7", "Chapter 8")
                assertEquals(expected, failure.chapters.map { it.title })
                assertEquals(503, failure.httpStatus)
                assertTrue(source.content(id, failure.chapters.first().id).parts.any { it.text == expected.first() })
            }
        }
    }

    @Test fun failedLaterPageKeepsEarlierChaptersReadableAndCanBeRetried() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = catalogue(fixture, chapters = 3, perPage = 2)
            val original = fixture.server.dispatcher
            var failSecondPage = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (failSecondPage && request.path == "/toc/2") MockResponse().setResponseCode(502)
                    else original.dispatch(request)
            }
            fixture.source(customize = rules).use { source ->
                val bookId = fixture.server.url("/book/one").toString()
                try {
                    source.directory(bookId)
                    fail("An incomplete catalogue must not be returned as a successful directory")
                } catch (failure: SourceContentException) {
                    assertEquals(ContentError.Network, failure.code)
                    assertEquals(502, failure.httpStatus)
                    assertEquals(listOf("Chapter 1", "Chapter 2"), (failure as PartialDirectoryException).chapters.map { it.title })
                }
                val requests = fixture.server.requestCount
                val chapter = source.content(bookId, fixture.server.url("/c/1").toString())
                assertEquals(requests + 1, fixture.server.requestCount)
                assertTrue(chapter.parts.any { it.text == "Body 1" })
                assertTrue(chapter.parts.any { it.text == "Chapter 1" })
                failSecondPage = false
                assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), source.directory(bookId).map { it.title })
            }
        }
    }

    @Test fun partialSnapshotsRemainIncompleteAfterReadingAndReopening() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val baseRules = catalogue(fixture, chapters = 3, perPage = 2)
            val rules = { raw: JsonObject -> baseRules(raw).let { definition -> JsonObject(definition +
                ("ruleContent" to JsonObject(definition.getValue("ruleContent").jsonObject +
                    ("nextContentUrl" to JsonPrimitive("a.next@href"))))) } }
            val original = fixture.server.dispatcher
            var failSecondPage = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    failSecondPage && request.path == "/toc/2" -> MockResponse().setResponseCode(502)
                    request.path == "/c/2" -> MockResponse().setBody("<article>Body 2</article><a class='next' href='/c/3'>next</a>")
                    else -> original.dispatch(request)
                }
            }
            val id = fixture.server.url("/book/one").toString()
            fixture.source(customize = rules).use { source ->
                assertTrue(runCatching { source.directory(id) }.exceptionOrNull() is PartialDirectoryException)
                assertTrue(source.content(id, fixture.server.url("/c/1").toString()).parts.any { it.text == "Body 1" })
            }
            fixture.source(customize = rules).use { source ->
                val requests = fixture.server.requestCount
                assertTrue(source.content(id, fixture.server.url("/c/1").toString()).parts.any { it.text == "Body 1" })
                assertEquals(requests + 1, fixture.server.requestCount)
                val failure = runCatching { source.content(id, fixture.server.url("/c/2").toString()) }.exceptionOrNull()
                assertEquals("ruleToc.nextTocUrl", (failure as SourceContentException).field)
                assertEquals(requests + 2, fixture.server.requestCount)
                assertTrue(runCatching { source.directory(id) }.exceptionOrNull() is PartialDirectoryException)
                failSecondPage = false
                assertEquals(3, source.directory(id).size)
                assertTrue(source.content(id, fixture.server.url("/c/2").toString()).parts.any { it.text == "Body 2" })
            }
        }
    }

    @Test fun laterPageFailureDoesNotReplaceAnExistingCompleteSnapshotOrFailBookInformation() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = catalogue(fixture, chapters = 3, perPage = 2)
            val original = fixture.server.dispatcher
            val id = fixture.server.url("/book/one").toString()
            fixture.source(customize = rules).use { source ->
                source.information(id)
                assertEquals(3, source.directory(id).size)
                val update = source.cachedInformation(id)!!.observedUpdate
                fixture.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/2")
                        MockResponse().setResponseCode(502) else original.dispatch(request)
                }
                assertTrue(runCatching { source.directory(id) }.exceptionOrNull() is PartialDirectoryException)
                assertEquals(update, source.information(id).observedUpdate)
                assertTrue(source.content(id, fixture.server.url("/c/3").toString()).parts.any { it.text == "Body 3" })
            }
        }
    }

    @Test fun firstPageFailureDoesNotClaimToHaveAPartialDirectory() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/1")
                    MockResponse().setResponseCode(502) else original.dispatch(request)
            }
            fixture.source().use { source ->
                val failure = runCatching { source.directory(fixture.server.url("/book/one").toString()) }.exceptionOrNull()
                assertTrue(failure is SourceContentException)
                assertFalse(failure is PartialDirectoryException)
            }
        }
    }

    @Test fun legacySnapshotsRemainReadableAndMoveOnlyAfterASuccessfulWrite() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val id = fixture.server.url("/book/one").toString()
            val definition = fixture.source().use { source ->
                source.search("title")
                source.directory(id)
                source.definition
            }
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val key = "content/book/" + digest(id)
            val snapshot = session.read(StorageRequest(StorageArea.BookState, key)) as StorageResult.Value
            assertNotNull(snapshot.value)
            assertEquals(snapshot, session.write(StorageRequest(StorageArea.Config, key, snapshot.value)))
            session.write(StorageRequest(StorageArea.BookState, key))
            fixture.source().use { source ->
                val requests = fixture.server.requestCount
                val content = source.content(id, fixture.server.url("/c/2").toString())
                assertEquals(requests + 1, fixture.server.requestCount)
                assertTrue(content.parts.any { it.text == "from-search:Two" })
            }
            assertNotNull((session.read(StorageRequest(StorageArea.BookState, key)) as StorageResult.Value).value)
            assertEquals(StorageResult.Value(null), session.read(StorageRequest(StorageArea.Config, key)))
        }
    }

    @Test fun aCatalogueCanContinueBeyondSixtyFourPages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = catalogue(fixture, chapters = 69, perPage = 1)
            fixture.source(customize = rules).use { source ->
                val chapters = source.directory(fixture.server.url("/book/one").toString())
                assertEquals(69, chapters.size)
                assertEquals("Chapter 69", chapters.last().title)
                assertEquals(fixture.server.url("/c/69").toString(), chapters.last().id)
            }
        }
    }

    @Test fun thousandsOfJsonChapterObjectsCanCrossTheWorkerBoundaryInOnePage() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val count = 7501
            val body = buildJsonObject {
                put("list", buildJsonArray {
                    add(buildJsonObject {
                        put("bookChapters", buildJsonArray {
                            for (index in 1..count) add(buildJsonObject {
                                put("name", "Chapter $index")
                                put("url", "/c/$index")
                                put("updated", "day-$index")
                                put("metadata", "metadata-".repeat(40))
                            })
                        })
                    })
                })
            }.toString()
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/1")
                    MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                else original.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleToc" to buildJsonObject {
                put("chapterList", "$.list..bookChapters[*]")
                put("chapterName", "$.name")
                put("chapterUrl", "$.url")
                put("updateTime", "$.updated")
            })) }).use { source ->
                val chapters = source.directory(fixture.server.url("/book/one").toString())
                assertEquals(count, chapters.size)
                assertEquals((1..count).map { "Chapter $it" }, chapters.map { it.title })
                assertEquals(fixture.server.url("/c/$count").toString(), chapters.last().id)
                assertEquals("day-$count", chapters.last().updateTime)
            }
        }
    }

    private fun catalogue(fixture: RuleSourceFixture, chapters: Int, perPage: Int): (JsonObject) -> JsonObject {
        val original = fixture.server.dispatcher
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/c/")) return MockResponse().setBody("<article><p>Body ${path.substringAfterLast('/')}</p></article>")
                if (!path.startsWith("/toc/")) return original.dispatch(request)
                val page = path.substringAfterLast('/').toInt()
                val start = (page - 1) * perPage + 1
                return MockResponse().setBody(buildString {
                    for (index in start..minOf(chapters, start + perPage - 1))
                        append("<li data-updated='today'><a href='/c/$index'>Chapter $index</a></li>")
                    if (start + perPage <= chapters) append("<a class='next' href='/toc/${page + 1}'>next</a>")
                })
            }
        }
        return { raw -> JsonObject(raw + mapOf(
            "ruleToc" to buildJsonObject {
                put("chapterList", "li")
                put("chapterName", "a@text@js:chapter.putVariable('chapterKey',result);result")
                put("chapterUrl", "a@href")
                put("updateTime", "@data-updated")
                put("isVolume", "@data-volume")
                put("isVip", "@data-vip")
                put("isPay", "@data-pay")
                put("formatJs", "title")
                put("nextTocUrl", "a.next@href")
            },
            "ruleContent" to buildJsonObject {
                put("content", "article@html@js:result+'<p>'+chapter.getVariable('chapterKey')+'</p>'")
            }
        )) }
    }
}
