package indi.renakoni.nextvol.data.content

import android.app.Application
import indi.renakoni.nextvol.ui.book.reader.content.componet.ErrorContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ContentDecodingContractTest {
    private val host = ContentTestHost()

    @Test fun absentFieldsBecomeOrderedErrorComponentsAndEmptyArraysStayEmpty() {
        val messages = render("""{"components":[{}, {"id":"simple_text"}, {"id":"unknown","data":{}}]}""")
            .map { (it as ErrorContentComponent).data.message }
        assertEquals(listOf("component id not found", "component data not found\nid=lightnovelreader:simple_text", "component class not found\nid=lightnovelreader:unknown"), messages)
        assertEquals("error to load components from json", (render("{}").single() as ErrorContentComponent).data.message)
        assertTrue(render("""{"components":[]}""").isEmpty())
    }

    @Test fun strictExportRejectsEveryMalformedOrUnsupportedComponent() {
        for (input in listOf("{}", """{"components":{}}""", """{"components":[1]}""",
            """{"components":[{}]}""", """{"components":[{"id":"simple_text"}]}""",
            """{"components":[{"id":"unknown:text","data":{}}]}""")) {
            assertThrows(IllegalArgumentException::class.java) { host.decoder.decodeForExport(json(input)) {} }
        }
    }

    @Test fun invalidJsonShapesBecomeRenderingErrorsAndDataOnlyDecodingSkipsThem() {
        val exported = mutableListOf<AbstractContentComponentData>()
        for (input in listOf("""{"components":{}}""", """{"components":[${entry("body")},1]}""",
            """{"components":[{"id":{},"data":{}}]}""", """{"components":[{"id":"simple_text","data":[]}]}""")) {
            assertTrue(render(input).all { it is ErrorContentComponent || it is SimpleTextComponent })
            host.decoder.getDataFromJsonObject(json(input), exported::add)
        }
        assertEquals(listOf(SimpleTextComponentData("body")), exported)
    }

    @Test fun constructionPreservesOrderAndRecoversFromAnOrdinaryFailure() {
        val seen = mutableListOf<String>()
        val output = host.decoder.decodeComponents(json("""{"components":[${entry("bad")},${entry("body")}]}"""), {
            val text = (it as SimpleTextComponentData).text
            seen += text
            check(text != "bad")
            text
        }, { "error" })
        assertEquals(listOf("bad", "body"), seen)
        assertEquals(listOf("error", "body"), output)
    }

    @Test fun cancellationAndFatalErrorsAreNotConvertedToErrorComponents() {
        for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            assertSame(failure, assertThrows(failure.javaClass) {
                host.decoder.decodeComponents(json("""{"components":[${entry("body")}]}"""), { throw failure }, { "error" })
            })
        }
    }

    private fun render(input: String) = host.renderer.getContentDataFromJson(json(input)).components
    private fun json(input: String): JsonObject = Json.parseToJsonElement(input).jsonObject
    private fun entry(text: String) = """{"id":"lightnovelreader:simple_text","data":{"text":"$text"}}"""
}
