package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptRuleHelpersTest {
    @Test fun ruleScriptsExpandJavaTemplatesBeforeCompilingTheirQuotedBodies() {
        val rule = """@js:var options={"chapter":"{{java.get("chapterId")}}"};options.chapter"""
        assertEquals(JsonPrimitive("42"), run("java.put('chapterId','42');java.getString(${JsonPrimitive(rule)})"))
    }
    @Test fun literalTemplatesCanGenerateRequestSyntaxAndEvaluateFromRightToLeft() {
        assertEquals(JsonPrimitive("right|right"), run("""
            java.getString('{{java.get("order")}}|{{java.put("order","right")}}')
        """))
        val rule = """{{'Writer'}}::{"body":"offset={{'\\{\\{page\\}\\}&limit=18"}'}}"""
            .replace("\\\\", "\\")
        assertEquals(JsonPrimitive("Writer::{\"body\":\"offset={{page}}&limit=18\"}"),
            run("java.getString(${JsonPrimitive(rule)})"))
    }
    @Test fun templateScriptsKeepRegexLiteralsSeparateFromStringsAndDivision() {
        assertEquals(JsonPrimitive("rating"), run("""
            java.setContent('<meta ratingValue": "8"/>');
            java.getString('{{html="";if(result.match(/ratingValue": "(\\d+)"/)){html="rating"}html}}')
        """))
        assertEquals(JsonPrimitive("4"), run("""java.getString('{{(12)/3}}')"""))
        assertEquals(JsonPrimitive("yes"), run("""
            java.getString('{{function f(){return /[}]/.test("}")} f()?"yes":"no"}}')
        """))
    }
    @Test fun toastMessagesUseNativeStringConversionAndReturnVoid() {
        val calls = mutableListOf<Pair<String, List<JsonElement>>>()
        val engine = RhinoScriptEngine(HostBridge { name, args -> calls += name to args; JsonNull })
        val result = engine.evaluate("""
            var cycle={};cycle.self=cycle;
            [typeof java.toast(cycle),typeof java.longToast(null)]
        """, frame)
        assertEquals(ScriptResult.Success("[\"undefined\",\"undefined\"]"), result)
        assertEquals(listOf("java.toast" to listOf(JsonPrimitive("[object Object]")),
            "java.longToast" to listOf(JsonPrimitive("null"))), calls)
    }
    @Test fun explicitAndScriptArrayInputsStayStructuredForJsonPath() {
        assertEquals(JsonPrimitive(true), run("Array.isArray(java.getElement('@js:[{id:1}]'))"))
        assertEquals(JsonPrimitive("second"), run("java.getString('$[1].title',[{title:'first'},{title:'second'}])"))
        assertEquals(Json.parseToJsonElement("""[{"id":1},{"id":2}]"""), run("""
            java.setContent({items:[{id:1},{id:2}]});
            java.getElements('$.items[*]<js>result</js>$.[*]')
        """))
    }
    @Test fun emptyHtmlSelectionsKeepDomMethodsWhileJsonAndScriptArraysStayData() {
        assertEquals(Json.parseToJsonElement("""["",0,"","","undefined","undefined"]"""),run("""
            var empty=java.getElement('@@#missing'), combined=java.getElements('@@#missing||#alsoMissing');
            var script=java.getElement('@js:[]');
            java.setContent('{"items":[]}'); var data=java.getElements('$.items[*]');
            [empty.text(),empty.size(),empty.attr('href'),combined.text(),typeof data.text,typeof script.text]
        """))
    }
    @Test fun loggingReturnsTheOriginalValueWithoutBreakingRuleFallbacks() {
        assertEquals(JsonArray(listOf(JsonPrimitive("chapter"), JsonPrimitive(true), JsonNull)), run("""
            var object={name:'chapter'};[java.log(object.name),java.log(object)===object,java.log(null)]
        """))
        assertEquals(JsonPrimitive(true), run("""
            var error = new Error('source error'), cycle = {}; cycle.self = cycle;
            java.log(error) === error && java.log(cycle) === cycle && java.log(undefined) === undefined
        """))
    }
    private val engine = RhinoScriptEngine(HostBridge { _, _ -> error("Selectors must stay in worker") })
    private val frame = ScriptFrame("a", "legado", variables = mapOf("result" to JsonPrimitive("<a href='/one'>One</a><a href='/two'>Two</a>")), baseUrl="https://example.org/base")
    private fun run(code: String, frame: ScriptFrame = this.frame): JsonElement {
        val result = engine.evaluate(code, frame)
        assertTrue(result.toString(), result is ScriptResult.Success)
        return Json.parseToJsonElement((result as ScriptResult.Success).json)
    }

    @Test fun selectorsUseRootWhileNestedScriptsRestoreResultAndShareVariables() {
        val context = RuleContext("a", sourceVariables=mapOf("value" to "source"), chapterVariables=mapOf("value" to "chapter"))
        assertEquals(JsonArray(listOf(JsonPrimitive("One\nTwo"), JsonPrimitive("chapter!"), JsonPrimitive("outside"))), run("""
            result='outside';
            var title=java.getString('a@text');
            var changed=java.getString('@js:java.put("value",java.get("value")+"!");result');
            [title,java.get('value'),result]
        """, frame.copy(ruleContext=context)))
        assertEquals(mapOf("value" to "chapter!"), context.writes())
    }

    @Test fun explicitContentUrlListsAndNativeJsonNodesRetainTheirTypes() {
        assertEquals(JsonArray(listOf(JsonPrimitive("https://example.org/one"), JsonPrimitive("https://example.org/two"))),
            run("java.getStringList('a@href',null,true)"))
        assertEquals(JsonPrimitive("7"), run("java.getString('$.title',{title:7})"))
        val jsonFrame = frame.copy(variables=mapOf("result" to Json.parseToJsonElement("""{"books":[{"title":"one"},{"title":"two"}]}""")))
        assertEquals(JsonPrimitive("one"), run("java.getElement('$.books[0]').title", jsonFrame))
        assertEquals(JsonPrimitive("two"), run("java.getElements('$.books[*]')[1].title", jsonFrame))
    }

    @Test fun elementSnapshotsCanFeedSelectorsWithoutExposingDomObjects() {
        assertEquals(JsonPrimitive("One"), run("var nodes=java.getElements('a');java.getString('a@text',nodes[0])"))
        assertEquals(JsonPrimitive("undefined"), run("typeof java.getElements('a')[0].getClass"))
    }

    @Test fun emptyAndUnescapeOverloadsFollowReference() {
        assertEquals(JsonArray(listOf(JsonPrimitive(""), JsonNull, JsonNull, JsonArray(emptyList()), JsonPrimitive("&amp;"), JsonPrimitive("&"))), run("""
            [java.getString(null),java.getStringList(''),java.getElement(''),java.getElements(''),
             java.getString('@js:"&amp;"',false),java.getString('@js:"&amp;"',true)]
        """))
    }

    @Test fun nestedStringRulesKeepUnterminatedEntitiesInRequestParameters() {
        assertEquals(JsonPrimitive("id=1&timestamp=2&notin=3|& &apos;"), run("""
            java.getString('@js:"id=1&timestamp=2&notin=3"')+'|'+java.getString('@js:"&amp; &apos;"')
        """))
    }

    @Test fun nestedRuleRecursionCannotResetInstructionOrDepthBudget() {
        val frame = frame.copy(ruleBudget=RuleBudget(RuleLimits(maxDepth=8)))
        val result = engine.evaluate("function again(){return java.getString('@js:again()')}try{again()}catch(e){'hidden'}", frame)
        assertEquals(FailureCode.Timeout, (result as ScriptResult.Failure).code)
        assertEquals(JsonPrimitive("One\nTwo"), run("java.getString('a@text')"))
    }

    @Test fun setContentReplacesSelectorInputAndKeepsExplicitReadsTemporary() {
        assertEquals(JsonArray(listOf(JsonPrimitive("First"), JsonPrimitive("temporary"), JsonPrimitive("First"),
            JsonPrimitive("Second"), JsonPrimitive("Fourth"), JsonPrimitive("outside"), JsonPrimitive(true))), run("""
            result='outside';
            var same=java.setContent('<p>First</p>')===java;
            var first=java.getString('p@text');
            var temporary=java.getString('p@text','<p>temporary</p>');
            var retained=java.getString('p@text');
            java.setContent({chapter:{text:'Second'}});
            var second=java.getString('$.chapter.text');
            java.setContent([{title:'Third'},{title:'Fourth'}]);
            [first,temporary,retained,second,java.getString('$[1].title'),result,same]
        """))
        assertEquals(JsonPrimitive("One\nTwo"), run("java.getString('a@text')"))
    }

    @Test fun setContentKeepsRedirectResolutionSeparateFromContentBase() {
        val context = RuleContext("a", baseUrl = frame.baseUrl)
        assertEquals(JsonArray(listOf(JsonPrimitive("https://example.org/next"),
            JsonPrimitive(""), JsonPrimitive("https://example.org/later"),
            JsonPrimitive(frame.baseUrl), JsonPrimitive("https://text.invalid/folder/book"))), run("""
            java.setContent('<a href="next">Chapter</a>','https://text.invalid/folder/book');
            var url=java.getString('a@href',null,true);
            var dom=java.getElement('a').first().absUrl('href');
            java.setContent('<a href="later">Later</a>',null);
            [url,dom,java.getStringList('a@href',null,true)[0],baseUrl,java.getString("@js:''",null,true)]
        """, frame.copy(ruleContext = context)))
        assertEquals(frame.baseUrl, context.baseUrl)
    }

    @Test fun invalidSetContentLeavesPreviousInputIntactAndCannotResetBudget() {
        assertEquals(JsonPrimitive("One\nTwo"), run("""
            try{java.setContent(null)}catch(e){}
            try{java.setContent('<p>bad</p>',{})}catch(e){}
            java.getString('a@text')
        """))
        val small = RhinoScriptEngine(HostBridge { _, _ -> error("No host") }, ScriptLimits(maxBridgeChars = 256))
        val failure = small.evaluate("try{java.setContent('x'.repeat(512))}catch(e){'hidden'}", frame)
        assertEquals(FailureCode.ResultTooLarge, (failure as ScriptResult.Failure).code)
    }
}
