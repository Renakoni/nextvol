package indi.renakoni.nextvol.sourceexecution

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.michaelbull.result.get
import hnovel.content.*
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.di.WebDataSourceModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReadingPanelInstrumentedTest {
    @Test fun currentChapterAndSingleWriteCrossProductionBinderWithoutChangingOtherPanels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RuleSourceFixture().use { fixture ->
            val writes = AtomicInteger()
            val delegate = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/action") {
                        assertEquals("POST", request.method)
                        writes.incrementAndGet()
                        return MockResponse().setBody("ok")
                    }
                    return delegate.dispatch(request)
                }
            }
            val root = File(context.cacheDir, "reading-panel-${UUID.randomUUID()}").apply { mkdirs() }
            val host = object : ContextWrapper(context) { override fun getFilesDir() = root }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val executor = AndroidIsolatedExecutor(context, fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, WebDataSourceModule.provideRuleTaskRunner(executor))
            val login = SourceLoginService(sources, accounts)
            try {
                val post = "java.post('${fixture.server.url("/action")}', 'op=fixture', {'Content-Type':'application/x-www-form-urlencoded'});"
                val raw = JsonObject(fixture.raw() + mapOf(
                    "loginUrl" to JsonPrimitive("function login(){}"),
                    "loginUi" to JsonPrimitive("""@js:JSON.stringify([
                        {name:'context',default:book===null?'none':book.durChapterTitle+':'+book.readingProgress+':'+chapter.url},
                        {name:'same',type:'button',action:${JsonPrimitive("if(!isLongClick)throw 'missing long press';" + post + "java.refreshBookInfo();java.refreshBookToc();")}},
                        {name:'same',type:'button',action:"if(isLongClick)throw 'stale long press';java.refreshContent();"},
                        {name:'fail',type:'button',action:${JsonPrimitive(post + "throw 'fixture-failure';")}}]);
                    """.trimIndent())))
                val preview = sources.importer.preview(raw.toString(), EXTENSION_PROFILE)
                val saved = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val book = fixture.server.url("/book/one").toString()
                assertTrue(runtime.getBookInformation(book).isOk)
                val chapters = runtime.getBookVolumes(book).get()!!.volumes.single().chapters
                assertTrue(runtime.getChapterContent(chapters[0].id, book).isOk)
                val panel = login.begin(id, reading = LoginReadingContext(book, chapters[0].id, .25f))
                val other = login.begin(id, reading = LoginReadingContext(book, chapters[1].id, .75f))
                val form = login.form(panel)
                assertEquals("One:0.25:/c/1", form.fields[0].default)
                assertEquals("Two:0.75:/c/2", login.form(other).fields[0].default)
                val buttons = form.fields.filter { it.name == "same" }
                assertEquals(setOf(LoginRefreshTarget.BookInformation, LoginRefreshTarget.Directory),
                    login.submit(panel, form.values, buttons[0].id, form.id, longClick = true).refreshTargets)
                assertEquals(1, writes.get())
                assertEquals(setOf(LoginRefreshTarget.Content), login.submit(panel, form.values, buttons[1].id, form.id).refreshTargets)
                assertEquals(1, writes.get())
                assertTrue(runCatching { login.submit(panel, form.values, form.fields.last().id, form.id) }.isFailure)
                assertEquals(2, writes.get())
                login.cancel(panel)
                assertTrue(runCatching { login.submit(panel, form.values, buttons[0].id, form.id) }.isFailure)
                assertEquals(2, writes.get())
                assertEquals("Two:0.75:/c/2", login.form(other).fields[0].default)
                login.logout(id)
                assertTrue(runCatching { login.submit(other, emptyMap()) }.isFailure)
                login.cancel(other)
                val noBook = login.begin(id)
                assertEquals("none", login.form(noBook).fields[0].default)
                login.cancel(noBook)
            } finally { sources.stop(); executor.close(); root.deleteRecursively() }
        }
    }
}
