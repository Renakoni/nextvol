package hnovel.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RequestCompilerTest {
    @Test fun encodedFormValuesArePreservedUnlessCharsetRequiresEncoding() {
        val rule = """/search,{"method":"POST","body":"trace=one%253Ftype%253Dbook&raw=a+b&q={{key}}"}"""
        assertEquals("trace=one%253Ftype%253Dbook&raw=a%2Bb&q=%252F", request(rule, "%2F").body)
        assertEquals("trace=one%25253Ftype%25253Dbook&raw=a%2Bb&q=%252F",
            request(rule.replace("\"method\"", "\"charset\":\"UTF-8\",\"method\""), "%2F").body)
        val longValue = "%41".repeat(12000)
        assertEquals("q=$longValue", request("""/search,{"method":"POST","body":"q=$longValue"}""").body)
    }

    @Test fun singleQuotedOptionsPreserveEscapesAndCharsetAwareSubstitution() {
        val single = """/search/,{'method':'POST','charset':'GB2312','body':'keyword={{key}}','header':{'X-Literal':'It\'s "quoted"\\end','X-Key':'{{key}}'}}"""
        val strict = """/search/,{"method":"POST","charset":"GB2312","body":"keyword={{key}}","header":{"X-Literal":"It's \"quoted\"\\end","X-Key":"{{key}}"}}"""
        val actual = request(single, "校园&role=admin")
        assertEquals(request(strict, "校园&role=admin"), actual)
        assertEquals("keyword=%D0%A3%D4%B0%26role%3Dadmin", actual.body)
        assertEquals("It's \"quoted\"\\end", actual.headers["X-Literal"])
    }

    @Test fun singleQuotedNestedBodiesAndEncodedHeadersUseTheSameDataGrammar() {
        val single = """/search,{'method':'POST','header':"{'X-Test':'a\\\\b'}",'body':{'q':'{{key}}','values':['it\'s',true,null,{'n':2}]}}"""
        val strict = """/search,{"method":"POST","header":{"X-Test":"a\\b"},"body":{"q":"{{key}}","values":["it's",true,null,{"n":2}]}}"""
        assertEquals(request(strict, "a\"b"), request(single, "a\"b"))
        assertEquals(request("""/search,{"method":"POST","body":{"q":"{{key}}"}}"""),
            request("""/search,{'method':'POST','body':"{'q':'{{key}}'}"}"""))
    }

    @Test fun malformedOptionalDataIsIgnoredWithoutEvaluatingObjectSyntax() {
        for (options in listOf("{'method':(function(){return 'POST'})()}",
            "{'method':'POST',}", "{'body':'unterminated}")) {
            val actual = request("/search,$options")
            assertEquals("GET", actual.method)
            assertEquals("https://fixture.invalid/search", actual.url)
            assertNull(actual.body)
        }
        assertEquals(CompiledRequest.Rejected(FailureCode.InvalidRequest),
            compiler.compile("r", "/search,{'header':\"{'X':'unterminated}\"}", "https://fixture.invalid/"))
    }

    @Test fun nestedJsonInsideOptionStringsIsBoundedBeforeParsing() {
        val nested = "[".repeat(2000) + "0" + "]".repeat(2000)
        val denied = CompiledRequest.Rejected(FailureCode.InvalidRequest)
        assertEquals(denied, RequestCompiler().compile("x", "/x,{\"header\":$nested}", "https://fixture.invalid/"))
        assertEquals(denied, RequestCompiler().compile("x", "/x,{\"header\":${kotlinx.serialization.json.JsonPrimitive(nested)}}", "https://fixture.invalid/"))
        assertEquals(denied, RequestCompiler().compile("x", "/x,{\"method\":\"POST\",\"body\":${kotlinx.serialization.json.JsonPrimitive(nested)}}", "https://fixture.invalid/"))
    }
    private val compiler = RequestCompiler()
    private fun request(rule: String, key: String = "校园", page: Int = 2): BrokerRequest {
        val result = compiler.compile("request", rule, "https://fixture.invalid/base/", key, page)
        assertTrue(result.toString(), result is CompiledRequest.Ready)
        return (result as CompiledRequest.Ready).request
    }
    @Test fun staticUrlOptionsRelativePathsEncodingAndPageAlternatives() {
        val request = request("""../search?q={{key}}&p={{page}}&section=<first,last>,{"charset":"GB2312","retry":2,"header":{"X-Test":"value"}}""")
        assertEquals("https://fixture.invalid/search?q=%D0%A3%D4%B0&p=2&section=last", request.url)
        assertEquals(2, request.retry)
        assertEquals("value", request.headers["X-Test"])
        assertEquals("https://fixture.invalid/base/last", request("<first,last>", page = 9).url)
        assertEquals("https://fixture.invalid/base/search?q=%D0%A3%D4%B0", request("search?q=%D0%A3%D4%B0").url)
    }
    @Test fun formAndJsonTemplatesCannotInjectOtherFields() {
        val form = request("""search,{"method":"POST","charset":"GB2312","body":"q={{key}}&p={{page}}"}""", "校园&role=admin")
        assertEquals("q=%D0%A3%D4%B0%26role%3Dadmin&p=2", form.body)
        assertTrue(form.headers["Content-Type"]!!.startsWith("application/x-www-form-urlencoded"))
        val declared = request("""search,{"method":"POST","headers":{"Content-Type":"application/x-www-form-urlencoded; charset=UTF-8"},"body":"q={{key}}&p={{page}}"}""", "a&b=c")
        assertEquals("q=a%26b%3Dc&p=2", declared.body)
        val json = request("""search,{"method":"POST","body":{"q":"{{key}}","p":"{{page}}"}}""", "a\"b")
        assertEquals("""{"q":"a\"b","p":"2"}""", json.body)
        assertEquals("POST", json.method)
        val xml = request("""search,{"method":"POST","body":"<query>{{key}}</query>"}""", "word")
        assertEquals("<query>word</query>", xml.body)
    }
    @Test fun jsBrowserAndUnknownOptionsRemainExplicitRequirements() {
        for (rule in listOf("@js:result", "<js>result</js>", "{{page + 1}}", """/x,{"js":"result"}""")) {
            assertEquals(CompiledRequest.Rejected(FailureCode.ScriptRequired), compiler.compile("r", rule, "https://fixture.invalid"))
        }
        val browser = compiler.compile("r", """/x,{"webView":true,"webJs":"document.title","webViewDelayTime":200}""", "https://fixture.invalid") as CompiledRequest.Ready
        assertEquals(BrowserOptions("document.title", 200, nativeWebsite = true), browser.request.browser)
        assertEquals(CompiledRequest.Rejected(FailureCode.UnknownOption), compiler.compile("r", """/x,{"surprise":1}""", "https://fixture.invalid"))
        assertEquals(CompiledRequest.Rejected(FailureCode.InvalidRequest), compiler.compile("r", "file:///private", ""))
    }
    @Test fun protocolRoundTripsButDiagnosticStringsOmitSecrets() {
        val original = BrokerRequest("secret-id", "https://fixture.invalid/token=secret", headers = mapOf("Cookie" to "secret-cookie"), body = "secret-body")
        assertEquals(original, Json.decodeFromString<BrokerRequest>(Json.encodeToString(original)))
        assertFalse(original.toString().contains("secret"))
        val response = BrokerResponse(200, original.url, mapOf("Set-Cookie" to listOf("secret")), "secret-body".toByteArray(), "UTF-8", 0)
        assertFalse(BrokerResult.Success(response).toString().contains("secret"))
        assertFalse(StorageRequest(StorageArea.Account, "secret-key", "secret-value").toString().contains("secret"))
    }
}
