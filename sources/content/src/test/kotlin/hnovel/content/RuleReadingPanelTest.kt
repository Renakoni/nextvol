package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleReadingPanelTest {
    private val ui = """@js:
        JSON.stringify([{name:'snapshot',default:book===null?'none':JSON.stringify([book.bookUrl,
          chapter===null?null:chapter.url,book.durChapterIndex,book.durChapterTitle,book.readingProgress,book.getVariable('shared')])},
          {name:'same',type:'button',action:'java.refreshBookInfo();java.refreshBookInfo();java.refreshBookToc();'},
          {name:'same',type:'button',action:'java.refreshContent();'},
          {name:'fail',type:'button',action:'java.refreshBookInfo();throw "failed";'}]);
    """.trimIndent()

    @Test fun panelFreezesChapterAndBookVariablesAndDispatchesOpaqueButtons() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){}"), "loginUi" to JsonPrimitive(ui))) }.use { source ->
                val book = source.search("fixture").single()
                val chapters = source.directory(book.id).filterNot { it.isVolume }
                source.content(book.id, chapters[0].id)
                source.openLoginSession(LoginReadingContext(book.id, chapters[0].id, .25f)).use { first ->
                    source.openLoginSession(LoginReadingContext(book.id, chapters[1].id, .75f)).use { second ->
                        val form = first.loginForm()
                        val snapshot = Json.parseToJsonElement(form.values.getValue("snapshot")).jsonArray
                        assertEquals("/c/1", snapshot[1].jsonPrimitive.content)
                        assertEquals(0, snapshot[2].jsonPrimitive.int)
                        assertEquals("One", snapshot[3].jsonPrimitive.content)
                        assertEquals(.25f, snapshot[4].jsonPrimitive.float)
                        assertEquals("from-search", snapshot[5].jsonPrimitive.content)
                        val other = Json.parseToJsonElement(second.loginForm().values.getValue("snapshot")).jsonArray
                        assertEquals("/c/2", other[1].jsonPrimitive.content)
                        val buttons = form.fields.filter { it.name == "same" }
                        assertNotEquals(buttons[0].id, buttons[1].id)
                        assertEquals(setOf(LoginRefreshTarget.BookInformation, LoginRefreshTarget.Directory),
                            first.login(form.values, buttons[0].id, form.id).refreshTargets)
                        assertEquals(setOf(LoginRefreshTarget.Content), first.login(form.values, buttons[1].id, form.id).refreshTargets)
                        assertEquals(form.values, first.loginForm().values)
                        assertTrue(runCatching { first.login(form.values, form.fields.last().id, form.id) }.isFailure)
                    }
                }
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    assertEquals("none", form.fields.first().default)
                    assertTrue(runCatching { panel.login(form.values, form.fields[1].id, form.id) }.isFailure)
                    assertTrue(panel.login(form.values).refreshTargets.isEmpty())
                }
            }
        }
    }

    @Test fun invalidOrClosedReadingContextCannotRunAnAction() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { raw -> JsonObject(raw + mapOf(
                "loginUrl" to JsonPrimitive("function login(){}"), "loginUi" to JsonPrimitive(ui))) }.use { source ->
                val book = source.search("fixture").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                assertTrue(runCatching { source.openLoginSession(LoginReadingContext(book.id, "unknown")) }.isFailure)
                assertTrue(runCatching { source.openLoginSession(LoginReadingContext("unknown", null)) }.isFailure)
                val panel = source.openLoginSession(LoginReadingContext(book.id, chapter.id))
                val form = panel.loginForm()
                panel.close()
                assertTrue(runCatching { panel.login(form.values, form.fields[1].id, form.id) }.isFailure)
                assertNotNull(source.loginForm())
                assertEquals("Same title", source.search("after-panel").single().title)
            }
        }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f))
            assertTrue(runCatching { LoginReadingContext("book", null, value) }.isFailure)
    }
}
