package hnovel.content

import hnovel.execution.ExecutionTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleMetadataTest {
    @Test fun oldSnapshotsSeedLastValidMetadataWithoutChangingRawOrScriptState() {
        val old = Json.decodeFromString<BookRecord>("""{"revision":"old","book":{
            "id":"book","wordCount":"1.23万","updateTime":"2026-09-14","observedUpdate":123,
            "state":{"variables":{"custom":"kept"}}}}""")
        assertEquals("", old.book.lastValidWordCount)
        assertEquals("", old.book.lastValidUpdateTime)
        val invalid = old.book.copy(wordCount = "unknown", updateTime = "2026-02-30").preservingValidMetadata(old.book)
        val restored = Json.decodeFromString<BookRecord>(Json.encodeToString(BookRecord.serializer(), old.copy(book = invalid)))
        assertEquals("1.23万", restored.book.lastValidWordCount)
        assertEquals("2026-09-14", restored.book.lastValidUpdateTime)
        assertEquals("unknown", restored.book.wordCount)
        assertEquals("2026-02-30", restored.book.updateTime)
        assertEquals(old.book.state, restored.book.state)
        assertEquals(old.book.observedUpdate, restored.book.observedUpdate)
        val stillInvalid = restored.book.copy(wordCount = "0", updateTime = "today").preservingValidMetadata(restored.book)
        assertEquals("1.23万", stillInvalid.lastValidWordCount)
        assertEquals("2026-09-14", stillInvalid.lastValidUpdateTime)
        val valid = restored.book.copy(wordCount = "2万", updateTime = "2026-09-15").preservingValidMetadata(restored.book)
        assertEquals("2万", valid.lastValidWordCount)
        assertEquals("2026-09-15", valid.lastValidUpdateTime)
    }

    @Test fun updateMetadataStillPropagatesLimitsAndLoginFailures(): Unit = runBlocking {
        for (code in listOf(ContentError.Limit, ContentError.LoginRequired)) RuleSourceFixture().use { fixture ->
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleBookInfo.updateTime")
                    throw SourceContentException(code, task.location.field)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleBookInfo" to
                JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                    ("updateTime" to JsonPrimitive("@js:'2026-09-14'"))))) }).use { source ->
                val failure = runCatching { source.information(fixture.server.url("/book/one").toString()) }.exceptionOrNull()
                assertTrue(failure is SourceContentException)
                assertEquals(code, (failure as SourceContentException).code)
            }
        }
    }

    @Test fun invalidUpdateRuleKeepsEarlierMetadataAndReadableDetails(): Unit = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
            "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
                ("updateTime" to JsonPrimitive("@js:'2026-09-14'"))),
            "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                ("updateTime" to JsonPrimitive("@js:throw new Error('optional date')")))
        )) }).use { source ->
            val found = source.search("title").single()
            val book = source.information(found.id)
            assertEquals("Same title", book.title)
            assertEquals("2026-09-14", book.updateTime)
            assertEquals(3, source.directory(book.id).size)
        } }
    }

    @Test fun absentAndBlankKindRulesKeepEarlierMetadataWithoutExecutingAnEmptyTask(): Unit = runBlocking {
        for (kind in listOf(null, "", " \t\n")) for (discovery in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val kindTasks = mutableListOf<ExecutionTask.Rule>()
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule && task.location.field.endsWith(".kind")) kindTasks += task
            }
            fixture.source(customize = { raw ->
                val list = JsonObject(raw.getValue("ruleSearch").jsonObject + mapOf(
                    "name" to JsonPrimitive("h2@text@js:book.kind='inherited,tag';result")
                ) + if (kind == null) emptyMap() else mapOf("kind" to JsonPrimitive(kind)))
                val information = JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                    if (kind == null) emptyMap() else mapOf("kind" to JsonPrimitive(kind)))
                JsonObject(raw + mapOf("ruleSearch" to list, "ruleExplore" to list,
                    "exploreUrl" to JsonPrimitive("/search"), "ruleBookInfo" to information))
            }).use { source ->
                val book = if (discovery) source.openDiscovery("metadata").page("/search", 1, emptyMap()).single()
                    else source.search("title").single()
                assertEquals(listOf("inherited", "tag"), book.tags)
                assertEquals(listOf("inherited", "tag"), source.information(book.id).tags)
                assertEquals("Missing metadata rules must not occupy the isolated worker", emptyList<ExecutionTask.Rule>(), kindTasks)
            }
        }
    }

    @Test fun configuredKindWithEmptyOutputStillAppliesItsMetadataAndVariableWrites(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to
                JsonObject(raw.getValue("ruleSearch").jsonObject + ("kind" to
                    JsonPrimitive("@js:book.kind='from-script';book.putVariable('kindSeen','yes');''"))))) }).use { source ->
                val book = source.search("title").single()
                assertEquals(listOf("from-script"), book.tags)
                assertEquals("yes", book.state.variables["kindSeen"])
            }
        }
    }
}
