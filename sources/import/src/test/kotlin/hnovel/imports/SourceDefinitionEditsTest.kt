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
        val edited = SourceDefinitionEdits.prepare(definition, raw.toString().replace("old.example", "new.example"), 8)
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

    @Test fun invalidDraftsCannotBecomeRevisions() {
        for (text in listOf("{", "[$raw]", raw.toString().replace("\"Original\"", "\"Original\",\"bookSourceName\":\"duplicate\""),
                JsonObject(raw - "ruleContent").toString(), JsonObject(raw + ("ruleToc" to JsonObject(emptyMap()))).toString())) {
            assertTrue(runCatching { SourceDefinitionEdits.prepare(definition, text) }.exceptionOrNull() is SourceEditException)
        }
    }

}
