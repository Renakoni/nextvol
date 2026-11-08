package hnovel.execution

import hnovel.rules.RuleValue
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkerDiscoveryReadPlanTest {
    private fun accepts(urls: List<String> = listOf("/list?p={{page}}"), header: String = "",
        rules: List<String> = listOf("li", "a@href")): Boolean {
        val result = WorkerDiscoveryReadPlan.evaluate(ExecutionTask.DiscoveryReadPlan(urls, header, rules), ExecutionLimits())
        assertTrue(result.toString(), result is ExecutionResult.Success)
        return (Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Text).value == "true"
    }

    @Test fun fixedHtmlJsonAndLocalUrlTemplatesCanReadIndependently() {
        assertTrue(accepts(rules = listOf(".list li:has(a[href])", "$.items[*]", "a@href", "")))
        assertTrue(accepts(urls = listOf("/n?sort={{({'最新':'date','阅读量':'view','收藏数':'like','字数':'char'})[infoMap['文章排序']]||'date'}}&p={{page}}"),
            header = "@js:JSON.stringify({'User-Agent':java.getWebViewUA()})"))
        assertTrue(accepts(rules = listOf("@js:var p=String(baseUrl).match(/[?&]p=(\\d+)/);" +
            " /\\/rank(?:\\/s)?\\?/.test(String(baseUrl))&&p&&Number(p[1])>1?[]:" +
            "java.getElements(\".container li.list-group-item:has(a[href^='/n/']),.container li.list-group-item:has(a[href^='/s/']:not([href='/s/undefined']))\")",
            "@js:String(java.getString(\"body:has(.col-lg-9 > ul > li:has(a[href^='/n/'])) .col-lg-3 a[href^='/tag/']@text\"))" +
                ".split(String.fromCharCode(10)).slice(0,6).join(',')",
            "@js:String(baseUrl).split(/[?#]/)[0]+'?p=1'")))
    }

    @Test fun scriptsWithSharedStateNetworkOrUnknownCodeKeepTheirOrder() {
        for (code in listOf("infoMap.mode='new'", "infoMap.save()", "java.ajax('/next')", "java.put('x','y')",
            "java.get('x')", "cookie.getCookie(baseUrl)", "book.name='next'", "book.getVariable('x')",
            "source.getLoginInfo()", "eval('result')", "new String('x')", "(()=>result)()", "for(;;){}",
            "Object.prototype.x=1", "var java='x';java", "var String='x';String", "result.constructor('return this')()",
            "result['constructor']('return this')()", "result.sort()", "result.push('x')", "var x={get v(){return 1}};x.v")) {
            assertFalse(code, accepts(rules = listOf("@js:$code")))
            assertFalse(code, accepts(urls = listOf("/list?q={{$code}}")))
            assertFalse(code, accepts(header = "@js:$code"))
        }
        assertFalse(accepts(rules = listOf("@js:'{{java.ajax(\"/next\")}}'")))
        assertFalse(accepts(rules = listOf("@js:'\$1'")))
    }

    @Test fun fixedAccountCacheReadsCanBeSnapshottedButWritesAndDynamicCallsCannot() {
        assertTrue(accepts(urls = listOf("/user/{{cache.get(\"pixivUid\")}}/novels/bookmarks?offset={{(page-1)*24}}")))
        for (code in listOf("cache.put('account','other')", "cache.delete('account')",
            "cache.get(page)", "cache.getFromMemory('account')", "var cache={};cache.get('account')")) {
            assertFalse(code, accepts(urls = listOf("/user/{{$code}}")))
        }
        assertFalse(accepts(rules = listOf("@js:cache.get('account')")))
    }

    @Test fun nestedDynamicSelectorsAndTemplateWritesAreRejected() {
        for (selector in listOf("li@js:java.put('x','y')", "{{java.ajax('/next')}}", "@put:{x:'li'}li", "@get:{x}")) {
            val literal = Json.encodeToString(kotlinx.serialization.serializer<String>(), selector)
            assertFalse(selector, accepts(rules = listOf("@js:java.getElements($literal)")))
        }
        assertFalse(accepts(rules = listOf("@js:java.getElements(result)")))
        assertFalse(accepts(header = "@js:java.getString('title@text')"))
        assertFalse(accepts(urls = listOf("/list?q={{java.getString('title@text')}}")))
        assertFalse(accepts(rules = listOf("@js:var x=")))
        assertFalse(accepts(urls = listOf("/list?p={{page")))
    }

    @Test fun checksHaveSmallInputAndOutputBounds() {
        assertFalse(accepts(urls = emptyList()))
        assertFalse(accepts(urls = List(65) { "/list" }))
        assertFalse(accepts(rules = List(65) { "li" }))
        assertFalse(accepts(header = "x".repeat(65537)))
        assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), WorkerDiscoveryReadPlan.evaluate(
            ExecutionTask.DiscoveryReadPlan(listOf("/list"), "", emptyList()), ExecutionLimits(maxOutputBytes = 1)))
    }

    @Test fun independentRulesRecheckPurityBeforeExecutingAnySourceCode() {
        val identity = ExecutionIdentity("source", "legado", "1", "test")
        WorkerRuntime().use { runtime ->
            fun execute(rule: String, library: String? = null): ExecutionResult = ExecutionWire.decodeResult(
                runtime.executeSerialized(ExecutionWire.encode(identity, ExecutionTask.Rule(rule,
                    RuleValue.Text("<li>one</li>"), output = hnovel.rules.OutputKind.Text,
                    libraryCode = library, readOnly = true), ExecutionLimits())
                    .toString(Charsets.UTF_8)).toByteArray())
            val result = execute("@js:java.getString('li@text')")
            assertTrue(result.toString(), result is ExecutionResult.Success)
            assertEquals(RuleValue.Text("one"), Json.decodeFromString(ExecutedRule.serializer(),
                (result as ExecutionResult.Success).output).value)
            for (rule in listOf("@js:source.put('x','y')", "@js:java.ajax('/next')", "@put:{x:'li'}li",
                "@js:infoMap.mode='changed'", "@js:cache.get('account')")) {
                assertEquals(rule, ExecutionResult.Failure(FailureCode.InvalidTask), execute(rule))
            }
            assertEquals(ExecutionResult.Failure(FailureCode.InvalidTask), execute("li@text", "var shared={n:0}"))
        }
    }
}
