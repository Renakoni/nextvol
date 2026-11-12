package hnovel.rules

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuleEvaluatorTest {
    @Test fun jsonValuesKeepScalarTextSeparateFromStructuredArrayEntries() {
        assertEquals(RuleValue.Empty, JsonNull.toRuleValue())
        assertEquals(RuleValue.Text("7"), JsonPrimitive(7).toRuleValue())
        assertEquals(RuleValue.Text("true"), JsonPrimitive(true).toRuleValue())
        assertEquals(RuleValue.Items(listOf(
            RuleValue.Node("7", InputKind.Json), RuleValue.Node("true", InputKind.Json),
            RuleValue.Node("null", InputKind.Json), RuleValue.Text("line\ntext"),
            RuleValue.Items(listOf(RuleValue.Node("2", InputKind.Json), RuleValue.Node("3", InputKind.Json))),
            RuleValue.Node("""{"id":4}""", InputKind.Json),
        )), Json.parseToJsonElement("""[7,true,null,"line\ntext",[2,3],{"id":4}]""").toRuleValue())
    }

    @Test fun textJoinsNestedValuesWhileItemsOnlyUnwrapsTheOuterList() {
        val captures = RuleValue.Captures(listOf("whole", "group"))
        val values = listOf(RuleValue.Text("text"), RuleValue.Node("<p>node</p>", InputKind.Html),
            RuleValue.Items(listOf(captures, RuleValue.Empty)))
        val nested = RuleValue.Items(values)
        assertEquals("text\n<p>node</p>\nwhole\ngroup\n", nested.text())
        assertEquals(values, nested.items())
        assertEquals(listOf(captures), captures.items())
        assertEquals(emptyList<RuleValue>(), RuleValue.Empty.items())
    }

    @Test fun selectorTemplateReplacementsKeepRegexCharacterClassesLiteral() {
        val input = RuleValue.Text("""<a onclick="showMore('Book intro')">Open</a>""")
        val selector = """@@a@onclick##showMore[(]['](.*)['][)]##Intro: ${'$'}1"""
        assertEquals("Intro: Book intro", value(selector, input, OutputKind.Text).text())
        assertEquals("Intro: Book intro", value("{{$selector}}", input, OutputKind.Text).text())
    }
    @Test fun regexExtractionTreatsQuotesAndCharacterClassesAsPatternSyntax() {
        val input = RuleValue.Text("""<a href="/xs1">One</a><a href="/xs2">Two</a>""")
        val rows = value(""":href="([^"]+xs[^"]+)">([^<]+)""", input, OutputKind.Elements).items()
        assertEquals(listOf("/xs1", "/xs2"), rows.map { (it as RuleValue.Captures).groups[1] })
        assertEquals(listOf("One", "Two"), rows.map { (it as RuleValue.Captures).groups[2] })
        val filtered = value(""":[a-z&&[^aeiou]]+&&([b-d]+)""", RuleValue.Text("abc def"), OutputKind.Elements).items()
        assertEquals(listOf("bcd"), filtered.map { (it as RuleValue.Captures).groups[1] })
        assertEquals("(\"", (value(""":([("]+)""", RuleValue.Text("(\""), OutputKind.Element) as RuleValue.Captures).groups[1])
    }
    private val html = RuleValue.Text("<h1>Library</h1><div><a class='book' href='/1'>Alpha<b>One</b></a><a class='book' href='/2'>Beta</a></div>")
    private fun value(rule: String, input: RuleValue = html, output: OutputKind = OutputKind.TextList,
        context: RuleContext = RuleContext("a", baseUrl = "https://fixture.invalid/path/")): RuleValue {
        val result = RuleEvaluator().evaluate(rule, input, context, output)
        assertTrue(result.toString(), result is RuleResult.Success)
        return (result as RuleResult.Success).value
    }

    @Test fun htmlIndexExclusionOwnTextAndNodeRoundTrip() {
        assertEquals("Beta", value("class.book.-1@text", output = OutputKind.Text).text())
        assertEquals("AlphaOne", value("class.book!1@text", output = OutputKind.Text).text())
        assertEquals(listOf("Alpha", "Beta"), value("class.book@ownText").items().map { it.text() })
        val node = value("class.book.0", output = OutputKind.Elements).items().single()
        assertEquals("AlphaOne", value("text", node, OutputKind.Text).text())
        assertTrue(value("class.book@html").text().contains("<b>One</b>"))
    }

    @Test fun selectedTableRowsKeepTheirRootAttributesAfterSerialization() {
        val input = RuleValue.Text("<table><tbody><tr data-volume='true'><td><a href='/chapter'>One</a></td></tr></tbody></table>")
        val node = value("tr", input, OutputKind.Elements).items().single()
        val wire = kotlinx.serialization.json.Json.encodeToString(RuleValue.serializer(), node)
        val restored = kotlinx.serialization.json.Json.decodeFromString(RuleValue.serializer(), wire)
        assertEquals("true", value("@data-volume", restored, OutputKind.Text).text())
        assertEquals("One", value("td@text", restored, OutputKind.Text).text())
        assertEquals("https://fixture.invalid/chapter", value("a@href", restored, OutputKind.Url).text())
    }

    @Test fun jsonAndXmlSelectionTemplatesAndEmptyFallback() {
        val json = RuleValue.Node("""{"items":[{"title":"A"},{"title":"B"}],"suffix":"!"}""", InputKind.Json)
        assertEquals("A\nB", value("$.items[*].title", json, OutputKind.Text).text())
        assertEquals("Name: !", value("Name: {$.suffix}", json, OutputKind.Text).text())
        assertEquals(listOf("A", "B"), value("$.absent||$.items[*].title", json).items().map { it.text() })
        val node = value("$.items[0]", json, OutputKind.Element).items().single()
        assertEquals("A", value("$.title", node, OutputKind.Text).text())
        assertEquals("A", value("//book/text()", RuleValue.Text("<?xml version='1.0'?><root><book>A</book></root>"), OutputKind.Text).text())
    }

    @Test fun urlsUseContextBaseAndScalarSelectsFirstWithBlankBaseFallback() {
        assertEquals("https://fixture.invalid/1", value("class.book@href", output = OutputKind.Url).text())
        assertEquals("https://fixture.invalid/1", value("class.book@href&&class.book@href", output = OutputKind.Url).text())
        assertEquals(listOf("https://fixture.invalid/1", "https://fixture.invalid/2"), value("class.book@href", output = OutputKind.UrlList).items().map { it.text() })
        assertEquals("https://fixture.invalid/path/", value("class.missing@href", output = OutputKind.Url).text())
    }

    @Test fun urlResolutionPreservesLogicalRequestOptionsAndUnencodedTitles() {
        val context = RuleContext("url", baseUrl = "https://fixture.invalid/list/page,{'method':'POST'}")
        val url = "../chapter?name=第一 卷,{'method':'POST'}"
        val input = RuleValue.Text("<a href=\"$url\">Read</a>")
        assertEquals("https://fixture.invalid/chapter?name=第一 卷,{'method':'POST'}",
            value("a@href", input, OutputKind.Url, context).text())
        assertEquals("", value("a@href", RuleValue.Text("<a href='javascript:void(0)'>Locked</a>"), OutputKind.Url, context).text())
    }

    @Test fun putsAndTemplatesUseRequestContextAndPreserveCaptureReplacement() {
        val context = RuleContext("a", "book", chapterVariables = mapOf("title" to "Chapter"))
        assertEquals("Library Chapter", value("""@put:{"name":"tag.h1@text"}@get:{name} @get:{title}""", context = context).text())
        assertEquals(mapOf("name" to "Library"), context.bookWrites)
        assertTrue(context.writes().isEmpty())
        assertEquals("[Library]", value("[{{@CSS:h1@text}}]").text())
        assertEquals("1-Alpha", value("\$1-\$2", RuleValue.Captures(listOf("1:Alpha", "1", "Alpha")), OutputKind.Text).text())
        assertEquals("Lbrary", value("tag.h1@text##i##", output = OutputKind.Text).text())
        assertEquals("[bra]", value("tag.h1@text##(bra)##[\$1]###", output = OutputKind.Text).text())
        assertEquals("", value("tag.h1@text##absent##x###", output = OutputKind.Text).text())
        assertEquals("Library", value("tag.h1@text##[##x", output = OutputKind.Text).text())
        assertEquals("x", value("tag.h1@text##[##x###", output = OutputKind.Text).text())
    }

    @Test fun scriptPortReceivesIntermediateValuesAndPayloadBoundaries() {
        val seen = mutableListOf<ScriptRequest>()
        val engine = RuleEvaluator { request, _, _ -> seen.add(request); RuleValue.Text("result") }
        val script = "const x = '</js>'; /* </js> */ const pattern = /<\\/js>/; result"
        val result = engine.evaluate("tag.h1@text<js>$script</js>##result##done", html, RuleContext("a"))
        assertEquals("done", (result as RuleResult.Success).value.text())
        assertEquals(script, seen.single().script)
        assertEquals("Library", seen.single().input.text())
        engine.evaluate("{{ ({x: '&&||%%'}).x }}", html, RuleContext("a"))
        assertEquals(" ({x: '&&||%%'}).x ", seen.last().script)
        val missing = RuleEvaluator().evaluate("@js:result", html, RuleContext("a")) as RuleResult.Failure
        assertEquals(RuleStage.Script, missing.error.stage)
        assertEquals("ScriptPortUnavailable", missing.error.code)
    }

    @Test fun nestedMixedRulesAndUnevenInterleaveKeepOrder() {
        assertEquals(listOf("AlphaOne", "Beta", "Library"), value("class.book@text&&@XPath://h1/text()").items().map { it.text() })
        assertEquals(listOf("Library", "AlphaOne"), value("tag.h1@text%%class.book@text").items().map { it.text() })
        val split = RuleParser().split("$.a[?(@.x == 'a&&b')].x||$.b", listOf("&&", "||", "%%"), RuleLocation("name"), RuleBudget())
        assertEquals("||", split.first)
        assertEquals(2, split.second.size)
        assertEquals(1, RuleParser().split("tag.a\\&&text", listOf("&&"), RuleLocation("name"), RuleBudget()).second.size)
    }

    @Test fun scalarBilingualContentInterleavesParagraphsBeforeJoiningText() {
        val input = RuleValue.Text("<p class='en'>One</p><p class='en'>Two</p>" +
            "<p class='cn' title='一'></p><p class='cn' title='二'></p><p class='cn' title='三'></p>")
        assertEquals("One\n一\nTwo\n二", value(".en@text%%.cn@title", input, OutputKind.Text).text())
        // Legado retains only the first branch's length, even for a scalar result.
        assertEquals("一\nOne\n二\nTwo\n三", value(".cn@title%%.en@text", input, OutputKind.Text).text())
        assertEquals("One\n一\nTwo\n二", value(".missing@text||.en@text%%.cn@title", input, OutputKind.Text).text())
    }

    @Test fun requestsAcrossSourcesBooksAndThreadsNeverShareWrites() {
        val pool = Executors.newFixedThreadPool(4)
        val engine = RuleEvaluator()
        try {
            val identities = listOf("a/1/r1", "a/1/r2", "a/2/r1", "b/1/r1", "b/2/r1")
            val jobs = identities.map { identity -> pool.submit<String> {
                val context = RuleContext(identity.substringBefore('/'), identity.split('/')[1])
                engine.evaluate("""@put:{"name":"tag.h1@text"}@get:{name}""", RuleValue.Text("<h1>$identity</h1>"), context, OutputKind.Text)
                context.get("name")
            } }
            assertEquals(identities, jobs.map { it.get(3, TimeUnit.SECONDS) })
        } finally { pool.shutdownNow() }
    }

    @Test fun inheritedVariablesAreCopiedAndEmptyValuesFallBackThroughScopes() {
        val source = mutableMapOf("name" to "source")
        val context = RuleContext("a", "book", sourceVariables = source,
            bookVariables = mapOf("name" to "book"), chapterVariables = mapOf("name" to ""))
        source["name"] = "changed"
        assertEquals("book", context.get("name"))
        context.put("name", "request")
        assertEquals("request", context.get("name"))
        val snapshot = context.writes()
        context.put("name", "")
        assertEquals("source", context.get("name"))
        assertTrue(snapshot.isEmpty())
        assertEquals("", context.bookWrites["name"])
    }

    @Test fun selectorVariablesUseMetadataNamesAndWriteToTheCurrentEntity() {
        val context = RuleContext("a", "book", "chapter", sourceVariables = mapOf("token" to "source"))
        context.bookMetadata = """{"name":"Book"}"""
        context.chapterMetadata = """{"title":"","variable":"{\"token\":\"old\"}"}"""
        context.put("title", "shadow")
        assertEquals("Book", value("@get:{bookName}", output = OutputKind.Text, context = context).text())
        assertEquals("", context.get("title"))
        assertEquals("old", context.get("token"))
        context.put("token", "")
        assertEquals("source", context.get("token"))
        context.put("large", "x".repeat(10000))
        assertEquals(10000, context.get("large").length)
        assertNull(context.chapterWrites["large"])
        assertEquals(10000, context.chapterBigWrites["large"]!!.length)
        assertTrue(context.writes().isEmpty())
    }

    @Test fun regexChainStopsOnEmptyAndFirstReplacementOnlyReplacesFirstMatch() {
        assertEquals(emptyList<RuleValue>(), value(":missing&&^$", output = OutputKind.Elements).items())
        assertEquals("x", value("tag.h1@text##L?##x###", output = OutputKind.Text).text())
        val captures = value(":<a[^>]*>.*?</a>&&>([A-Za-z]+)<", output = OutputKind.Elements).items()
        assertEquals(listOf("Alpha", "One", "Beta"), captures.map { (it as RuleValue.Captures).groups[1] })
    }

    @Test(timeout = 5000) fun ordinaryReplacementCanScanLongParagraphsWithoutMatchingTheNotice() {
        val paragraph = "正文文字".repeat(1500)
        val input = "$paragraph\n在线阅读提示\n最后一段。"
        assertEquals("$paragraph\n\n最后一段。",
            value("##.*在线阅读.*", RuleValue.Text(input), OutputKind.Text).text())
    }

    @Test(timeout = 5000) fun malformedOversizedAndBacktrackingRulesReturnLocatedFailuresWithinBudget() {
        val engine = RuleEvaluator()
        val location = RuleLocation("ruleSearch.name", 10)
        val broken = engine.evaluate("@Json:$.a[", RuleValue.Text("{}"), RuleContext("a"), location = location) as RuleResult.Failure
        assertEquals(RuleStage.Parse, broken.error.stage)
        assertEquals("ruleSearch.name", broken.error.location.field)
        assertTrue(broken.error.location.offset >= 10)
        val oversized = engine.evaluate("x".repeat(100), html, RuleContext("a"), location = location,
            budget = RuleBudget(RuleLimits(maxRuleChars = 50))) as RuleResult.Failure
        assertEquals(RuleStage.Budget, oversized.error.stage)
        val catastrophic = engine.evaluate(":(a+)+$", RuleValue.Text("a".repeat(5000) + "!"), RuleContext("a"), OutputKind.Elements,
            location, RuleBudget(RuleLimits(maxSteps = 10000, timeoutMillis = 200))) as RuleResult.Failure
        assertEquals(RuleStage.Budget, catastrophic.error.stage)
        val invalidJsonPath = engine.evaluate("$.a[?(@.x ===)]", RuleValue.Text("{}"), RuleContext("a"))
        assertEquals(RuleResult.Success(RuleValue.Items(emptyList())), invalidJsonPath)
    }
}
