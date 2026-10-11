package hnovel.execution

import hnovel.rhino.ArchiveDecoder
import hnovel.rhino.HostBridge
import hnovel.rhino.ScriptLibrary
import hnovel.rules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable data class DirectoryRow(
    val id: String, val title: String, val isVolume: Boolean, val isVip: Boolean, val isPay: Boolean,
    val updateTime: String, val metadata: JsonObject, val variables: Map<String, String>,
    val bigVariables: Map<String, String>, val inherited: Map<String, String>,
)

/** A bounded, ordered continuation of the host's directory loop, inside one isolated invocation. */
internal object WorkerDirectoryRows {
    private class Failed(val result: ExecutionResult.Failure) : RuntimeException()

    fun evaluate(task: ExecutionTask.DirectoryRows, identity: ExecutionIdentity, limits: ExecutionLimits,
        bridge: HostBridge, library: ScriptLibrary?, archives: ArchiveDecoder): ExecutionResult {
        if (task.inputs.size !in 1..ExecutionTask.DirectoryRows.MAX_ROWS || task.startIndex < 0 ||
            task.rules.keys.any { it !in ExecutionTask.DirectoryRows.FIELDS } || task.context.readOnly)
            return ExecutionResult.Failure(FailureCode.InvalidTask)
        val started = System.nanoTime()
        var book = task.context
        val rows = mutableListOf<DirectoryRow>()
        var outputBytes = 0
        var fieldIndex = 0
        fun remaining(): Long {
            if (Thread.currentThread().isInterrupted) throw Failed(ExecutionResult.Failure(FailureCode.Cancelled))
            val time = limits.timeoutMillis - (System.nanoTime() - started) / 1_000_000
            if (time <= 0) throw Failed(ExecutionResult.Failure(FailureCode.Timeout))
            return time
        }
        try {
            for ((offset, input) in task.inputs.withIndex()) {
                remaining()
                val index = task.startIndex + offset
                var row = book.copy(input = input, chapterId = "pending:$index")
                var inherited = emptyMap<String, String>()
                fun chapterField(name: String, value: JsonElement) {
                    row = row.copy(chapter = JsonObject(row.chapter + (name to value)))
                }
                fun field(name: String): String {
                    val rule = task.rules[name].orEmpty()
                    if (rule.isBlank()) return ""
                    val currentField = fieldIndex++
                    val fieldBridge = HostBridge { method, args -> bridge.call("directory.call",
                        listOf(JsonPrimitive(currentField), JsonPrimitive(method), JsonArray(args))) }
                    val result = WorkerRuleEvaluator.evaluate(row.copy(rule = rule, output = OutputKind.Text,
                        location = RuleLocation("ruleToc.$name")), identity,
                        limits.copy(timeoutMillis = remaining(), maxOutputBytes = minOf(196608, limits.maxOutputBytes)),
                        fieldBridge, library, archives)
                    if (result is ExecutionResult.Failure) throw Failed(result)
                    val executed = Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
                    inherited = inherited + executed.writes
                    row = row.copy(book = executed.book ?: row.book, chapter = executed.chapter ?: row.chapter,
                        bookVariables = applyWrites(row.bookVariables, executed.bookWrites),
                        chapterVariables = applyWrites(row.chapterVariables, executed.chapterWrites),
                        bookBigVariables = applyWrites(row.bookBigVariables, executed.bookBigWrites),
                        chapterBigVariables = applyWrites(row.chapterBigVariables, executed.chapterBigWrites),
                        sourceVariables = task.context.sourceVariables + inherited)
                    return executed.value.text()
                }
                chapterField("baseUrl", JsonPrimitive(task.documentUrl))
                chapterField("bookUrl", JsonPrimitive(task.context.bookId.orEmpty()))
                val title = field("chapterName")
                if (title.isBlank()) continue
                chapterField("title", JsonPrimitive(title))
                val raw = field("chapterUrl")
                chapterField("url", JsonPrimitive(raw))
                val time = field("updateTime")
                chapterField("updateTime", JsonPrimitive(time))
                chapterField("tag", JsonPrimitive(time))
                val volume = field("isVolume").truth()
                val id = if (volume && raw.isBlank()) "volume:" + MessageDigest.getInstance("SHA-256")
                    .digest("${task.requestUrl}:$index:$title".toByteArray(Charsets.UTF_8)).toHexString()
                else try { resolveSourceLink(task.documentUrl, raw.ifBlank { task.requestUrl }) }
                    catch (_: IllegalArgumentException) { raw }
                row = row.copy(chapterId = id)
                chapterField("url", JsonPrimitive(raw.ifBlank { if (volume) title + index else task.requestUrl }))
                chapterField("isVolume", JsonPrimitive(volume))
                val vip = field("isVip").truth()
                val pay = field("isPay").truth()
                chapterField("isVip", JsonPrimitive(vip))
                chapterField("isPay", JsonPrimitive(pay))
                val chapter = DirectoryRow(id, title, volume, vip, pay, time, row.chapter,
                    row.chapterVariables, row.chapterBigVariables, inherited)
                outputBytes += Json.encodeToString(DirectoryRow.serializer(), chapter).toByteArray(Charsets.UTF_8).size
                if (outputBytes > limits.maxOutputBytes) return ExecutionResult.Failure(FailureCode.OutputLimit)
                rows += chapter
                // Each row starts with a fresh chapter, but sees the previous successful row's book writes.
                book = book.copy(book = row.book, bookVariables = row.bookVariables, bookBigVariables = row.bookBigVariables)
            }
            remaining()
        } catch (failure: Failed) {
            return failure.result
        }
        val output = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(RuleValue.Empty, emptyMap(),
            bookWrites = changes(task.context.bookVariables, book.bookVariables),
            bookBigWrites = changes(task.context.bookBigVariables, book.bookBigVariables),
            book = book.book, directoryRows = rows))
        return if (output.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
            else ExecutionResult.Success(output)
    }

    private fun applyWrites(before: Map<String, String>, writes: Map<String, String?>) = before.toMutableMap().apply {
        writes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }.toMap()

    private fun changes(before: Map<String, String>, after: Map<String, String>): Map<String, String?> =
        (before.keys + after.keys).filter { before[it] != after[it] }.associateWith { after[it] }

    private fun String.truth() = isNotBlank() && this != "null" && trim().lowercase() !in setOf("false", "no", "not", "0", "0.0")
    private fun RuleValue.text(): String = when (this) {
        is RuleValue.Text -> value
        is RuleValue.Node -> content
        is RuleValue.Items -> values.joinToString("\n") { it.text() }
        is RuleValue.Captures -> groups.joinToString("\n")
        RuleValue.Empty -> ""
    }
}
