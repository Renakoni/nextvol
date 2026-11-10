package hnovel.rhino

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptResponseTest {
    @Test fun browserDocumentsKeepTheirUrlAndDoNotInventRawHttpMetadata() {
        val document = JsonObject(data + mapOf("kind" to JsonPrimitive("BrowserDocument"), "status" to JsonPrimitive(0)))
        val engine = RhinoScriptEngine(HostBridge { _, _ -> document })
        for (call in listOf("java.connect('url')", "java.get('url',{})")) {
            assertEquals(ScriptResult.Success("[\"chapter\",\"https://fixture.invalid/final\",0,true,null,false]"),
                engine.evaluate("var r=$call;[r.body(),r.getUrl(),r.code(),r.isBrowserDocument(),r.raw(),r.isSuccessful()]", frame))
        }
        assertEquals(ScriptResult.Success("\"https://fixture.invalid/request\""), engine.evaluate("java.getUrl()",
            frame.copy(baseUrl = "https://fixture.invalid/request")))
    }

    @Test fun completedBrowserAwaitAllowsTheLoginSuccessBranchWithoutInventingRawHttp() {
        val document = JsonObject(data + mapOf("kind" to JsonPrimitive("BrowserDocument"), "status" to JsonPrimitive(0)))
        val calls = mutableListOf<String>()
        val engine = RhinoScriptEngine(HostBridge { name, _ ->
            calls += name
            document
        })
        assertEquals(ScriptResult.Success("[true,200,true,null,null,\"chapter\",\"https://fixture.invalid/final\",true]"),
            engine.evaluate("""
                var resp = java.startBrowserAwait('url', 'Login', false);
                var saved = false;
                if (resp.code() === 200) saved = true;
                [saved, resp.statusCode(), resp.isSuccessful(), resp.raw(), resp.getRaw(),
                    resp.body(), resp.url(), resp.isBrowserDocument()]
            """.trimIndent(), frame))
        assertEquals(listOf("java.startBrowserAwait"), calls)
    }

    @Test fun browserFailureCannotReachTheLoginSaveBranch() {
        val calls = mutableListOf<String>()
        val engine = RhinoScriptEngine(HostBridge { name, _ ->
            calls += name
            error("Browser failed")
        })
        val result = engine.evaluate("""
            var response = java.startBrowserAwait('url', 'Login', false);
            if (response.code() === 200) cache.put('login-ready', 'saved', 0);
        """.trimIndent(), frame)
        assertEquals(FailureCode.BridgeDenied, (result as ScriptResult.Failure).code)
        assertEquals(listOf("java.startBrowserAwait"), calls)
    }

    @Test fun browserAwaitPreservesObservableHttpFailures() {
        val response = JsonObject(data + ("status" to JsonPrimitive(401)))
        val engine = RhinoScriptEngine(HostBridge { _, _ -> response })
        assertEquals(ScriptResult.Success("401"),
            engine.evaluate("java.startBrowserAwait('url','Login',false).code()", frame))
    }

    @Test fun retainedResponseAndDerivedViewsChargeTheWholeOwnerAndDiscardOnOverflow() {
        val response = JsonObject(data + ("body" to JsonPrimitive("b".repeat(400))))
        val bounded = RhinoScriptEngine(HostBridge { _, _ -> response }, ScriptLimits(maxBridgeChars = 1024))
        val mutations = listOf(
            "holder.r.addHeader('X-Test',value)", "holder.r.cookie('c'+i,value)",
            "holder.headers.put('Other',[value])", "holder.cookies.put('c',value)",
            "holder.list.add(value)", "holder.namedList.add(value)", "holder.property.add(value)", "holder.values.get(0).add(value)",
            "holder.headerEntry.getValue().add(value)", "holder.cookieEntry.setValue(value)",
            "holder.headers.putAll({Other:[value]})")
        for (mutation in mutations) ScriptLibrary("a", "legado", "var holder={};").use { library ->
            assertEquals(ScriptResult.Success("1"), bounded.evaluate("""
                holder.r=java.get('u',{});holder.r.cookie('seed','x');
                holder.headers=holder.r.multiHeaders();holder.cookies=holder.r.cookies();
                holder.list=holder.headers.get('X-Test');holder.property=holder.headers['X-Test'];
                holder.namedList=holder.r.headers('x-test');
                holder.values=holder.headers.values();holder.headerEntry=holder.headers.entrySet()[0];
                holder.cookieEntry=holder.cookies.entrySet()[0];1
            """, frame, library))
            var overflow = false
            for (i in 1..9) {
                val result = bounded.evaluate("var i=$i,value=new Array(i*60+1).join('x');$mutation;1", frame, library)
                if (result is ScriptResult.Failure) {
                    assertEquals(mutation, FailureCode.ResultTooLarge, result.code)
                    overflow = true
                    break
                }
            }
            assertTrue("Must count body and metadata together: $mutation", overflow)
            assertEquals(ScriptResult.Success("\"undefined\""), bounded.evaluate("typeof holder.r", frame, library))
        }
    }

    @Test fun retainedNamedHeaderListCountsBodyWhenMutationFitsItsOwnBudget() {
        val response = JsonObject(data + ("body" to JsonPrimitive("b".repeat(400))))
        val bounded = RhinoScriptEngine(HostBridge { _, _ -> response }, ScriptLimits(maxBridgeChars = 1024))
        ScriptLibrary("a", "legado", "var holder={};").use { library ->
            assertEquals(ScriptResult.Success("1"), bounded.evaluate(
                "holder.r=java.get('u',{});holder.list=holder.r.headers('x-test');1", frame, library))
            // The argument and list fit separately; the retained 400-byte body makes the owner exceed 1024.
            assertEquals(FailureCode.ResultTooLarge, (bounded.evaluate(
                "holder.list.add(new Array(701).join('x'));1", frame, library) as ScriptResult.Failure).code)
            assertEquals(ScriptResult.Success("\"undefined\""), bounded.evaluate("typeof holder.r", frame, library))
        }
    }

    @Test fun malformedSnapshotsStayCatchableAndNeverExposeImplementationErrors() {
        val calls = listOf("java.connect('url')", "java.ajaxAll(['url'])", "java.get('url',{})", "java.head('url',{})", "java.post('url','',{})")
        for (payload in listOf<JsonElement>(JsonPrimitive("private-payload"), JsonNull, buildJsonObject {},
            JsonObject(data + ("status" to JsonPrimitive("private-status"))),
            JsonObject(data + ("headers" to buildJsonObject { put("X-Private", "invalid-list") })))) {
            for (call in calls) {
                val engine = RhinoScriptEngine(HostBridge { name, _ -> if (name == "java.ajaxAll") JsonArray(listOf(payload)) else payload })
                assertEquals(ScriptResult.Success("[\"host bridge denied\",\"undefined\",\"undefined\"]"),
                    engine.evaluate("try{$call}catch(e){[e.message,typeof e.javaException,typeof e.getClass]}", frame))
                assertEquals(FailureCode.BridgeDenied, (engine.evaluate(call, frame) as ScriptResult.Failure).code)
            }
        }
    }
    private val data = buildJsonObject {
        put("body", "chapter"); put("url", "https://fixture.invalid/final"); put("status", 200); put("message", "OK")
        put("headers", buildJsonObject { put("X-Test", JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two")))) })
    }
    private val engine = RhinoScriptEngine(HostBridge { name, _ -> if (name == "java.ajaxAll") JsonArray(listOf(data, data)) else data })
    private val frame = ScriptFrame("a", "legado")

    @Test fun responseViewsExposeDataMethodsWithoutJavaWrappers() {
        assertEquals(ScriptResult.Success("[\"chapter\",200,\"OK\",true,\"two\",[\"one\",\"two\"],\"undefined\",\"undefined\",200,true]"), engine.evaluate(
            "var r=java.connect('url');[r.body(),r.code(),r.message(),r.isSuccessful(),r.headers().get('x-test')," +
                "r.headers().values('X-Test'),typeof r.getClass,typeof r.raw().getClass,r.raw().code(),(function(){try{r.raw().body().bytes();return false}catch(e){return true}})()]", frame))
        assertEquals(ScriptResult.Success("[\"chapter\",200,\"OK\",null,\"one\",\"one, two\",null]"), engine.evaluate(
            "var r=java.get('url',{});[r.body.call(null),r.statusCode(),r.statusMessage(),r.header('missing'),r.headers().get('X-Test'),r.header('x-test'),r.headers().get('x-test')]", frame))
        assertEquals(ScriptResult.Success("[\"chapter\",\"chapter\"]"), engine.evaluate("java.ajaxAll(['a','b']).map(function(r){return r.body()})", frame))
    }

    @Test fun responseMethodsSurviveShadowedConstructorsAndKeepDataBounds() {
        ScriptLibrary("a", "legado", "var Object=null,Array=null,Function=null,Error=null;var holder={};").use { library ->
            assertEquals(ScriptResult.Success("1"), engine.evaluate("holder.response=java.connect('url');1", frame, library))
            assertEquals(ScriptResult.Success("\"chapter\""), engine.evaluate("holder.response.body()", frame, library))
        }
        val small = RhinoScriptEngine(HostBridge { _, _ -> data }, ScriptLimits(maxBridgeChars = 64))
        assertEquals(FailureCode.ResultTooLarge, (small.evaluate("java.connect('url')", frame) as ScriptResult.Failure).code)
    }
}
