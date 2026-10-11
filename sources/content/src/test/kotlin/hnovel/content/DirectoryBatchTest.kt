package hnovel.content

import hnovel.execution.ExecutionTask
import hnovel.execution.SourceWorkRequest
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class DirectoryBatchTest {
    @Test fun thousandChapterNextChainUsesPageBatchesWithoutDroppingOrReorderingRows() = longDirectory(1001)
    @Test fun nineHundredChapterNextChainUsesPageBatchesWithoutDroppingOrReorderingRows() = longDirectory(936)

    private fun longDirectory(count: Int) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    return MockResponse().setBody(buildString {
                        for (index in ((page - 1) * 50 + 1)..minOf(page * 50, count))
                            append("<li><a href='/c/$index'>Chapter $index</a></li>")
                        if (page * 50 < count) append("<a class='next' href='/toc/${page + 1}'>Next</a>")
                    })
                }
            }
            var tasks = 0
            var fields = 0
            fixture.beforeRun = { task, _ ->
                tasks++
                if (task is ExecutionTask.Rule && task.location.field in listOf("ruleToc.chapterName", "ruleToc.chapterUrl")) fields++
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleToc" to buildJsonObject {
                put("chapterList", "li")
                put("chapterName", "a@text")
                put("chapterUrl", "@js:org.jsoup.Jsoup.parse(result).select('a').attr('href')")
                put("nextTocUrl", "a.next@href")
            })) }).use { source ->
                val started = System.nanoTime()
                val chapters = source.directory(fixture.server.url("/book/one").toString())
                println("directory: chapters=${chapters.size}, tasks=$tasks, fields=$fields, requests=${fixture.server.requestCount}, millis=${(System.nanoTime() - started) / 1_000_000}")
                assertEquals((1..count).map { "Chapter $it" }, chapters.map { it.title })
                assertEquals((1..count).map { fixture.server.url("/c/$it").toString() }, chapters.map { it.id })
                assertEquals(1 + (count + 49) / 50, fixture.server.requestCount)
                assertTrue("Expected bounded page batches, got $tasks tasks / $fields individual fields", tasks < 100)
                assertEquals(0, fields)
            }
        }
    }

    @Test fun chapterAndBookWritesStayOrderedAcrossFieldsRowsAndBatches() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/1") MockResponse().setBody(buildString {
                    for (i in 1..70) append("<li><a href='/c/$i'>${if (i == 5) "" else "Chapter $i"}</a></li>")
                }) else original.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleToc" to buildJsonObject {
                put("chapterList", "li")
                put("chapterName", "a@text@js:book.putVariable('n',String(Number(book.getVariable('n')||0)+1));book.putVariable('temporary','x');chapter.putVariable('large','x'.repeat(11000));chapter.putVariable('saved',result);result")
                put("chapterUrl", "a@href@js:book.putVariable('temporary',null);chapter.title==chapter.getVariable('saved')?result:'bad'")
                put("updateTime", "@js:book.getVariable('n')")
                put("isVip", "@js:chapter.tag==book.getVariable('n')")
                put("isPay", "@js:chapter.url.indexOf('/c/')==0")
            })) }).use { source ->
                val id = fixture.server.url("/book/one").toString()
                val rows = source.directory(id)
                assertEquals((1..70).filter { it != 5 }.map { "Chapter $it" }, rows.map { it.title })
                assertEquals((1..69).map { it.toString() }, rows.map { it.updateTime })
                assertTrue(rows.all { it.isVip && it.isPay && it.title == it.state.variables["saved"] })
                assertEquals("69", source.cachedInformation(id)!!.state.variables["n"])
                assertNull(source.cachedInformation(id)!!.state.variables["temporary"])
                assertTrue(rows.all { it.state.bigVariables["large"]?.length == 11000 })
                assertEquals("Chapter 70", rows.last().state.metadata["title"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun displayInformationDoesNotFetchDirectoryOrSatisfyABackgroundUpdateCheck() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            val queuedUpdate = SourceWorkRequest(0)
            val display = source.information(id, inferUpdate = false)
            assertEquals("Same title", display.title)
            assertEquals(1, fixture.server.requestCount)
            val updated = withContext(queuedUpdate) { source.information(id) }
            assertTrue(updated.observedUpdate >= display.observedUpdate)
            assertEquals(4, fixture.server.requestCount)
            val requests = fixture.server.requestCount
            assertEquals(3, source.directory(id).size)
            assertEquals(requests, fixture.server.requestCount)
        } }
    }

    @Test fun foregroundDirectoryChangesRemainVisibleToLaterBackgroundChecks() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            source.information(id, inferUpdate = false)
            source.directory(id)
            val before = source.cachedInformation(id)!!.observedUpdate
            delay(2)
            fixture.extraChapter = true
            source.directory(id)
            val observed = source.cachedInformation(id)!!.observedUpdate
            assertTrue(observed > before)
            assertEquals(observed, source.information(id).observedUpdate)
        } }
    }

    @Test fun failedFieldKeepsItsDiagnosticAndDoesNotCommitTheBatch() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw +
            ("ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject + mapOf(
                "chapterName" to JsonPrimitive("a@text@js:book.putVariable('pending','uncommitted');result"),
                "chapterUrl" to JsonPrimitive("@js:throw Error('broken')")))))
        }).use { source ->
            val id = fixture.server.url("/book/one").toString()
            val failure = runCatching { source.directory(id) }.exceptionOrNull() as SourceContentException
            assertEquals("ruleToc.chapterUrl", failure.field)
            assertNull(source.cachedInformation(id)!!.state.variables["pending"])
        } }
    }

    @Test fun cancellationAfterWorkerReplyDoesNotSaveChaptersAndRetryStartsCleanly() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            source.information(id, inferUpdate = false)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task -> if (task is ExecutionTask.DirectoryRows) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            } }
            val operation = async { source.directory(id) }
            withTimeout(5000) { entered.await() }
            operation.cancel()
            release.complete(Unit)
            operation.join()
            val definition = source.definition
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val saved = session.read(StorageRequest(StorageArea.BookState, "content/book/" + digest(id))) as StorageResult.Value
            assertTrue(Json.decodeFromString(BookRecord.serializer(), saved.value!!).chapters.isEmpty())
            fixture.afterRun = {}
            assertEquals(listOf("Volume one", "One", "Two"), source.directory(id).map { it.title })
        } }
    }
}
