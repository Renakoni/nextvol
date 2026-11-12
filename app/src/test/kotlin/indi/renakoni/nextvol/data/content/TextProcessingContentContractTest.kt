package indi.renakoni.nextvol.data.content

import android.app.Application
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class TextProcessingContentContractTest {
    @Test
    fun chapterProcessingPreservesProcessorOrderMetadataExtensionsAndIncompleteItems() {
        val registry = ContentComponentRegistry()
        val processing = TextProcessingRepository(
            mockk { every { enabled } returns false },
            mockk { every { enabled } returns false },
            registry,
        )
        val events = mutableListOf<String>()
        fun processor(suffix: String) = object : TextProcessor {
            override val enabled = true
            override fun processText(text: String) = text + suffix
            override fun processChapterContent(bookId: String, chapterContent: ChapterContent, componentProcessor: ComponentProcessor): ChapterContent {
                events += "$bookId/$suffix"
                return super.processChapterContent(bookId, chapterContent, componentProcessor)
            }
        }
        processing.registerProcessors(Identifier("fixture", "first"), processor("A"))
        processing.registerProcessors(Identifier("fixture", "first"), processor("ignored"))
        processing.registerProcessors(Identifier("fixture", "second"), processor("B"))
        val chapter = ChapterContent("chapter", "title", Json.parseToJsonElement("""{
            "metadata":"root-extension",
            "components":[
                {"id":"lightnovelreader:simple_text","data":{"text":"body"},"extra":1},
                {"id":"simple_text","data":{"text":"short"}},
                {"id":"fixture:unknown","data":{"value":2}},
                {"data":{"text":"missing id"}},
                {"id":"fixture:missing-data"},
                7,
                []
            ]
        }""").jsonObject, "previous", "next")
        val expected = chapter.copy(content = Json.parseToJsonElement("""{
            "metadata":"root-extension",
            "components":[
            {"id":"lightnovelreader:simple_text","data":{"text":"bodyAB"},"extra":1},
            {"id":"simple_text","data":{"text":"short"}},
            {"id":"fixture:unknown","data":{"value":2}},
            {"data":{"text":"missing id"}},
            {"id":"fixture:missing-data"},
            7,
            []
        ]}""").jsonObject)

        val decoder = ContentJsonDecoder(registry)
        val output = processing.processChapterContent("book") { chapter }
        assertEquals(expected, output)
        val rendered = decoder.decodeComponents(
            output.content,
            create = { (it as SimpleTextComponentData).text },
            error = { "error" },
        )
        assertEquals(listOf("bodyAB", "short", "error", "error", "error", "error", "error"), rendered)
        val exported = mutableListOf<String>()
        decoder.getDataFromJsonObject(output.content) { exported += (it as SimpleTextComponentData).text }
        assertEquals(listOf("bodyAB"), exported)
        assertEquals(listOf("book/A", "book/B"), events)
    }

    @Test
    fun missingOrNonArrayComponentsLeaveTheRootJsonUnchanged() {
        val registry = ContentComponentRegistry()
        for (input in listOf("""{"metadata":"missing"}""", """{"metadata":"wrong","components":{}}""")) {
            val original = Json.parseToJsonElement(input).jsonObject
            val processor = ComponentProcessor(registry.serializeMap, registry.dataKClassMap, original)

            processor.process<SimpleTextComponentData> { it.copy(text = it.text + "changed") }

            assertEquals(original, processor.get())
            val decoder = ContentJsonDecoder(registry)
            assertEquals(
                listOf("error"),
                decoder.decodeComponents(processor.get(), { "unexpected" }, { "error" }),
            )
            decoder.getDataFromJsonObject(processor.get()) { error("No valid component to export") }
        }
    }
}
