package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkerDirectoryRowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val identity = ExecutionIdentity("fixture", "legado", "1", "directory")
    private val url = "https://example.test/book"
    private val input = RuleValue.Node("<li><a href='/one'>A &amp; B</a></li>", InputKind.Html, "ul")
    private val task = ExecutionTask.DirectoryRows(listOf(input), mapOf("chapterName" to "a@text", "chapterUrl" to "a@href"),
        ExecutionTask.Rule("", RuleValue.Empty, bookId = url, baseUrl = url), url, url)
    private fun run(task: ExecutionTask.DirectoryRows, bytes: Int = 4 * 1024 * 1024, millis: Long = 5000): ExecutionResult =
        ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(identity, task,
            ExecutionLimits(timeoutMillis = millis, maxOutputBytes = bytes)).toString(Charsets.UTF_8)).toByteArray())
    private fun rows(result: ExecutionResult): List<DirectoryRow> {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        return Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).directoryRows
    }

    @Test fun volumesPlaceholdersRequestOptionsAndEntitiesKeepTheirOriginalMeaning() {
        assertEquals("A & B", rows(run(task)).single().title)
        assertEquals("https://example.test/one", rows(run(task)).single().id)
        val special = task.copy(inputs = listOf(
            RuleValue.Node("<li><a>Volume</a></li>", InputKind.Html),
            RuleValue.Node("<li><a href='javascript:locked()'>Locked</a></li>", InputKind.Html),
            RuleValue.Node("<li><a href='/read,{&quot;method&quot;:&quot;POST&quot;}'>Next</a></li>", InputKind.Html)
        ), rules = task.rules + ("isVolume" to "@js:chapter.title=='Volume'"))
        val values = rows(run(special))
        assertTrue(values[0].id.startsWith("volume:"))
        assertEquals("Volume0", values[0].metadata["url"]!!.jsonPrimitive.content)
        assertEquals("javascript:locked()", values[1].id)
        assertEquals("https://example.test/read,{\"method\":\"POST\"}", values[2].id)
    }

    @Test fun batchAndFieldOutputLimitsRemainSeparateAndScriptsHaveADeadline() {
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = List(65) { input })))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = emptyList())))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(rules = mapOf("unexpected" to "text"))))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(context = task.context.copy(readOnly = true))))
        assertEquals(FailureCode.OutputLimit, (run(task.copy(inputs = List(4) { input }), bytes = 700) as ExecutionResult.Failure).code)
        val large = task.copy(inputs = listOf(RuleValue.Node("<li>${"x".repeat(200000)}</li>", InputKind.Html)),
            rules = mapOf("chapterName" to "text"))
        assertTrue(run(large) is ExecutionResult.Failure)
        val endless = task.copy(rules = task.rules + ("chapterName" to "@js:while(true){}"))
        assertEquals(FailureCode.Timeout, (run(endless, millis = 50) as ExecutionResult.Failure).code)
    }

    @Test fun sharedLibraryStateContinuesInFieldOrderAcrossBatches() {
        val library = "var state={calls:0}; function next(){return String(++state.calls)}"
        val batch = task.copy(inputs = List(2) { input }, context = task.context.copy(libraryCode = library),
            rules = mapOf("chapterName" to "@js:next()", "chapterUrl" to "@js:'/c/'+next()"))
        WorkerRuntime().use { worker ->
            fun next() = rows(ExecutionWire.decodeResult(worker.executeSerialized(ExecutionWire.encode(identity, batch,
                ExecutionLimits(timeoutMillis = 5000)).toString(Charsets.UTF_8)).toByteArray()))
            assertEquals(listOf("1", "3"), next().map { it.title })
            assertEquals(listOf("5", "7"), next().map { it.title })
        }
    }

    @Test fun bridgeBudgetsBelongToEachFieldAndCannotBeResetBySourceCode() = runBlocking {
        val authority = ExecutionAuthority()
        val id = authority.issue("fixture", "legado", "1")
        SourceBroker(folder.root.toPath()).use { sessions ->
            val session = sessions.open(SourceScope("default", "fixture", "legado"), emptyList())
            val limits = ExecutionLimits(timeoutMillis = 5000, maxOutputBytes = 65536, maxRequests = 2)
            fun batch(rule: String) = task.copy(inputs = List(3) { input }, rules = mapOf("chapterName" to rule))
            suspend fun execute(batch: ExecutionTask.DirectoryRows): Pair<ExecutionResult, Boolean> =
                SourceExecutionBroker(id, authority, session, limits, url, directoryFields = 3).use { broker ->
                    val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(ExecutionWire.encode(id, batch, limits)
                        .toString(Charsets.UTF_8), HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray())
                    result to broker.requestLimitExceeded
                }
            assertEquals(3, rows(execute(batch("@js:source.put('a','1');source.put('b','2');'Title'")).first).size)
            val exceeded = execute(batch("@js:source.put('a','1');source.put('b','2');source.put('c','3');'Title'"))
            assertEquals(FailureCode.BridgeDenied, (exceeded.first as ExecutionResult.Failure).code)
            assertTrue(exceeded.second)
            val forged = execute(batch("@js:host.call('directory.call',[1,'source.put',['c','forged']]);'Title'"))
            assertTrue(forged.first is ExecutionResult.Failure)
            assertEquals(StorageResult.Value(null), session.read(StorageRequest(StorageArea.Config, "value:c")))
        }
    }
}
