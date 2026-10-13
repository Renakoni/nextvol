package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleDiscoveryCatalogTest {
    @Test fun gsonCatalogGrammarAcceptsSingleQuotesAndUnquotedKeysWithoutExecutingCode() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (rule in listOf("[{title:'Recent',url:'/search'}]", "@js:\"[{title:'Recent',url:'/search'}]\"")) {
                fixture.source { raw -> JsonObject(raw + mapOf("exploreUrl" to JsonPrimitive(rule),
                    "ruleExplore" to raw.getValue("ruleSearch"))) }.use { source ->
                    val catalog = source.openDiscovery("lenient").catalog()
                    assertEquals("Recent", catalog.rows.single().title)
                    assertEquals("/search", catalog.rows.single().url)
                }
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun largeCatalogDoesNotInheritTheRequestOptions64KiBBudget() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rows = JsonArray(List(400) { i -> buildJsonObject {
                put("title", "Category $i"); put("url", "/search?query=" + "x".repeat(200) + "&row=$i")
            } })
            assertTrue(rows.toString().length > 65536)
            fixture.source { definition(it, rows) }.use { source ->
                assertEquals(400, source.openDiscovery("large").catalog().rows.size)
            }
        }
    }

    @Test fun authorCatalogsAboveOneThousandEntriesKeepTheLastTarget() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val raw = (1..1119).joinToString("\n") { "Author $it::/writer/$it" }
            fixture.source { JsonObject(it + ("exploreUrl" to JsonPrimitive(raw))) }.use { source ->
                val rows = source.openDiscovery("authors").catalog().rows
                assertEquals(1119, rows.size)
                assertEquals("/writer/1119", rows.last().url)
            }
        }
    }

    @Test fun unnamedUrlEntriesRemainNavigableAndInertBlankRowsNeedNoStyle() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { definition(it, Json.parseToJsonElement("""[
                {"title":"","url":""},{"title":"","url":null,"style":{}},{"title":"","url":"/search"},
                {"title":"Recent","url":"/search?sort=new"}
            ]""").jsonArray) }.use { source ->
                val rows = source.openDiscovery("unnamed").catalog().rows
                assertEquals(listOf("", "Recent"), rows.map { it.title })
                assertEquals(listOf("/search", "/search?sort=new"), rows.map { it.url })
                assertEquals("exploreUrl[2]", rows.first().field)
            }
        }
    }

    private fun definition(raw: JsonObject, rows: JsonArray) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive(rows.toString()), "ruleExplore" to raw.getValue("ruleSearch")))

    @Test fun malformedResultFilterScopeReportsItsField() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (scope in listOf("[]", "[1]", "[null]", "[\"\"]", "\"/search\"")) {
                fixture.source { raw -> JsonObject(definition(raw, JsonArray(emptyList())) +
                    ("exploreScreen" to JsonPrimitive("""[{"title":"Sort","type":"text","targetPrefixes":$scope}]"""))) }.use { source ->
                    val error = failure { source.openDiscovery("invalid-scope").catalog() }
                    assertEquals(ContentError.InvalidRule, error.code)
                    assertEquals("exploreScreen[0].targetPrefixes", error.field)
                }
            }
        }
    }

    @Test fun qidianCatalogRetainsAll326RowsAndEveryTarget() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val input = DiscoveryCatalogFixtures.rows(0)
            fixture.source { definition(it, input) }.use { source ->
                val session = source.openDiscovery("qidian")
                val catalog = session.catalog()
                assertEquals(326, catalog.rows.size)
                assertEquals(input.map { it.jsonObject.getValue("title").jsonPrimitive.content }, catalog.rows.map { it.title })
                assertEquals(input.map { it.jsonObject.getValue("url").jsonPrimitive.content }, catalog.rows.map { it.url })
                assertEquals(322, catalog.rows.count { it.url.isNotBlank() })
                assertEquals(326, catalog.rows.map { it.id }.toSet().size)
                assertEquals(catalog, session.catalog(refresh = true))
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    @Test fun dubuStyleSpacerDoesNotRejectTheCatalog() = separators(9, 14)
    @Test fun aituStyleSpacersDoNotRejectTheCatalog() = separators(17, 72)
    @Test fun jiuaiStyleSpacersDoNotRejectTheCatalog() = separators(19, 22)

    @Test fun unnamedTargetsArePreservedAndUnknownSchemaKeepsItsOriginalArrayLocation() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source("yueyou") { definition(it, DiscoveryCatalogFixtures.rows(18)) }.use { source ->
                val input = DiscoveryCatalogFixtures.rows(18)
                val rows = source.openDiscovery("yueyou").catalog().rows
                assertEquals(input.map { it.jsonObject.getValue("url").jsonPrimitive.content }.filter(String::isNotBlank),
                    rows.map { it.url }.filter(String::isNotBlank))
                assertTrue(rows.any { it.title.isBlank() && it.url.isNotBlank() })
            }
            val spacer = DiscoveryCatalogFixtures.rows(9).last().jsonObject
            for ((row, field) in listOf(
                JsonObject(spacer + ("unknown" to JsonPrimitive(true))) to "unknown",
                JsonObject(spacer + ("type" to JsonPrimitive("nativeView"))) to "type",
                JsonObject(spacer + ("type" to JsonPrimitive("text"))) to "title",
                JsonObject(spacer + ("action" to JsonPrimitive("java.open('login')"))) to "title",
                JsonObject(spacer + ("viewName" to JsonPrimitive("'Missing name'"))) to "title",
                JsonObject(spacer + ("style" to JsonPrimitive("not an object"))) to "title",
            )) fixture.source("invalid-$field") { definition(it, JsonArray(listOf(spacer, row))) }.use { source ->
                val error = failure { source.openDiscovery("invalid").catalog() }
                assertEquals(ContentError.InvalidRule, error.code)
                assertEquals("exploreUrl[1].$field", error.field)
            }
        }
    }

    @Test fun completeCatalogHasAnExplicitLimitAndNeverTruncatesExcessRows() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val maximum = RuleDiscoveryCatalogParser.MAX_CATALOG_ROWS
            fixture.source("maximum") { definition(it, urls(maximum)) }.use { source ->
                assertEquals(maximum, source.openDiscovery("maximum").catalog().rows.size)
            }
            val spacer = DiscoveryCatalogFixtures.rows(9).last()
            for (rows in listOf(urls(maximum + 1), JsonArray(List(maximum + 1) { spacer })))
                fixture.source("excess") { definition(it, rows) }.use { source ->
                    val error = failure { source.openDiscovery("excess").catalog() }
                    assertEquals(ContentError.Limit, error.code)
                    assertEquals("exploreUrl", error.field)
                }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun combinedLimitCountsScreenControls() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val extra = buildJsonObject { put("exploreScreen", """[{"id":"button","title":"Action","type":"button"}]""") }
            fixture.source { JsonObject(definition(it, urls(RuleDiscoveryCatalogParser.MAX_CATALOG_ROWS)) + extra) }.use { source ->
                val error = failure { source.openDiscovery("combined").catalog() }
                assertEquals(ContentError.Limit, error.code)
                assertEquals("exploreUrl", error.field)
            }
        }
    }

    @Test fun catalogueGrowthDoesNotEnlargeInputStateOrChoiceBudgets() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (count in listOf(128, 129)) {
                val inputs = List(count) { i -> buildJsonObject { put("title", "Input$i"); put("type", "text") } }
                fixture.source("inputs-$count") { definition(it, JsonArray(urls(326) + inputs)) }.use { source ->
                    if (count == 128) {
                        val catalog = source.openDiscovery("allowed").catalog()
                        assertEquals(454, catalog.rows.size)
                        assertEquals(128, catalog.values.size)
                    } else {
                        val error = failure { source.openDiscovery("excess").catalog() }
                        assertEquals(ContentError.Limit, error.code)
                        assertEquals("infoMap", error.field)
                    }
                }
            }
            val choice = buildJsonObject {
                put("title", "Choice"); put("type", "select")
                putJsonArray("chars") { repeat(65) { add("$it") } }
            }
            fixture.source { definition(it, JsonArray(urls(326) + choice)) }.use { source ->
                assertEquals("exploreUrl[326].chars", failure { source.openDiscovery("choices").catalog() }.field)
            }
        }
    }

    @Test fun dynamicCatalogUsesTheSameSizeAndSeparatorContract() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(definition(raw, JsonArray(emptyList())) +
                ("exploreUrl" to JsonPrimitive("""
                    @js:var rows=[{title:'',url:'',style:{}}];
                    for(var i=0;i<326;i++)rows.push({title:'Books '+i,url:'/search?row='+i});rows
                """.trimIndent()))) }.use { source ->
                val catalog = source.openDiscovery("dynamic").catalog()
                assertEquals(326, catalog.rows.size)
                assertEquals("Books 325", catalog.rows.last().title)
                assertEquals("/search?row=325", catalog.rows.last().url)
                assertEquals("exploreUrl[326]", catalog.rows.last().field)
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    private fun urls(count: Int) = JsonArray(List(count) { index ->
        buildJsonObject { put("title", "Books $index"); put("url", "/search?row=$index") }
    })

    private suspend fun failure(block: suspend () -> Unit): SourceContentException = try {
        block(); throw AssertionError("Expected an explicit discovery failure")
    } catch (error: SourceContentException) { error }

    private fun separators(index: Int, expected: Int) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val input = DiscoveryCatalogFixtures.rows(index)
            val meaningful = JsonArray(input.filter { it.jsonObject.getValue("title").jsonPrimitive.content.isNotBlank() })
            fixture.source("spaced") { definition(it, input) }.use { source ->
                val catalog = source.openDiscovery("spaced").catalog()
                assertEquals(expected, catalog.rows.size)
                assertEquals(meaningful.map { it.jsonObject.getValue("title").jsonPrimitive.content }, catalog.rows.map { it.title })
                assertEquals(meaningful.map { it.jsonObject.getValue("url").jsonPrimitive.content }, catalog.rows.map { it.url })
                fixture.source("compact") { definition(it, meaningful) }.use { compact ->
                    assertEquals(compact.openDiscovery("compact").catalog().rows.map { it.id }, catalog.rows.map { it.id })
                }
                assertEquals(0, fixture.documents.get())
            }
        }
    }
}
