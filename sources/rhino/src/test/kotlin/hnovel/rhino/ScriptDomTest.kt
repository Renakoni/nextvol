package hnovel.rhino

import kotlinx.serialization.json.*
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.junit.Assert.*
import org.junit.Test

class ScriptDomTest {
    private val html = "<section><a href='/chapter'>One<b>!</b></a><a>Two</a></section>"
    private val frame = ScriptFrame("a", "legado", baseUrl = "https://fixture.invalid/book", variables = mapOf("result" to JsonPrimitive(html)))
    @Test fun collectionReadsDoNotRescanMembersOrReencodeAnUnchangedRoot() {
        val cx = Context.enter()
        try {
            val realm = ScriptRealm(cx)
            ScriptRealm.install(cx, realm)
            cx.putThreadLocal(bridgeLimitKey, 64 * 1024)
            var encodings = 0
            val root = object : Element("section") {
                override fun outerHtml(): String { encodings++; return super.outerHtml() }
            }
            repeat(32) { root.appendElement("a").attr("href", "/$it") }
            var iterations = 0
            val rows = object : Elements(root.children()) {
                override fun iterator(): MutableIterator<Element> { iterations++; return super.iterator() }
            }
            var reads = 0
            val values = object : AbstractMutableList<String>() {
                private val data = MutableList(32) { it.toString() }
                override val size get() = data.size
                override fun get(index: Int): String { reads++; return data[index] }
                override fun add(index: Int, element: String) = data.add(index, element)
                override fun set(index: Int, element: String) = data.set(index, element)
                override fun removeAt(index: Int) = data.removeAt(index)
            }
            ScriptableObject.putProperty(realm.global, "rows", ScriptDom.wrap(cx, realm.global, rows))
            ScriptableObject.putProperty(realm.global, "values", ScriptData.list(cx, realm.global, values))
            iterations = 0
            reads = 0
            fun evaluate(script: String) = cx.evaluateString(realm.global, script, "collections", 1, null)
            assertEquals(true, evaluate("""
                var first=rows[0];
                for(var i=0;i<rows.size();i++) {
                    if(rows.isEmpty() || rows.get(i).attr('href')!='/'+i) throw 'DOM';
                    if(values.isEmpty() || values.size()!=32 || values.get(i)!=''+i) throw 'List';
                }
                first===rows[0]
            """))
            assertEquals(0, iterations)
            assertEquals(32, reads)
            assertEquals(1, encodings)
            assertEquals("[32,\"/1\",\"changed\",33,\"tail\"]", evaluate("""
                rows.set(0,rows.get(1));rows.get(0).attr('title','changed');values.add('tail');
                JSON.stringify([rows.length,rows[0].attr('href'),rows[1].attr('title'),values.length,values[32]])
            """))
            cx.putThreadLocal(bridgeLimitKey, 32)
            assertThrows(ResultTooLarge::class.java) { evaluate("rows.get(0).attr('href')") }
        } finally { Context.exit() }
    }

    @Test fun elementMethodsChainAndRemainMarkupAtRuleBoundaries() {
        val engine = RhinoScriptEngine(HostBridge { _, _ -> error("No host") })
        assertEquals(ScriptResult.Success("[2,\"One!\",\"One\",\"/chapter\",\"\",\"!\",\"One!\",\"undefined\"]"), engine.evaluate("""
            var es=java.getElements('a');var a=es.get(0);
            [es.size(),a.text(),a.ownText(),a.attr('href'),a.absUrl('href'),a.select('b').first().text(),
             java.getString('a@text',a),typeof a.getClass]
        """, frame))
        assertEquals(ScriptResult.Success("\"invalid DOM argument\""), engine.evaluate("try{java.getElements('a').get(99)}catch(e){e.message}", frame))
    }
    @Test fun responseParseUsesFinalUrlAndRetainedMethodsUseCurrentBudget() {
        val data = buildJsonObject { put("body", html); put("url", frame.baseUrl); put("status", 200); put("message", "OK"); put("headers", JsonObject(emptyMap())) }
        val engine = RhinoScriptEngine(HostBridge { _, _ -> data })
        ScriptLibrary("a", "legado", "var holder={};").use { library ->
            assertEquals(ScriptResult.Success("\"https://fixture.invalid/chapter\""), engine.evaluate(
                "holder.doc=java.get('url',{}).parse();holder.doc.selectFirst('a').absUrl('href')", frame, library))
            val small = RhinoScriptEngine(HostBridge { _, _ -> data }, ScriptLimits(maxBridgeChars = 32))
            assertEquals(FailureCode.ResultTooLarge, (small.evaluate("holder.doc.outerHtml()", frame.copy(variables=emptyMap()), library) as ScriptResult.Failure).code)
        }
    }
}
