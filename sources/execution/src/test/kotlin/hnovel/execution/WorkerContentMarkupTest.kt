package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkerContentMarkupTest {
    private val identity = ExecutionAuthority().issue("markup", "legado", "1")
    private fun run(task: ExecutionTask, limits: ExecutionLimits = ExecutionLimits()): ExecutionResult =
        ExecutionWire.decodeResult(WorkerMain.executeSerialized(
            ExecutionWire.encode(identity, task, limits).toString(Charsets.UTF_8),
            HostBridge { _, _ -> throw AssertionError("Markup must not call the host") }
        ).toByteArray(Charsets.UTF_8))

    @Test fun formattedChapterKeepsLiteralTextAndImageMarkersAcrossTheWire() {
        val result = run(ExecutionTask.ContentMarkup("<b>literal</b> &amp;\n<img src='image?a=1&amp;b=2'>\nTail", formatted = true))
        assertTrue(result.toString(), result is ExecutionResult.Success)
        val value = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Items
        assertEquals(listOf("<b>literal</b> &amp;", "image?a=1&amp;b=2", "Tail"), value.values.map {
            Json.parseToJsonElement((it as RuleValue.Node).content).jsonObject.values.single().jsonPrimitive.content
        })
        val tooLarge = run(ExecutionTask.ContentMarkup("a".repeat(600), formatted = true), ExecutionLimits(maxOutputBytes = 512))
        assertEquals(FailureCode.OutputLimit, (tooLarge as ExecutionResult.Failure).code)
    }

    @Test fun byteLimitIncludesMultibyteTextAndTheWireEnvelope() {
        val failure = run(ExecutionTask.ContentMarkup("<p>${"中".repeat(200)}</p>"),
            ExecutionLimits(maxOutputBytes = 512)) as ExecutionResult.Failure
        assertEquals(FailureCode.OutputLimit, failure.code)
        assertEquals(RuleError(RuleStage.Budget, RuleLocation("ruleContent.parts"), "MarkupOutputLimit"), failure.ruleError)
    }

    @Test fun limitsAndCancellationKeepTheirLocationsAcrossTheWire() {
        val task = ExecutionTask.ContentMarkup("<div>".repeat(70), RuleLocation("ruleContent.parts", 9))
        val failure = run(task) as ExecutionResult.Failure
        assertEquals(FailureCode.Timeout, failure.code)
        assertEquals(RuleError(RuleStage.Budget, task.location, "MarkupDepthLimit"), failure.ruleError)
        Thread.currentThread().interrupt()
        try { assertEquals(FailureCode.Cancelled, (run(task.copy(html = "<p>normal</p>")) as ExecutionResult.Failure).code) }
        finally { Thread.interrupted() }
    }
}
