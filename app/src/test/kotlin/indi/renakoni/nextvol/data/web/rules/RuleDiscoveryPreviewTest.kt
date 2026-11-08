package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import hnovel.content.createRuleSource
import hnovel.execution.ExecutionTask
import hnovel.network.NetworkGrant
import hnovel.network.SourceScope
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDiscoveryPreviewTest {
    private fun definition(raw: JsonObject, fields: Map<String, JsonElement> = emptyMap()) = JsonObject(raw + mapOf(
        "homepageModules" to JsonPrimitive("""[{"key":"books","type":"ranking","title":"Books","url":"/search"}]"""),
        "ruleExplore" to JsonObject(mapOf(
            "bookList" to JsonPrimitive("@js:java.getElements('li')"),
            "name" to JsonPrimitive("h2@text"), "bookUrl" to JsonPrimitive("a@href"),
            "author" to JsonPrimitive("b@text"), "kind" to JsonPrimitive(".kind@text"),
            "intro" to JsonPrimitive(".intro@text"), "coverUrl" to JsonPrimitive("img@src")
        ) + fields),
        // These metadata fields exist only on the list. Opening a preview must still restore them.
        "ruleBookInfo" to buildJsonObject {
            put("name", "h1@text"); put("lastChapter", ".latest@text")
        }
    ))

    private fun row(id: Int, title: String = "Book $id") =
        "<li><a href='/book/$id'><h2>$title</h2></a><b>Author $id</b>" +
            "<i class='kind'>Kind $id</i><p class='intro'>Intro $id</p><img src='/cover/$id.png'></li>"

    private fun pages(fixture: RuleSourceFixture, list: String = (1..30).joinToString("") { row(it) }) {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/search" -> MockResponse().setBody(list)
                "/book/1" -> MockResponse().setBody("<h1>Book 1</h1><span class='latest'>Latest chapter</span>")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun previewOnlyRunsSixTitlesLinksAndCoversAndDefersMetadataAcrossSourceReopen(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            val fields = mutableListOf<String>()
            val batches = mutableListOf<ExecutionTask.BookOverviews>()
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule) fields += task.location.field
                if (task is ExecutionTask.BookOverviews) batches += task
            }
            var firstId = ""
            fixture.source { definition(it) }.use { source ->
                val section = RuleDiscoveryProvider(source).feed().get()!!.single()
                assertEquals((1..6).map { "Book $it" }, section.books.map { it.title })
                assertEquals((1..6).map { fixture.server.url("/book/$it").toString() }, section.books.map { it.remoteId })
                assertEquals(1, fixture.server.requestCount)
                assertEquals(6, batches.single().inputs.size)
                assertFalse(fields.any { it in listOf("ruleExplore.name", "ruleExplore.bookUrl", "ruleExplore.coverUrl") })
                assertTrue(section.books.all { it.author.isEmpty() })
                assertEquals((1..6).map { fixture.server.url("/cover/$it.png").toString() }, section.books.map { it.coverUrl })
                assertFalse(fields.any { it in listOf("ruleExplore.author", "ruleExplore.kind", "ruleExplore.intro") })
                firstId = section.books.first().remoteId
            }
            fields.clear()
            fixture.source { definition(it) }.use { source ->
                val information = source.information(firstId)
                assertEquals("Book 1", information.title)
                assertEquals("Author 1", information.author)
                assertEquals(listOf("Kind 1"), information.tags)
                assertEquals("Intro 1", information.description)
                assertEquals(fixture.server.url("/cover/1.png").toString(), information.coverUrl)
                assertEquals(2, fixture.server.requestCount)
                assertTrue("List-only metadata is evaluated when the book is opened", "ruleExplore.author" in fields)
                fields.clear()
                source.information(firstId)
                assertFalse("A completed detail must not replay the saved list fragment", fields.any { it.startsWith("ruleExplore.") })
            }
        }
    }

    @Test fun reversePreviewMatchesFullListAfterFilteringBlanksAndDuplicateUrls(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture, (1..7).joinToString("") { row(it) } + row(7, "Last copy") + row(8) + row(9, ""))
            val rules = mapOf("bookList" to JsonPrimitive("-li"))
            fixture.source { definition(it, rules) }.use { source ->
                val preview = RuleDiscoveryProvider(source).feed().get()!!.single().books
                val full = source.openDiscovery("full").openPages("/search", emptyMap()).page(1).books
                assertEquals(listOf("Book 8", "Last copy", "Book 6", "Book 5", "Book 4", "Book 3"), preview.map { it.title })
                assertEquals(full.take(6).map { it.id }, preview.map { it.remoteId })
                assertEquals(full.take(6).map { it.title }, preview.map { it.title })
                assertEquals(8, full.size)
                assertTrue(full.all { it.author.isEmpty() })
                assertEquals((8 downTo 1).map { fixture.server.url("/cover/$it.png").toString() }, full.map { it.coverUrl })
                assertEquals(full.take(6).map { it.coverUrl }, preview.map { it.coverUrl })
                assertTrue(source.search("fixture").all { it.author.isNotEmpty() })
            }
        }
    }

    @Test fun rankingPagesKeepAllBooksAndDeferMetadataUntilOpen(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/search")) {
                        val page = request.path!!.substringAfter("page=", "1").toInt()
                        val body = if (page > 2) "" else ((page - 1) * 30 + 1..page * 30).joinToString("") { row(it) }
                        return MockResponse().setBody(body)
                    }
                    return MockResponse().setBody("<h1>Book 1</h1><span class='latest'>Latest chapter</span>")
                }
            }
            val fields = mutableListOf<String>()
            val batches = mutableListOf<ExecutionTask.BookOverviews>()
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule) fields += task.location.field
                if (task is ExecutionTask.BookOverviews) batches += task
            }
            fixture.source { definition(it) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val request = DiscoveryRequest("/search?page={{page}}")
                val first = provider.page(request).get()!!
                assertEquals((1..30).map { "Book $it" }, first.books.map { it.title })
                assertEquals("2", first.nextCursor)
                val second = provider.page(request.copy(cursor = first.nextCursor)).get()!!
                assertEquals((31..60).map { "Book $it" }, second.books.map { it.title })
                assertTrue((first.books + second.books).all { it.author.isEmpty() })
                assertEquals((1..60).map { fixture.server.url("/cover/$it.png").toString() },
                    (first.books + second.books).map { it.coverUrl })
                assertEquals(60, batches.sumOf { it.inputs.size })
                assertEquals(8, batches.size)
                assertFalse(fields.any { it in listOf("ruleExplore.name", "ruleExplore.bookUrl", "ruleExplore.coverUrl") })
                assertFalse(fields.any { it in listOf("ruleExplore.author", "ruleExplore.kind", "ruleExplore.intro") })
                val end = provider.page(request.copy(cursor = second.nextCursor)).get()!!
                assertTrue(end.books.isEmpty())
                assertNull(end.nextCursor)
                assertEquals(3, fixture.server.requestCount)
                assertEquals("Author 1", source.information(first.books.first().remoteId).author)
                assertEquals(4, fixture.server.requestCount)
            }
        }
    }

    @Test fun shortPreviewRestoresJsonListMetadata(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture, """[{"name":"Book 1","url":"/book/1","author":"JSON author","kind":"JSON kind","intro":"JSON intro"}]""")
            val rules = mapOf("bookList" to JsonPrimitive("$[*]"), "name" to JsonPrimitive("$.name"),
                "bookUrl" to JsonPrimitive("$.url"), "author" to JsonPrimitive("$.author"),
                "kind" to JsonPrimitive("$.kind"), "intro" to JsonPrimitive("$.intro"), "coverUrl" to JsonPrimitive(""))
            fixture.source { definition(it, rules) }.use { source ->
                val preview = RuleDiscoveryProvider(source).feed().get()!!.single().books.single()
                assertEquals("Book 1", preview.title)
                assertEquals("", preview.author)
                val information = source.information(preview.remoteId)
                assertEquals("JSON author", information.author)
                assertEquals(listOf("JSON kind"), information.tags)
                assertEquals("JSON intro", information.description)
            }
        }
    }

    @Test fun listScriptStateAndFallbackTitleSurviveBatchedFieldsAndDetailRestore(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture, (1..10).joinToString("") { row(it, "") })
            var listCalls = 0
            fixture.beforeRun = { task, _ -> if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList") listCalls++ }
            fixture.source { definition(it, mapOf("bookList" to JsonPrimitive(
                "@js:book.name='Inherited title';book.putVariable('shared','from-list');java.getElements('li')"))) }.use { source ->
                val books = RuleDiscoveryProvider(source).feed().get()!!.single().books
                assertEquals(List(6) { "Inherited title" }, books.map { it.title })
                assertEquals(1, listCalls)
                val detail = source.information(books.first().remoteId)
                assertEquals("from-list", detail.state.variables["shared"])
                assertEquals("Author 1", detail.author)
                assertEquals(1, listCalls)
            }
        }
    }

    @Test fun scriptedFieldsKeepUrlDependenciesAndEvaluateTheWholePage(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            var authors = 0
            fixture.beforeRun = { task, _ -> if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.author") authors++ }
            val rules = mapOf("author" to JsonPrimitive("b@text@js:book.putVariable('linkHint','saved');result"),
                "bookUrl" to JsonPrimitive("a@href@js:result+'?hint='+book.getVariable('linkHint')"))
            fixture.source { definition(it, rules) }.use { source ->
                val preview = RuleDiscoveryProvider(source).feed().get()!!.single().books
                assertEquals((1..6).map { fixture.server.url("/book/$it?hint=saved").toString() }, preview.map { it.remoteId })
                assertEquals((1..6).map { "Author $it" }, preview.map { it.author })
                assertEquals((1..6).map { fixture.server.url("/cover/$it.png").toString() }, preview.map { it.coverUrl })
                assertEquals(30, authors)
                assertEquals("saved", source.information(preview.first().remoteId).state.variables["linkHint"])
            }
        }
    }

    @Test fun dynamicTemplatesAndPutExpressionsAreNotSkipped(): Unit = runBlocking {
        for (rule in listOf("b@text<JS>result</JS>", "b@text@JS:result", "{{java.getString('b@text')}}",
            "@put:{'saved':'b@text'}b@text", "@get:{saved}")) RuleSourceFixture().use { fixture ->
            pages(fixture)
            var authors = 0
            fixture.beforeRun = { task, _ -> if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.author") authors++ }
            fixture.source { definition(it, mapOf("author" to JsonPrimitive(rule))) }.use { source ->
                assertEquals(6, RuleDiscoveryProvider(source).feed().get()!!.single().books.size)
                assertEquals(rule, 30, authors)
            }
        }
    }

    @Test fun savedListFragmentsDoNotCrossAccountOrRevisionChanges(): Unit = runBlocking {
        for (accountChange in listOf(false, true)) RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    if (request.path == "/search") row(1)
                    else "<h1>Book 1</h1><b>Detail author</b><span class='latest'>Latest chapter</span>")
            }
            fun configured(raw: JsonObject): JsonObject {
                val source = definition(raw)
                return JsonObject(source + ("ruleBookInfo" to JsonObject(source.getValue("ruleBookInfo").jsonObject +
                    ("author" to JsonPrimitive("b@text")))))
            }
            val firstSource = fixture.source { configured(it) }
            val preview = firstSource.use { RuleDiscoveryProvider(it).feed().get()!!.single().books.single() }
            val secondSource = if (accountChange) {
                val definition = firstSource.definition
                val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile, 1),
                    listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                val identity = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules", 1)
                createRuleSource(definition, identity, fixture.authority, session, fixture.runner)
            } else fixture.source { JsonObject(configured(it) + ("bookSourceComment" to JsonPrimitive("New revision"))) }
            var restored = false
            fixture.beforeRun = { task, _ -> if (task is ExecutionTask.Rule && task.location.field.startsWith("ruleExplore.")) restored = true }
            secondSource.use { source ->
                assertEquals("Detail author", source.information(preview.remoteId).author)
                assertFalse("A different account/revision must use its own detail response", restored)
            }
        }
    }

    @Test fun directBookEntriesOnlyReadTheirTitleUntilOpened(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    "<h1>Book 1</h1><b>Detail author</b><span class='latest'>Latest chapter</span>")
            }
            val fields = mutableListOf<String>()
            fixture.beforeRun = { task, _ -> if (task is ExecutionTask.Rule) fields += task.location.field }
            fixture.source { raw ->
                val source = definition(raw)
                JsonObject(source + mapOf("bookUrlPattern" to JsonPrimitive(Regex.escape(fixture.server.url("/book/1").toString())),
                    "ruleBookInfo" to JsonObject(source.getValue("ruleBookInfo").jsonObject + ("author" to JsonPrimitive("b@text")))))
            }.use { source ->
                val book = RuleDiscoveryProvider(source).page(DiscoveryRequest("/book/1")).get()!!.books.single()
                assertEquals("Book 1", book.title)
                assertEquals("", book.author)
                assertEquals(1, fields.count { it == "ruleBookInfo.name" })
                assertFalse("ruleBookInfo.author" in fields)
                assertEquals(1, fixture.server.requestCount)
                assertEquals("Detail author", source.information(book.remoteId).author)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }
}
