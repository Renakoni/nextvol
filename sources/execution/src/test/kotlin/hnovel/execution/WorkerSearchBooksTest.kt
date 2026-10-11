package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkerSearchBooksTest {
    private val identity = ExecutionIdentity("fixture", "legado", "1", "search")
    private val input = RuleValue.Node("<li><a href='/one'>A &amp; B</a><b>Author</b><i>One</i><i>Two</i></li>", InputKind.Html, "ul")
    private val task = ExecutionTask.SearchBooks(listOf(input), mapOf("name" to "a@text", "author" to "b@text",
        "kind" to "i@text", "bookUrl" to "a@href"), "https://example.test/list")
    private fun run(task: ExecutionTask.SearchBooks, bytes: Int = 4 * 1024 * 1024) =
        Json.decodeFromString(ExecutionResult.serializer(), WorkerMain.executeSerialized(
            ExecutionWire.encode(identity, task, ExecutionLimits(timeoutMillis = 5000, maxOutputBytes = bytes)).toString(Charsets.UTF_8)))
    private fun rows(result: ExecutionResult): List<Map<String, String>> {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        val rows = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Items
        return rows.values.map { row -> ExecutionTask.SearchBooks.FIELDS.zip((row as RuleValue.Items).values.map { (it as RuleValue.Text).value }).toMap() }
    }

    @Test fun fullFieldsPreserveEntitiesListTagsAndUrlsWithOptionalRuleErrors() {
        val row = rows(run(task.copy(rules = task.rules + ("coverUrl" to "[")))).single()
        assertEquals("A & B", row["name"])
        assertEquals("Author", row["author"])
        assertEquals("One,Two", row["kind"])
        assertEquals("https://example.test/one", row["bookUrl"])
        assertEquals("", row["coverUrl"])
        val error = run(task.copy(rules = task.rules + ("author" to "["))) as ExecutionResult.Failure
        assertEquals("ruleSearch.author", error.ruleError?.location?.field)
    }

    @Test fun blankNamesSkipOtherFieldsButFallbackNamesEvaluateThem() {
        val empty = task.copy(rules = mapOf("name" to "missing@text", "author" to "[", "bookUrl" to "["))
        assertTrue(rows(run(empty)).single().values.all { it.isEmpty() })
        assertTrue(run(empty.copy(fallbackTitle = "Fallback")) is ExecutionResult.Failure)
    }

    @Test fun workerRejectsScriptsTemplatesStateAndOversizedBatches() {
        for (rule in listOf("@js:java.put('x','y')", "<js>result</js>", "{{book.name}}", "@put:{x:'text'}text", "@get:{x}"))
            for (field in ExecutionTask.SearchBooks.FIELDS)
                assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(rules = task.rules + (field to rule))))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = List(9) { input })))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(inputs = emptyList())))
        assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), run(task.copy(rules = mapOf("unknown" to "text"))))
    }

    @Test fun fieldsAndAggregateOutputKeepTheirSeparateLimits() {
        val large = RuleValue.Node("<li>${"正文".repeat(10000)}</li>", InputKind.Html, "ul")
        val batch = task.copy(inputs = List(8) { large }, rules = mapOf("name" to "text"))
        assertEquals(8, rows(run(batch)).size)
        // The selector budget can reject a large field before the serialized output limit.
        assertTrue(run(batch, 64) is ExecutionResult.Failure)
        assertTrue(run(task.copy(inputs = listOf(
            RuleValue.Node("<li>${"x".repeat(200000)}</li>", InputKind.Html)), rules = mapOf("name" to "text"))) is ExecutionResult.Failure)
        val smallFields = task.copy(inputs = List(8) { input })
        assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), run(smallFields, 512))
    }
}
