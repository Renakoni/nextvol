package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DiscoveryStorageTest {
    private class Writes : StorageCipher {
        val records = mutableListOf<BookRecord>()
        override fun seal(bytes: ByteArray, identity: String): ByteArray {
            val text = bytes.toString(Charsets.UTF_8)
            if (text.startsWith("{\"revision\":")) records += Json.decodeFromString(BookRecord.serializer(), text)
            return bytes
        }
        override fun open(bytes: ByteArray, identity: String) = bytes
    }
    private fun definition(raw: JsonObject, name: String = "h2@text") = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("Books::/search"),
        "ruleExplore" to buildJsonObject {
            put("bookList", "li"); put("name", name); put("author", "b@text"); put("bookUrl", "a@href")
        }
    ))

    @Test fun identicalOverviewsDoNotRewriteBooksAndDetailsStillLoad() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture -> fixture.source { definition(it) }.use { source ->
            val first = source.openDiscovery("one").openPages("/search", emptyMap()).page(1)
            val second = source.openDiscovery("two").openPages("/search", emptyMap()).page(1)
            assertEquals(first.books, second.books)
            assertEquals(1, writes.records.size)
            assertEquals("Same author", source.information(first.books.single().id).author)
            assertTrue(writes.records.last().informationLoaded)
            val saved = writes.records.size
            source.openDiscovery("after-details").openPages("/search", emptyMap()).page(1)
            assertEquals(saved, writes.records.size)
        } }
    }

    @Test fun aSingleBookOverviewAlsoSkipsIdenticalRecords() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture -> fixture.source {
            JsonObject(definition(it) + ("bookUrlPattern" to JsonPrimitive(".*/book/one")))
        }.use { source ->
            val first = source.openDiscovery("one").openPages("/book/one", emptyMap()).page(1)
            val second = source.openDiscovery("two").openPages("/book/one", emptyMap()).page(1)
            assertEquals(first.books, second.books)
            assertEquals("Same title", first.books.single().title)
            assertEquals(fixture.server.url("/cover.png").toString(), first.books.single().coverUrl)
            assertEquals(1, writes.records.size)
            assertEquals(first.books.single().coverUrl, writes.records.single().book.coverUrl)
            assertFalse(writes.records.single().informationLoaded)
        } }
    }

    @Test fun identicalLegacyRecordsRemainReadableAndDetailsMigrateThem() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { definition(it) }.use { source ->
            val first = source.openDiscovery("one").openPages("/search", emptyMap()).page(1)
            val owner = fixture.broker.open(SourceScope("rules", source.definition.sourceId, source.definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
            val current = StorageRequest(StorageArea.BookState, "content/book/" + digest(first.books.single().id))
            val legacy = current.copy(area = StorageArea.Config)
            val saved = (owner.read(current) as StorageResult.Value).value
            assertNotNull(saved)
            assertTrue(owner.write(legacy.copy(value = saved)) is StorageResult.Value)
            assertTrue(owner.write(current) is StorageResult.Value)
            assertEquals(first.books, source.openDiscovery("two").openPages("/search", emptyMap()).page(1).books)
            assertEquals("Same author", source.information(first.books.single().id).author)
            assertNotNull((owner.read(current) as StorageResult.Value).value)
            assertNull((owner.read(legacy) as StorageResult.Value).value)
        } }
    }

    @Test fun accountAndRevisionChangesStillPersistANewRecord() = runBlocking {
        for (account in listOf(false, true)) {
            val writes = Writes()
            RuleSourceFixture(cipher = writes).use { fixture ->
                val old = fixture.source { definition(it) }
                old.openDiscovery("one").openPages("/search", emptyMap()).page(1)
                old.close()
                val next = if (account) {
                    val definition = old.definition
                    val owner = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile, 1),
                        listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                    val identity = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules", 1)
                    createRuleSource(definition, identity, fixture.authority, owner, fixture.runner)
                } else fixture.source { JsonObject(definition(it) + ("bookSourceComment" to JsonPrimitive("new revision"))) }
                next.use { it.openDiscovery("two").openPages("/search", emptyMap()).page(1) }
                assertEquals(2, writes.records.size)
                assertNotEquals(writes.records[0], writes.records[1])
            }
        }
    }

    @Test fun changedDeferredFieldsWithTheSameTitleAndUrlStillReplaceThePreview() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture ->
            var author = "First author"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    "<li><h2>Same title</h2><a href='/book/one'>Read</a><b>$author</b></li>")
            }
            fixture.source { definition(it) }.use { source ->
                val first = source.openDiscovery("one").openPages("/search", emptyMap()).page(1)
                author = "Second author"
                val second = source.openDiscovery("two").openPages("/search", emptyMap()).page(1)
                assertEquals(first.books, second.books)
                assertEquals(2, writes.records.size)
                assertNotEquals(writes.records[0].preview, writes.records[1].preview)
                assertEquals(writes.records[0].book, writes.records[1].book)
            }
        }
    }

    @Test fun scriptVariableChangesAreSavedEvenWhenVisibleFieldsAreIdentical() = runBlocking {
        val writes = Writes()
        RuleSourceFixture(cipher = writes).use { fixture ->
            var token = "first"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    "<li data-token='$token'><h2>Same title</h2><a href='/book/one'>Read</a><b>Same author</b></li>")
            }
            fixture.source { definition(it, "@js:var title=java.getString('h2@text');book.putVariable('token',java.getString('@data-token'));title") }.use { source ->
                source.openDiscovery("one").openPages("/search", emptyMap()).page(1)
                token = "second"
                source.openDiscovery("two").openPages("/search", emptyMap()).page(1)
                assertEquals(2, writes.records.size)
                assertEquals(listOf("first", "second"), writes.records.map { it.book.state.variables["token"] })
                assertEquals(writes.records[0].book.title, writes.records[1].book.title)
            }
        }
    }

    @Test fun bookAndAliasDigestsMatchExistingStorageKeys() {
        for (id in listOf("https://example.test/book/1", "https://example.test/中文?q=é🌙")) {
            val legacy = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(legacy, digest(id))
        }
    }
}
