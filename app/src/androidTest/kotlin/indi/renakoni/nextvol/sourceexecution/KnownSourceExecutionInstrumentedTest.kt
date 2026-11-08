package indi.renakoni.nextvol.sourceexecution

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.*
import hnovel.rules.OutputKind
import hnovel.rules.RuleLocation
import hnovel.rules.RuleValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KnownSourceExecutionInstrumentedTest {
    @Test fun completeCatalogueFitsTheExistingBinderPacketLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val identity = authority.issue("large-catalogue", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val chapters = JsonArray((0 until 1600).map { index -> buildJsonObject {
                put("id", index); put("title", "Chapter $index"); put("metadata", "x".repeat(512))
            } })
            val task = ExecutionTask.Rule("$[*]", RuleValue.Text(chapters.toString()), OutputKind.Elements)
            val result = executor.execute(identity, task, ExecutionLimits(timeoutMillis = 15000, maxOutputBytes = 2 * 1024 * 1024))
            assertTrue(result.toString(), result is ExecutionResult.Success)
            val rows = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Items
            assertEquals(1600, rows.values.size)
            assertEquals(1599, Json.parseToJsonElement((rows.values.last() as RuleValue.Node).content).jsonObject.getValue("id").jsonPrimitive.int)
        } finally { executor.close() }
    }

    @Test fun realPageSizeIsSelectedInsideTheIsolatedWorker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val identity = authority.issue("maintained-source", LEGADO_PROFILE, "1")
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            val task = ExecutionTask.Rule("article@text", RuleValue.Text("<!--" + "x".repeat(320000) + "--><article>chapter</article>"),
                OutputKind.Text, RuleLocation("ruleContent.content"))
            val result = executor.execute(identity, task, ExecutionLimits(maxOutputBytes = 196608))
            assertTrue(result.toString(), result is ExecutionResult.Success)
            assertEquals(RuleValue.Text("chapter"), Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value)
            val oversized = task.copy(input = RuleValue.Text("x".repeat(ExecutionWire.MAX_INPUT_BYTES)))
            assertEquals(ExecutionResult.Failure(hnovel.execution.FailureCode.InputLimit), executor.execute(identity, oversized))
            assertEquals(ExecutionResult.Success("ready"), executor.execute(identity, ExecutionTask.Echo("ready")))
        } finally { executor.close() }
    }

    /** Explicit network opt-in; supplied public DNS belongs only to these disposable test sessions. */
    @Test fun knownSitesReachCatalogueSearchChaptersAndApprovedCovers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveKnownSources") == "true")
        val context = instrumentation.targetContext
        val mappings = Json.parseToJsonElement(String(Base64.decode(args.getString("knownSourcesDnsBase64"), Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        val dns = Dns { host -> mappings[host]?.jsonArray?.map { InetAddress.getByName(it.jsonPrimitive.content) }
            ?.takeIf { it.isNotEmpty() } ?: throw UnknownHostException("No fresh test-control DNS") }
        val directory = File(context.cacheDir, "known-live-${UUID.randomUUID()}").apply { mkdirs() }
        val definitions = SourceDefinitionStore(File(directory, "definitions").toPath())
        val importer = SourceDefinitionImporter(definitions)
        val text = checkNotNull(SourceDefinitionImporter::class.java.getResourceAsStream("/known-sources/hoohoo-1.0.3-4.json"))
            .bufferedReader().use { it.readText() }
        val preview = importer.preview(text)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertNull(importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) }).error)
        val samples = mapOf("https://www.alicesw.com/" to "novel/53254.html", "https://bcshuku.com/" to "novel54393/",
            "https://fqbook.cc/" to "m/book-1.html", "https://18mh.net/" to "novel/detail/3")
        val failures = mutableListOf<String>()
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        try {
            for (definition in definitions.list()) {
                val authority = ExecutionAuthority()
                val executor = AndroidIsolatedExecutor(context, authority)
                val runner = RuleTaskRunner { identity, task, limits, bridge -> executor.execute(identity, task, limits, bridge) }
                val broker = SourceBroker(File(directory, definition.sourceId).toPath(), dns = dns)
                var source: RuleSource? = null
                var session: hnovel.network.SourceSession? = null
                val trace = mutableListOf<ContentTraceEvent>()
                fun open(grants: List<NetworkGrant>): RuleSource {
                    session?.close()
                    val current = broker.open(SourceScope("known-live", definition.sourceId, definition.profile), grants)
                    session = current
                    val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "known-live")
                    return createRuleSource(definition, identity, authority, current, runner, ContentTrace { trace += it })
                }
                try {
                    val origin = definition.importKey
                    val apiGrant = NetworkGrant(origin)
                    source = open(listOf(apiGrant))
                    val catalog = source.openDiscovery("live").catalog()
                    assertTrue(catalog.rows.size > 1)
                    val first = source.discovery(catalog.rows.first().url, 1)
                    val second = source.discovery(catalog.rows.first().url, 2)
                    assertTrue(first.isNotEmpty() && second.isNotEmpty())
                    assertNotEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
                    val search = source.search("都市", 1)
                    assertTrue(search.isNotEmpty())
                    val bookId = origin + samples.getValue(origin)
                    val info = source.information(bookId)
                    val chapters = source.directory(bookId).filterNot { it.isVolume }
                    assertTrue(info.title.isNotBlank() && chapters.isNotEmpty())
                    val content = source.content(bookId, chapters.first().id)
                    assertEquals(chapters.first().id, content.id)
                    assertTrue(content.parts.sumOf { it.text?.length ?: 0 } > 100)
                    val initialImage = runCatching { source.image(bookId, info.coverUrl, true) }
                    val denial = (initialImage.exceptionOrNull() as? SourceContentException)?.denial
                    val bytes = if (denial != null) {
                        assertEquals(ContentError.PermissionDenied, (initialImage.exceptionOrNull() as SourceContentException).code)
                        assertEquals(ResourceKind.Image, denial.kind)
                        source.close()
                        source = open(listOf(apiGrant, NetworkGrant(denial.origin)))
                        source.image(bookId, info.coverUrl, true)
                    } else initialImage.getOrThrow()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    assertNotNull("Decoded cover for $origin", bitmap)
                    bitmap?.recycle()
                    report("Known source $origin: categories=${catalog.rows.size}, pages=${first.size}/${second.size}, search=${search.size}, chapters=${chapters.size}, text=${content.parts.sumOf { it.text?.length ?: 0 }}, cover=${bytes.size}, extraOriginApproved=${denial != null}")
                } catch (failure: Throwable) {
                    val reason = (failure as? SourceContentException)?.let { "${it.code}:${it.field}" } ?: failure.javaClass.simpleName
                    failures += "${definition.importKey}: $reason"
                    report("Known source ${definition.importKey}: $reason; trace=" + trace.takeLast(3).joinToString { "${it.field}:${it.result}:${it.ruleCode}" })
                } finally { source?.close(); executor.close(); broker.close() }
            }
            assertEquals(failures.toString(), emptyList<String>(), failures)
        } finally { directory.deleteRecursively() }
    }
}
