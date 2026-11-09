package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ContentStorageTest {
    private class Writes : StorageCipher {
        val records = mutableListOf<BookRecord>()
        override fun seal(bytes: ByteArray, identity: String): ByteArray {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.startsWith("{\"revision\":")) records += Json.decodeFromString(BookRecord.serializer(), text)
            return bytes
        }
        override fun open(bytes: ByteArray, identity: String) = bytes
    }

    @Test fun unchangedContentSkipsWritesButItsFirstDirectoryIsSaved() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture ->
            val rules = { raw: JsonObject -> JsonObject(raw + ("ruleContent" to buildJsonObject { put("content", "article@html") })) }
            val id = fixture.server.url("/book/one").toString()
            val chapter = fixture.server.url("/c/1").toString()
            fixture.source(customize = rules).use { source ->
                source.information(id, inferUpdate = false)
                assertTrue(writes.records.last().chapters.isEmpty())
                val before = writes.records.size
                source.content(id, chapter)
                assertEquals(before + 1, writes.records.size)
                assertEquals(3, writes.records.last().chapters.size)
            }
            fixture.source(customize = rules).use { source ->
                val saved = writes.records.size
                repeat(2) { assertTrue(source.content(id, chapter).parts.any { it.text == "A first" }) }
                assertEquals(saved, writes.records.size)
            }
        }
    }

    @Test fun changedPartialChapterIsSavedAndUnchangedRereadsKeepBothSnapshots() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture ->
            val rules = { raw: JsonObject -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "article@html")
                put("title", "@js:chapter.putVariable('read','yes');'Changed title'")
            })) }
            val id = fixture.server.url("/book/one").toString()
            val chapter = fixture.server.url("/c/1").toString()
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/toc/2")
                    MockResponse().setResponseCode(502) else original.dispatch(request)
            }
            fixture.source(customize = rules).use { source ->
                assertTrue(runCatching { source.directory(id) }.exceptionOrNull() is PartialDirectoryException)
                val before = writes.records.size
                assertEquals("Changed title", source.content(id, chapter).title)
                assertEquals(before + 1, writes.records.size)
                assertTrue(writes.records.last().chapters.isEmpty())
                val saved = writes.records.last().partialDirectory!!.chapters.single { !it.isVolume }
                assertEquals("Changed title", saved.title)
                assertEquals("yes", saved.state.variables["read"])
            }
            fixture.source(customize = rules).use { source ->
                val before = writes.records.size
                assertEquals("Changed title", source.content(id, chapter).title)
                assertEquals(before, writes.records.size)
            }
        }
    }
}
