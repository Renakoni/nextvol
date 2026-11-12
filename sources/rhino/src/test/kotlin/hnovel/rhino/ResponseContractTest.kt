package hnovel.rhino

import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ResponseContractTest {
    private fun data(bytes: ByteArray, type: String? = null) = buildJsonObject {
        put("body", bytes.toString(Charsets.UTF_8)); put("bytes", Base64.getEncoder().encodeToString(bytes))
        put("url", "https://fixture.invalid/book"); put("status", 200); put("message", "OK")
        put("method", "POST"); put("protocol", "h2"); put("sentAt", 123); put("receivedAt", 456)
        put("headers", buildJsonObject {
            put("X-Test", JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))))
            if (type != null) put("Content-Type", JsonArray(listOf(JsonPrimitive(type))))
        })
    }
    private fun run(script: String, data: JsonObject): ScriptResult = RhinoScriptEngine(HostBridge { _, _ -> data })
        .evaluate(script, ScriptFrame("a", "legado"))

    @Test fun originalBytesStreamsAndConsumedRawResponseAreDataOnly() {
        val data = data(byteArrayOf(0, 127, -128, -1))
        assertEquals(ScriptResult.Success("[[0,127,-128,-1],0,3,2,[9,127,-128,9],127,2,-1,true,\"undefined\"]"), run("""
            var bytes=java.get('url',{}).bodyAsBytes();var s=java.get('url',{}).bodyStream();
            var first=s.read(),available=s.available();s.mark(4);var a=[9,9,9,9];var n=s.read(a,1,2);
            s.reset();var again=s.read(),skipped=s.skip(9),end=s.read();s.close();
            var closed=false;try{s.read()}catch(e){closed=true}
            [bytes,first,available,n,a,again,skipped,end,closed,typeof s.getClass]
        """, data))
        assertEquals(ScriptResult.Success("[\"POST\",\"h2\",123,456,\"two\",true,\"undefined\",\"undefined\"]"), run("""
            var r=java.connect('url').raw();var consumed=false;try{r.body().bytes()}catch(e){consumed=true}
            [r.request().method(),r.protocol().toString(),r.sentRequestAtMillis(),r.receivedResponseAtMillis(),
             r.headers().get('X-Test'),consumed,typeof r.newBuilder,typeof r.request().url().openConnection]
        """, data))
    }

    @Test fun headerCookieMutationsAndBufferConsumptionMatchPinnedJsoup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<p>one</p>").addHeader("X-Test", "one").addHeader("X-Test", "two"))
            val reference = Jsoup.connect(server.url("/book").toString()).execute()
            reference.header("X-Test", "three").addHeader("x-test", "four").cookie("c", "v")
            reference.multiHeaders()["X-Test"]!!.add("five")
            reference.cookies()["c"] = "changed"
            val expected = listOf(reference.header("x-test"), reference.headers("X-Test").joinToString("|"),
                reference.cookie("c"), reference.bufferUp().parse().select("p").text(), reference.body())
            val result = run("""
                var r=java.get('url',{});r.header('X-Test','three').addHeader('x-test','four').cookie('c','v');
                r.multiHeaders().get('X-Test').add('five');r.cookies().put('c','changed');
                [r.header('x-test'),r.headers('X-Test').join('|'),r.cookie('c'),r.bufferUp().parse().select('p').text(),r.body()]
            """, data("<p>one</p>".toByteArray()))
            assertEquals(ScriptResult.Success(JsonArray(expected.map { JsonPrimitive(it) }).toString()), result)
            assertEquals(ScriptResult.Success("[true,true,true]"), run("""
                var a=java.get('u',{}),b=java.get('u',{}),c=java.get('u',{});a.parse();b.bodyStream();c.body();
                function fails(f){try{f();return false}catch(e){return true}}
                [fails(function(){a.body()}),fails(function(){b.parse()}),fails(function(){c.bodyStream()})]
            """, data("<p>one</p>".toByteArray())))
        }
    }

    @Test fun namedHeaderListsStayLiveUntilReplacementAndRemovalLikePinnedJsoup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("chapter").addHeader("X-Test", "one").addHeader("X-Test", "two"))
            val reference = Jsoup.connect(server.url("/book").toString()).execute()
            val list = reference.headers("x-test")
            val expected = buildJsonArray {
                list.add("three")
                assertEquals("one, two, three", reference.header("X-Test"))
                add(reference.header("X-Test")); add(reference.multiHeaders()["X-Test"]!!.joinToString("|"))
                list[0] = "changed"; list.removeAt(1)
                add(reference.header("x-test")); add(reference.headers()["X-Test"])
                reference.addHeader("X-Test", "four")
                add(list.size); add(list.last())
                reference.header("X-Test", "replacement"); list.add("detached")
                add(reference.header("x-test")); add(list.joinToString("|"))
                val removed = reference.headers("X-Test")
                reference.removeHeader("x-test"); removed.add("removed")
                add(reference.header("X-Test")); add(reference.hasHeader("X-Test"))
                add(reference.multiHeaders().containsKey("X-Test"))
            }
            assertEquals(ScriptResult.Success(expected.toString()), run("""
                var r=java.get('u',{}),list=r.headers('x-test'),out=[];
                list.add('three');out.push(r.header('X-Test'),r.multiHeaders().get('X-Test').join('|'));
                list.set(0,'changed');list.remove(1);out.push(r.header('x-test'),r.headers().get('X-Test'));
                r.addHeader('X-Test','four');out.push(list.size(),list.get(list.size()-1));
                r.header('X-Test','replacement');list.add('detached');out.push(r.header('x-test'),list.join('|'));
                var removed=r.headers('X-Test');r.removeHeader('x-test');removed.add('removed');
                out.push(r.header('X-Test'),r.hasHeader('X-Test'),r.multiHeaders().containsKey('X-Test'));out
            """, data("chapter".toByteArray())))
        }
    }

    @Test fun clearedAndMissingHeaderListsMatchPinnedJsoup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("chapter").addHeader("X-Test", "one").addHeader("X-Test", "two"))
            val reference = Jsoup.connect(server.url("/book").toString()).execute()
            val cleared = reference.headers("X-Test")
            val expected = buildJsonArray {
                cleared.clear()
                add(reference.header("X-Test")); add(reference.hasHeader("X-Test"))
                add(reference.headers().containsKey("X-Test")); add(reference.multiHeaders().containsKey("X-Test"))
                add(reference.multiHeaders()["X-Test"]!!.size)
                reference.addHeader("X-Test", "fresh"); cleared.add("detached")
                add(reference.header("X-Test")); add(cleared.joinToString("|"))
                add(runCatching { reference.headers("missing").add("absent") }.isFailure)
                add(reference.hasHeader("missing"))
            }
            assertEquals(ScriptResult.Success(expected.toString()), run("""
                var r=java.get('u',{}),cleared=r.headers('X-Test');cleared.clear();
                var out=[r.header('X-Test'),r.hasHeader('X-Test'),r.headers().containsKey('X-Test'),
                    r.multiHeaders().containsKey('X-Test'),r.multiHeaders().get('X-Test').size()];
                r.addHeader('X-Test','fresh');cleared.add('detached');out.push(r.header('X-Test'),cleared.join('|'));
                var rejected=false;try{r.headers('missing').add('absent')}catch(e){rejected=true}
                out.push(rejected,r.hasHeader('missing'));out
            """, data("chapter".toByteArray())))
        }
    }

    @Test fun parseDetectsMetaCharsetWithoutReencodingBinarySnapshot() {
        val bytes = "<meta charset=windows-1252><p>caf\u00e9</p>".toByteArray(charset("windows-1252"))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(Buffer().write(bytes)))
            val reference = Jsoup.connect(server.url("/").toString()).execute()
            val before = reference.charset(); val text = reference.parse().select("p").text(); val after = reference.charset()
            assertEquals(ScriptResult.Success(buildJsonArray { add(before?.let(::JsonPrimitive) ?: JsonNull); add(text); add(after) }.toString()),
                run("var r=java.get('u',{}),before=r.charset(),text=r.parse().select('p').text();[before,text,r.charset()]", data(bytes,"text/html")))
        }
    }

    @Test fun bodyAndParseBomPoliciesMatchPinnedJsoupWithAndWithoutDeclaredCharsets() {
        val html = "<p>caf\u00e9 \u4E2D</p>"
        val encodings = listOf(
            "UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            "UTF-32LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0),
            "UTF-32BE" to byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()))
        MockWebServer().use { server ->
            for ((encoding, bom) in encodings) for (declared in listOf(null, "UTF-8", encoding).distinct()) {
                val bytes = bom + html.toByteArray(charset(encoding))
                val type = "text/html" + (declared?.let { "; charset=$it" } ?: "")
                server.enqueue(MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(bytes)))
                val reference = Jsoup.connect(server.url("/").toString()).execute()
                val before = reference.charset()
                val bodyBefore = reference.body()
                // Jsoup 1.16.2 body() does not sniff a BOM or update charset.
                assertEquals(before, reference.charset())
                if (declared == null) assertEquals(bytes.toString(Charsets.UTF_8), bodyBefore)
                assertArrayEquals(bytes, reference.bodyAsBytes())
                val parsed = reference.parse().select("p").text()
                assertEquals("caf\u00e9 \u4E2D", parsed)
                val expected = buildJsonArray {
                    add(before?.let(::JsonPrimitive) ?: JsonNull); add(bodyBefore); add(parsed)
                    add(reference.charset()); add(reference.body())
                    add(JsonArray(bytes.map { JsonPrimitive(it.toInt()) }))
                }
                val snapshot = JsonObject(data(bytes, type) - "body" +
                    ("charset" to (before?.let(::JsonPrimitive) ?: JsonNull)))
                val actual = run("""
                    var r=java.get('u',{}),before=r.charset(),text=r.body(),parsed=r.parse().select('p').text();
                    [before,text,parsed,r.charset(),r.body(),r.bodyAsBytes()]
                """, snapshot)
                assertTrue("$encoding / $declared: $actual", actual is ScriptResult.Success)
                assertEquals("$encoding / $declared", expected, Json.parseToJsonElement((actual as ScriptResult.Success).json))
            }
        }
    }

    @Test fun responseConsumptionSequencesMatchPinnedJsoupIncludingBufferedStreamRejection() {
        // Cover each operation from fresh, buffered, parsed and streamed bodies.
        // Failed reads and bufferUp must not resurrect a consumed response.
        val sequences = listOf(
            listOf("bufferUp", "bodyStream", "bufferUp", "body", "bodyAsBytes", "parse", "parse", "body", "bodyAsBytes"),
            listOf("body", "bodyAsBytes", "parse", "bodyStream"),
            listOf("bodyAsBytes", "body", "parse", "bodyStream"),
            listOf("parse", "bufferUp", "body", "bodyAsBytes", "parse", "bodyStream"),
            listOf("bodyStream", "bufferUp", "body", "bodyAsBytes", "parse", "bodyStream"),
        )
        MockWebServer().use { server ->
            for (sequence in sequences) {
                server.enqueue(MockResponse().setBody("<p>one</p>"))
                val reference = Jsoup.connect(server.url("/book").toString()).execute()
                val expected = sequence.map { operation -> runCatching {
                    when (operation) {
                        "bufferUp" -> reference.bufferUp()
                        "body" -> reference.body()
                        "bodyAsBytes" -> reference.bodyAsBytes()
                        "parse" -> reference.parse()
                        else -> reference.bodyStream().use { it.read() }
                    }
                }.isSuccess }
                val calls = JsonArray(sequence.map(::JsonPrimitive))
                assertEquals(sequence.toString(), ScriptResult.Success(JsonArray(expected.map(::JsonPrimitive)).toString()),
                    run("""
                        var r=java.get('u',{});
                        $calls.map(function(operation){
                            try{var value=r[operation]();if(operation==='bodyStream'){value.read();value.close()}return true}
                            catch(e){return false}
                        })
                    """, data("<p>one</p>".toByteArray())))
            }
        }
    }
}
