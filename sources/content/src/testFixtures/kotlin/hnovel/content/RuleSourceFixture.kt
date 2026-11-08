package hnovel.content

import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.*
import hnovel.rhino.HostBridge
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class RuleSourceFixture(browser: BrowserExecutor? = null, private val trace: ContentTrace = ContentTrace.None,
    route: SourceRouteProvider? = null, cipher: StorageCipher = StorageCipher.Plain) : AutoCloseable {
    val server = MockWebServer()
    val authority = ExecutionAuthority()
    val broker = SourceBroker(Files.createTempDirectory("rule-source-broker"), browser = browser, route = route, cipher = cipher)
    val worker = WorkerRuntime()
    val documents = AtomicInteger()
    var cycle = false
    var status = 200
    var afterRun: suspend (ExecutionTask) -> Unit = {}
    var beforeRun: (ExecutionTask, ExecutionLimits) -> Unit = { _, _ -> }
    var onPrepareIndependent: (ExecutionIdentity, Int) -> Unit = { _, _ -> }
    var imageBytes = byteArrayOf(1, 2, 3)
    var extraChapter = false
    var duplicateToc = false
    val runner = object : RuleTaskRunner {
        override suspend fun prepareIndependent(identity: ExecutionIdentity, count: Int) = onPrepareIndependent(identity, count)

        override suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask,
            limits: ExecutionLimits, broker: SourceExecutionBroker): ExecutionResult {
            beforeRun(task, limits)
            val scripts = task.libraryCode()?.takeIf(SourceLibraryDefinition::isUrlMap)?.let { broker.loadLibrary(it) }
            val wire = ExecutionWire.encode(identity, task, limits, scripts).toString(Charsets.UTF_8)
            val result = ExecutionWire.decodeResult(worker.executeSerialized(wire, HostBridge { name, args ->
                runBlocking { broker.call(name, args) }
            }).toByteArray())
            afterRun(task)
            return result
        }
    }
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                documents.incrementAndGet()
                if (status != 200) return MockResponse().setResponseCode(status)
                val source = request.getHeader("X-Source") ?: "unknown"
                val html = when (request.path?.substringBefore('?')) {
                    "/search" -> "<li><a href='/book/one'><h2>Same title</h2></a><b>Same author</b></li>"
                    "/book/one" -> "<h1>Same title</h1><b>Same author</b><a class='toc' href='/toc/1'>toc</a><img src='/cover.png'>"
                    "/toc/1" -> "<li data-volume='true'><a>Volume one</a></li><li><a href='/c/1'>One</a></li><a class='next' href='/toc/2'>next</a>"
                    "/toc/2" -> (if (duplicateToc) "<li><a href='/c/1'>One</a></li>" else "<li><a href='/c/2'>Two</a></li>") +
                        (if (extraChapter) "<li><a href='/c/3'>Three</a></li>" else "") +
                        (if (cycle) "<a class='next' href='/toc/1'>cycle</a>" else "")
                    "/c/1" -> "<article><p>$source first</p><img src='../image.png'><p>after image</p></article><a class='next' href='/c/1b'>next</a>"
                    "/c/1b" -> "<article><p>last page</p></article><a class='next' href='/c/2'>next chapter</a>"
                    "/c/2" -> "<article><p>second chapter</p></article>"
                    "/cover.png", "/image.png" -> return MockResponse().setBody(okio.Buffer().write(imageBytes))
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html)
            }
        }
        server.start()
    }
    fun raw(label: String = "A"): JsonObject {
        return buildJsonObject {
            put("bookSourceUrl", server.url("/source-$label").toString()); put("bookSourceName", "Same source name"); put("bookSourceType", 0)
            put("searchUrl", "/search?q={{key}}&page={{page}}")
            put("header", buildJsonObject { put("X-Source", label) }.toString())
            put("ruleSearch", buildJsonObject {
                put("bookList", "li"); put("name", "h2@text@js:book.putVariable('shared','from-search');result")
                put("author", "b@text"); put("bookUrl", "a@href")
            })
            put("ruleBookInfo", buildJsonObject { put("name", "h1@text"); put("author", "b@text"); put("tocUrl", "a.toc@href"); put("coverUrl", "img@src") })
            put("ruleToc", buildJsonObject {
                put("chapterList", "li"); put("chapterName", "a@text@js:chapter.putVariable('chapterKey',result);result")
                put("chapterUrl", "a@href"); put("isVolume", "@data-volume"); put("nextTocUrl", "a.next@href")
            })
            put("ruleContent", buildJsonObject {
                put("content", "article@html@js:result+'<p>'+book.getVariable('shared')+':'+chapter.getVariable('chapterKey')+'</p>'")
                put("nextContentUrl", "a.next@href"); put("replaceRegex", "##last page##last replaced")
                put("imageDecode", "result.reverse()")
            })
            put("coverDecodeJs", "result.reverse()")
        }
    }

    fun source(label: String = "A", profile: String = LEGADO_PROFILE, customize: (JsonObject) -> JsonObject = { it }): RuleSource {
        val store = SourceDefinitionStore(Files.createTempDirectory("rule-source-definitions"))
        val importer = SourceDefinitionImporter(store)
        val raw = raw(label)
        val preview = importer.preview(customize(raw).toString(), profile)
        assertEquals(emptyList<ImportIssue>(), preview.issues)
        assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
        val definition = store.list().single()
        val session = broker.open(SourceScope("rules", definition.sourceId, definition.profile),
            listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true)))
        val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
        return createRuleSource(definition, identity, authority, session, runner, trace)
    }
    override fun close() { worker.close(); broker.close(); server.close() }
}
