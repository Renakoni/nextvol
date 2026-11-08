package indi.renakoni.nextvol.sourceexecution

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.ContentTrace
import hnovel.content.createRuleSource
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.SourceExecutionBroker
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.AndroidSourceStorageCipher
import indi.renakoni.nextvol.data.web.rules.RuleDiscoveryProvider
import indi.renakoni.nextvol.di.WebDataSourceModule
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import io.nightfish.lightnovelreader.api.web.discovery.DiscoverySection
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, fixed local pages through the real provider, Binder worker, storage and browser. */
@RunWith(AndroidJUnit4::class)
class DiscoveryPerformanceInstrumentedTest {
    private val titles = listOf("Daily", "Weekly", "Monthly", "Articles")
    private val entries = 30

    @Test fun compareDiscoveryLoads(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("discoveryPerformance") == "true")
        val repeats = (args.getString("discoveryRepeats") ?: "5").toInt().also { require(it in 1..10) }
        val modes = (args.getString("discoveryModes") ?: "http,native").split(',')
        require(modes.isNotEmpty() && modes.all { it == "http" || it == "native" })
        val view = args.getString("discoveryView") ?: "feed"
        require(view in listOf("feed", "list", "handoff", "resume"))
        val fullList = view == "list" || view == "handoff"
        val expectedTitles = if (fullList) titles.take(1) else titles
        val booksPerSection = if (fullList) entries else 6
        val responseDelay = (args.getString("discoveryDelayMillis") ?: "0").toLong().also { require(it in 0..3000) }
        val expectedRequests = (args.getString("discoveryExpectedRequests") ?: expectedTitles.size.toString()).toInt()
        val context = instrumentation.targetContext
        for (mode in modes) MockWebServer().use { server ->
            val activeRequests = AtomicInteger()
            val maximumRequests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val body = when {
                        path.startsWith("/list/") -> {
                            val section = path.substringAfterLast('/').toInt()
                            (1..entries).joinToString("") { index ->
                                "<li class='book'><a class='title' href='/book/$section/$index'>Book $section-$index</a>" +
                                    "<b class='author'>Author $index</b><p class='intro'>Description $index</p></li>"
                            }.let { "<ul>$it</ul>" }
                        }
                        path.startsWith("/book/") -> {
                            val parts = path.split('/')
                            "<h1>Book ${parts[2]}-${parts[3]}</h1><b class='author'>Author ${parts[3]}</b>" +
                                "<span class='latest'>Latest chapter</span>"
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    maximumRequests.accumulateAndGet(activeRequests.incrementAndGet(), ::maxOf)
                    try { if (responseDelay > 0) Thread.sleep(responseDelay) }
                    finally { activeRequests.decrementAndGet() }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store")
                        .setBody("<html><head><link rel='icon' href='data:,'></head><body>$body</body></html>")
                }
            }
            server.start()
            val root = File(context.cacheDir, "discovery-performance-$mode-${System.nanoTime()}")
            val authority = ExecutionAuthority()
            val executor = AndroidIsolatedExecutor(context, authority)
            val taskRunner = WebDataSourceModule.provideRuleTaskRunner(executor)
            val calls = ConcurrentHashMap<String, AtomicInteger>()
            val taskNanos = ConcurrentHashMap<String, AtomicLong>()
            val executionNanos = AtomicLong()
            val networkMillis = AtomicLong()
            val executionSpans = ConcurrentLinkedQueue<Pair<Long, Long>>()
            val networkSpans = ConcurrentLinkedQueue<Pair<Long, Long>>()
            val saves = AtomicInteger()
            val saveNanos = AtomicLong()
            val opens = AtomicInteger()
            val openNanos = AtomicLong()
            val cipher = object : StorageCipher {
                private val delegate = AndroidSourceStorageCipher()
                override fun seal(bytes: ByteArray, identity: String): ByteArray {
                    val start = SystemClock.elapsedRealtimeNanos()
                    try { return delegate.seal(bytes, identity) }
                    finally { saves.incrementAndGet(); saveNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start) }
                }
                override fun open(bytes: ByteArray, identity: String): ByteArray {
                    val start = SystemClock.elapsedRealtimeNanos()
                    try { return delegate.open(bytes, identity) }
                    finally { opens.incrementAndGet(); openNanos.addAndGet(SystemClock.elapsedRealtimeNanos() - start) }
                }
            }
            val runner = object : RuleTaskRunner by taskRunner {
                override suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask,
                    limits: ExecutionLimits, broker: SourceExecutionBroker): ExecutionResult {
                    val field = (task as? ExecutionTask.Rule)?.location?.field ?: task.javaClass.simpleName
                    calls.computeIfAbsent(field) { AtomicInteger() }.incrementAndGet()
                    val started = SystemClock.elapsedRealtimeNanos()
                    try { return taskRunner.execute(identity, task, limits, broker) }
                    finally {
                        val elapsed = SystemClock.elapsedRealtimeNanos() - started
                        executionSpans.add(started to started + elapsed)
                        executionNanos.addAndGet(elapsed)
                        taskNanos.computeIfAbsent(field) { AtomicLong() }.addAndGet(elapsed)
                    }
                }
            }
            val trace = ContentTrace { event ->
                if (event.kind == "network") {
                    networkMillis.addAndGet(event.elapsedMillis)
                    val ended = SystemClock.elapsedRealtimeNanos()
                    networkSpans.add(ended - event.elapsedMillis * 1_000_000 to ended)
                }
            }
            try {
                val definitions = SourceDefinitionStore(File(root, "definitions").toPath())
                val importer = SourceDefinitionImporter(definitions)
                val raw = buildJsonObject {
                    put("bookSourceUrl", server.url("/").toString())
                    put("bookSourceName", "Discovery performance fixture")
                    put("bookSourceType", 0); put("enabledExplore", true); put("browserRead", mode == "native")
                    put("homepageModules", buildJsonArray {
                        titles.forEachIndexed { index, title -> add(buildJsonObject {
                            put("key", "section-$index"); put("type", "ranking"); put("title", title)
                            put("url", "/list/$index?page={{page}}")
                        }) }
                    }.toString())
                    put("ruleExplore", buildJsonObject {
                        put("bookList", "@js:java.getElements('li.book')")
                        put("name", "a.title@text"); put("author", "b.author@text")
                        put("intro", "p.intro@text"); put("bookUrl", "a.title@href")
                    })
                    put("ruleBookInfo", buildJsonObject {
                        put("name", "h1@text"); put("author", "b.author@text"); put("lastChapter", "span.latest@text")
                    })
                }
                val preview = importer.preview(raw.toString(), LEGADO_PROFILE)
                assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
                val definition = definitions.list().single()
                SourceBroker(File(root, "broker").toPath(), cipher = cipher,
                    browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("discovery-performance", definition.sourceId, definition.profile),
                        listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true)))
                    val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "discovery-performance")
                    try { createRuleSource(definition, identity, authority, session, runner, trace).use { source ->
                        var completed = emptyList<DiscoverySection>()
                        for (iteration in 0..repeats) {
                            if (view == "handoff" || view == "resume") {
                                val home = RuleDiscoveryProvider(source, source.openDiscovery("home-$iteration"))
                                assertNotNull(home.homepageCatalog(refresh = true).get())
                                // Prepare one known page even when the regular feed finishes out of order.
                                val partial = home.preview("homepage:section-0").get() ?: error("Preview failed")
                                assertNull(partial.previewFailure)
                                assertEquals(6, partial.books.size)
                            }
                            calls.clear(); taskNanos.clear(); executionNanos.set(0); networkMillis.set(0)
                            executionSpans.clear(); networkSpans.clear(); maximumRequests.set(0)
                            saves.set(0); saveNanos.set(0); opens.set(0); openNanos.set(0)
                            val requestsBefore = server.requestCount
                            val provider = RuleDiscoveryProvider(source, source.openDiscovery("iteration-$iteration"))
                            val ready = linkedMapOf<String, Double>()
                            var structureReadyMillis: Double? = null
                            val started = SystemClock.elapsedRealtimeNanos()
                            withTimeout(180_000) {
                                if (view == "feed") assertNotNull(provider.homepageCatalog(refresh = true).get())
                                if (fullList) {
                                    val result = provider.page(DiscoveryRequest("/list/0?page={{page}}"))
                                    val page = result.get() ?: error("Discovery list failed: ${result.getError()}")
                                    ready[titles.first()] = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                                    structureReadyMillis = ready.values.first()
                                    completed = listOf(DiscoverySection("section-0", titles.first(), page.books))
                                } else provider.feedUpdates().collect { result ->
                                    val sections = result.get() ?: error("Discovery failed: ${result.getError()}")
                                    if (structureReadyMillis == null && sections.map { it.title } == expectedTitles)
                                        structureReadyMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                                    sections.forEach { section ->
                                        assertNull(section.previewFailure)
                                        if (section.books.isNotEmpty()) {
                                            assertEquals(booksPerSection, section.books.size)
                                            ready.putIfAbsent(section.title, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                                        }
                                    }
                                    completed = sections
                                }
                            }
                            val totalMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                            assertEquals(expectedTitles, completed.map { it.title })
                            completed.forEachIndexed { sectionIndex, section ->
                                assertEquals((1..booksPerSection).map { "Book $sectionIndex-$it" }, section.books.map { it.title })
                                assertEquals((1..booksPerSection).map { server.url("/book/$sectionIndex/$it").toString() }, section.books.map { it.remoteId })
                            }
                            assertEquals(expectedTitles.size, ready.size)
                            assertEquals(expectedRequests, server.requestCount - requestsBefore)
                            val report = buildJsonObject {
                                put("label", args.getString("discoveryLabel") ?: "local")
                                put("view", view)
                                put("responseDelayMs", responseDelay)
                                put("mode", mode); put("iteration", iteration); put("sample", if (iteration == 0) "first" else "repeat")
                                put("booksPerPage", entries); put("previewBooks", completed.sumOf { it.books.size })
                                put("firstBooksMs", ready.values.min()); put("allBooksMs", totalMillis)
                                put("structureReadyMs", requireNotNull(structureReadyMillis))
                                put("sectionReadyMs", buildJsonObject { ready.forEach { (name, elapsed) -> put(name, elapsed) } })
                                put("requests", server.requestCount - requestsBefore)
                                put("ruleTasks", calls.values.sumOf { it.get() })
                                put("taskCounts", buildJsonObject { calls.toSortedMap().forEach { (field, count) -> put(field, count.get()) } })
                                put("taskMillis", buildJsonObject { taskNanos.toSortedMap().forEach { (field, nanos) -> put(field, nanos.get() / 1_000_000.0) } })
                                put("executionMs", executionNanos.get() / 1_000_000.0)
                                put("networkMs", networkMillis.get())
                                // Inclusive task totals may overlap. Only unions describe wall-clock coverage.
                                put("executionSpanMs", unionMillis(executionSpans))
                                put("networkSpanMs", unionMillis(networkSpans))
                                put("observedSpanMs", unionMillis(executionSpans + networkSpans))
                                put("maxConcurrentRequests", maximumRequests.get())
                                put("storageSeals", saves.get()); put("storageSealMs", saveNanos.get() / 1_000_000.0)
                                put("storageOpens", opens.get()); put("storageOpenMs", openNanos.get() / 1_000_000.0)
                            }
                            instrumentation.sendStatus(0, Bundle().apply { putString("discoveryPerformance", report.toString()) })
                        }
                        // Check the ordinary open-book path after the timed runs, without warming it between samples.
                        val first = completed.first().books.first()
                        val information = source.information(first.remoteId)
                        assertEquals(first.title, information.title)
                        assertEquals("Author 1", information.author)
                    } } finally { session.clearAccount() }
                }
            } finally { executor.close(); root.deleteRecursively() }
        }
    }

    private fun unionMillis(spans: Collection<Pair<Long, Long>>): Double {
        var covered = 0L
        var end = 0L
        for ((start, finish) in spans.sortedBy { it.first }) {
            covered += (finish - maxOf(start, end)).coerceAtLeast(0)
            end = maxOf(end, finish)
        }
        return covered / 1_000_000.0
    }
}
