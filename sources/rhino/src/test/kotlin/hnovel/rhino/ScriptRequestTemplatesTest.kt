package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptRequestTemplatesTest {
    @Test fun multiTtsAjaxKeepsHeadersScopedAndGeneratedTextAsData() {
        val spoken = "中文 {{mustNotExecute()}}"
        val engine = RhinoScriptEngine(HostBridge { name, args ->
            assertEquals("speech.ajax", name)
            val request = args.single().jsonObject
            assertEquals("POST", request.getValue("method").jsonPrimitive.content)
            assertEquals(10_000, request.getValue("timeoutMillis").jsonPrimitive.int)
            assertEquals(spoken, request.getValue("body").jsonPrimitive.content)
            assertEquals(buildJsonObject { put("Content-Type", "application/json") }, request["headers"])
            JsonPrimitive("""{"token":"temporary"}""")
        })
        val rule = """@js:
            if (typeof java.get !== 'undefined') throw Error('wrong bridge');
            var token=JSON.parse(java.fetch('https://auth.invalid', JSON.stringify({method:POST,body:speakText,timeout:10000}))).token;
            'https://audio.invalid?voice='+voiceCode+','+JSON.stringify({method:POST,headers:{Authorization:token},body:JSON.stringify({text:speakText,style:params[0],volume:speakVolume,speed:speakSpeed})})
        """.trimIndent()
        val input = buildJsonObject {
            put("url", rule); put("voiceCode", "speaker")
            put("params", buildJsonArray { add("calm") }); put("speakSpeed", 75); put("speakVolume", 25)
            put("headers", buildJsonObject { put("Cookie", "engine-only") })
        }
        val result = engine.evaluate("host.call('request.multitts',result)", frame.copy(
            variables = mapOf("result" to input), speakText = spoken, sourceHeaderRule = """{"Cookie":"must-not-leak"}"""))
        assertTrue(result.toString(), result is ScriptResult.Success)
        val request = Json.parseToJsonElement((result as ScriptResult.Success).json).jsonObject
        assertEquals("https://audio.invalid?voice=speaker", request.getValue("url").jsonPrimitive.content)
        assertEquals("temporary", request.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        assertEquals("engine-only", request.getValue("headers").jsonObject.getValue("Cookie").jsonPrimitive.content)
        assertEquals(buildJsonObject {
            put("text", spoken); put("style", "calm"); put("volume", 25); put("speed", 75)
        }, Json.parseToJsonElement(request.getValue("body").jsonPrimitive.content))
        val failing = RhinoScriptEngine(HostBridge { _, _ -> error("private network details") })
        val fallback = JsonObject(input + ("url" to JsonPrimitive("""@js:
            try { java.ajax(new String('https://auth.invalid')) } catch(e) {
                if (!(e instanceof Error) || String(e).indexOf('private') >= 0) throw Error('unsafe error');
                new String('https://audio.invalid/fallback')
            }
        """.trimIndent())))
        val recovered = failing.evaluate("host.call('request.multitts',result)", frame.copy(
            variables = mapOf("result" to fallback), speakText = spoken)) as ScriptResult.Success
        assertEquals("https://audio.invalid/fallback", Json.parseToJsonElement(recovered.json).jsonObject.getValue("url").jsonPrimitive.content)
        val malformed = JsonObject(input + ("url" to JsonPrimitive(
            "@js:try { java.ajax('https://auth.invalid') } catch(e) {} JSON.parse('invalid')")))
        val failed = failing.evaluate("host.call('request.multitts',result)", frame.copy(
            variables = mapOf("result" to malformed), speakText = spoken)) as ScriptResult.Failure
        assertEquals(FailureCode.Runtime, failed.code)
    }

    @Test fun sourceKeyPropertyUsesTheSameHostIdentityAsItsGetter() {
        val engine = RhinoScriptEngine(HostBridge { name, _ ->
            assertEquals("source.getKey", name)
            JsonPrimitive("https://source.invalid/")
        })
        assertEquals(ScriptResult.Success("true"), engine.evaluate("source.key===source.getKey()", frame))
    }

    @Test fun malformedOptionalUrlObjectFallsBackBeforeTheHostCompiler() {
        assertEquals("/search?q={{key}},{}", expanded("""/search?q={{key}},{"body":"id"="search-form"}"""))
    }

    @Test fun singleQuotedOptionsRemainDataAndOptionScriptsStillRunInTheWorker() {
        val rule = """/search,{'method':'POST','body':'keyword={{key}}','header':{'X-Test':'it\'s "quoted"'}}"""
        assertEquals("/search," + hnovel.rules.RequestOptionsJson.options(rule.substringAfter(',')), expanded(rule))
        assertEquals("https://example.org/new,{\"method\":\"POST\"}",
            expanded("""/search,{'method':'POST','js':'baseUrl+"/new"'}"""))
    }

    @Test fun ignoredOptionalObjectSyntaxNeverExecutesAsJavaScript() {
        val engine = RhinoScriptEngine(HostBridge { _, args ->
            assertEquals("/search,{}", args[0].jsonPrimitive.content)
            JsonPrimitive("ok")
        })
        assertEquals(ScriptResult.Success("false"), engine.evaluate(
            "var executed=false;java.ajax(\"/search,{'body':(function(){executed=true})()}\");executed", frame))
        assertEquals(ScriptResult.Success("\"ok\""), engine.evaluate(
            "java.ajax(\"/search,{'body':'unterminated}\")", frame))
    }

    @Test fun invalidEmbeddedRequestDataHasRedactedFailuresWithoutHostCalls() {
        var calls = 0
        val engine = RhinoScriptEngine(HostBridge { _, _ -> calls++; JsonNull })
        for (rule in listOf("/search,{'header':\"{'X':'secret-unterminated}\"}",
            "/search,{'method':'POST','body':\"{'q':'secret-unterminated}\"}")) {
            val result = engine.evaluate("java.ajax(${JsonPrimitive(rule)})", frame) as ScriptResult.Failure
            assertEquals("RequestSyntax", result.code.name)
            assertFalse(result.message.contains("secret"))
            assertEquals(0, calls)
            val nested = "@js:java.ajax(${JsonPrimitive(rule)});'/search'"
            val nestedResult = engine.evaluate("java.ajax(${JsonPrimitive(nested)})", frame) as ScriptResult.Failure
            assertEquals("RequestSyntax", nestedResult.code.name)
            assertEquals(0, calls)
        }
        assertEquals(ScriptResult.Success("true"), engine.evaluate(
            "try { java.ajax(\"/search,{'header':\\\"{'X':'secret-unterminated}\\\"}\"); false } catch(e) { e instanceof Error && String(e).indexOf('secret') < 0 }", frame))
        assertEquals(0, calls)
    }

    private val frame = ScriptFrame("a", "legado", variables=mapOf("result" to JsonPrimitive("outer")), key="a b", page=2, baseUrl="https://example.org/books/")
    private fun expanded(rule: String): String {
        var actual = ""
        val engine = RhinoScriptEngine(HostBridge { _, args -> actual=args[0].jsonPrimitive.content; JsonPrimitive("ok") })
        val result = engine.evaluate("java.ajax(${JsonPrimitive(rule)});result", frame)
        assertEquals(ScriptResult.Success("\"outer\""), result)
        return actual
    }

    @Test fun scriptStagesAndNestedExpressionsRunBeforeHostDispatch() {
        assertEquals("/next?p=2", expanded("/start<js>result.replace('start','next')</js>@result?p={{page+0}}"))
        assertEquals("/find?q=a%20b", expanded("@js:'/find?q='+encodeURIComponent(key)"))
        assertEquals("/x?value=3", expanded("/x?value={{({a:{b:3}}).a.b}}"))
        assertEquals("/x?value=", expanded("/x?value={{null}}"))
        assertEquals("/it's-a-book", expanded("/it's-a-book"))
    }

    @Test fun optionScriptReceivesResolvedUrlAndKeepsOtherOptions() {
        assertEquals("https://example.org/books/two?next=2,{\"method\":\"POST\",\"body\":\"key={{key}}\"}",
            expanded("""<one,two>,{"method":"POST","body":"key={{key}}","js":"result+'?next='+page"}"""))
        assertEquals("https://example.org/new,{}", expanded("""start,{"js":"baseUrl+'/new'"}"""))
    }

    @Test fun staticPlaceholdersRemainForCharsetAwareHostCompiler() {
        assertEquals("/find?q={{key}}&p={{page}}&n=3", expanded("/find?q={{key}}&p={{page}}&n={{page+1}}"))
    }

    @Test fun terminalScriptRetainsCommaExpressionsWhileDelimitedScriptsCanHaveOptions() {
        // AnalyzeUrl runs terminal @js through the end BEFORE its option split. A comma
        // expression is JS here; moving the option split earlier changes the pinned profile.
        assertEquals("[object Object]", expanded("""@js:'/endpoint',{"method":"POST"}"""))
        assertEquals("/endpoint,{\"method\":\"POST\"}", expanded("""<js>'/endpoint'</js>@result,{"method":"POST"}"""))
        assertEquals("/endpoint,{\"method\":\"POST\"}", expanded("""@js:'/endpoint,'+JSON.stringify({method:'POST'})"""))
    }

    @Test fun headerObjectsAndJsonStringsPreserveValuesWithoutRequestTemplateExpansion() {
        for (header in listOf("@js:({Authorization:'{{key}}'})", "@js:JSON.stringify({Authorization:'{{key}}'})",
            "{'Authorization':'{{key}}'}", "@js:${JsonPrimitive("{'Authorization':'{{key}}'}")}")) {
            val engine = RhinoScriptEngine(HostBridge { name, args ->
                assertEquals("request.withHeaders", name)
                assertEquals("{{key}}", args[2].jsonObject.getValue("Authorization").jsonPrimitive.content)
                JsonPrimitive("ok")
            })
            assertEquals(ScriptResult.Success("\"ok\""), engine.evaluate("java.ajax('/next')", frame.copy(sourceHeaderRule=header)))
        }
    }

    @Test fun explicitConnectHeadersAreCanonicalDataBeforeCrossingTheBridge() {
        var calls = 0
        val engine = RhinoScriptEngine(HostBridge { _, _ -> calls++; JsonNull })
        val header = "{'X-Test':'it\\'s \"quoted\"'}"
        val result = engine.evaluate("host.call('request.prepare','/next',${JsonPrimitive(header)})", frame)
        assertEquals(ScriptResult.Success(buildJsonArray {
            add("/next"); add("{\"X-Test\":\"it's \\\"quoted\\\"\"}")
        }.toString()), result)
        assertEquals(0, calls)
    }

}
