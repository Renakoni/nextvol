package hnovel.imports

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceDefinitionEditsTest {
    private val raw = buildJsonObject {
        put("bookSourceUrl", "https://old.example/")
        put("bookSourceName", "Original")
        put("searchUrl", "https://old.example/search?q={{key}}")
        put("header", "{\"Referer\":\"https://www.old.example/\"}")
        put("ruleToc", buildJsonObject { put("chapterList", "li"); put("chapterUrl", "@js:'https://old.example/'+result") })
        put("ruleContent", buildJsonObject { put("content", "article"); put("nextContentUrl", "https://old.example/content") })
    }
    private val definition = SourceDefinition("a".repeat(64), "legado-json", LEGADO_PROFILE, "https://old.example/",
        "Original", true, true, ImportOrigin(ImportOrigin.Kind.Url, "https://updates.example/source.json"),
        digest(canonical(raw).toString()), 4, canonical(raw).toString())

    @Test fun localEditKeepsImportIdentityAndChangesOnlyRevisionData() {
        val preview = SourceDefinitionEdits.previewDomain(raw.toString(), "old.example", "new.example")
        val edited = SourceDefinitionEdits.prepare(definition, preview.rawJson, 8)
        assertEquals(definition.sourceId, edited.sourceId)
        assertEquals(definition.importKey, edited.importKey)
        assertEquals(definition.origin, edited.origin)
        assertEquals(definition.profile, edited.profile)
        assertEquals(8L, edited.revision)
        assertNotEquals(definition.contentDigest, edited.contentDigest)
        assertEquals(digest(edited.rawJson), edited.contentDigest)
        assertTrue(edited.rawJson.contains("new.example"))
        assertFalse(edited.rawJson.contains("old.example"))
        assertSame(definition, SourceDefinitionEdits.prepare(definition, SourceDefinitionEdits.format(definition.rawJson)))
    }

    @Test fun previewCoversHeadersScriptsAndNestedRulesWithoutChangingInput() {
        val preview = SourceDefinitionEdits.previewDomain(raw.toString(), "https://old.example/", "new.example")
        assertEquals(setOf("bookSourceUrl", "searchUrl", "header", "ruleToc.chapterUrl", "ruleContent.nextContentUrl"),
            preview.changes.map { it.field }.toSet())
        assertTrue(preview.remaining.isEmpty())
        assertTrue(preview.changes.single { it.field == "header" }.after.contains("www.new.example"))
        assertEquals("https://old.example/", raw.getValue("bookSourceUrl").jsonPrimitive.content)
    }

    @Test fun replacementHonorsDomainBoundariesAndReportsUnchangedResiduals() {
        val input = JsonObject(raw + mapOf(
            "jsLib" to JsonPrimitive("/old\\.example/; 'https://OLD.EXAMPLE:8443/a'; 'https:\\/\\/old.example/b'"),
            "bookSourceComment" to JsonPrimitive("notold.example old.example.evil invalid-old.example old\\.example\\.evil")))
        val preview = SourceDefinitionEdits.previewDomain(input.toString(), "old.example", "new.example")
        val after = Json.parseToJsonElement(preview.rawJson).jsonObject
        assertEquals("/new\\.example/; 'https://new.example:8443/a'; 'https:\\/\\/new.example/b'", after.getValue("jsLib").jsonPrimitive.content)
        assertEquals(input["bookSourceComment"], after["bookSourceComment"])
        assertEquals(listOf("bookSourceComment"), preview.remaining)
    }

    @Test fun invalidDraftsCannotBecomeRevisions() {
        for (text in listOf("{", "[$raw]", raw.toString().replace("\"Original\"", "\"Original\",\"bookSourceName\":\"duplicate\""),
                JsonObject(raw - "ruleContent").toString(), JsonObject(raw + ("ruleToc" to JsonObject(emptyMap()))).toString())) {
            assertTrue(runCatching { SourceDefinitionEdits.prepare(definition, text) }.exceptionOrNull() is SourceEditException)
        }
    }

    @Test fun replacementRejectsNonDomainInputs() {
        for (domain in listOf("old.example", "new.example/path", "new.example:443", "https://user@new.example/", "new.example?q=x", "")) {
            assertTrue(domain, runCatching { SourceDefinitionEdits.previewDomain(raw.toString(), "old.example", domain) }.isFailure)
        }
    }
}
