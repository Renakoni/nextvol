package hnovel.content

import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class PixivPresentationTest {
    private val original = Json.parseToJsonElement(File(
        "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
        .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
    private val browser = object : BrowserExecutor {
        override suspend fun defaultUserAgent() = "Test WebView-UA"
        override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
            guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("Unexpected browser request")
    }

    private fun definition(fixture: RuleSourceFixture, settings: String = "", localUrls: Boolean = true): JsonObject {
        // Run the shipped UI, catalogue, library and actions; only provide an isolated test account.
        val local = if (localUrls) Json.parseToJsonElement(original.toString().replace(
            "https://www.pixiv.net", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject else
            // Preserve generated direct-connection URLs while reading the isolated fixture's Cookie jar.
            JsonObject(original + ("jsLib" to JsonPrimitive(original.getValue("jsLib").jsonPrimitive.content.replace(
                "java.getCookie(\"https://www.pixiv.net/\", null)", "java.getCookie('${fixture.server.url("/")}', null)"))))
        val setup = """
            if (!cache.get('pixivPresentationTest')) {
                cache.put('pixivUid','12345');
                cache.put('pixivCookie','PHPSESSID=12345_test');
                cookie.setCookie('${fixture.server.url("/")}', 'PHPSESSID=12345_test');
                cache.put('pixivCsrfToken','test-token');
                cache.put('checkTimes','1');
                let settings = setDefaultSettings();
                $settings
                putInCacheObject('pixivSettings',settings);
                cache.put('pixivPresentationTest','ready');
            }
        """.trimIndent()
        return JsonObject(local + listOf("loginUi", "exploreUrl").associateWith { field ->
            JsonPrimitive("@js:" + setup + "\n" + local.getValue(field).jsonPrimitive.content.removePrefix("@js:"))
        })
    }

    @Test fun chapterIgnoresLegacyCommentAndDebugSettingsWithoutCommentRequests() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"error":false,"body":{"id":"1","title":"One","content":"Chapter body",
                            "description":"","commentCount":100,"userId":"12345","userName":"Author"}}""")
                    else normal.dispatch(request)
            }
            fixture.source(profile = EXTENSION_PROFILE) { raw ->
                JsonObject(definition(fixture, "settings.SHOW_COMMENTS=true;settings.DEBUG=true;") +
                    raw.filterKeys { it in setOf("searchUrl", "ruleSearch", "ruleBookInfo", "ruleToc", "header") } +
                    ("ruleContent" to buildJsonObject {
                        put("content", original.getValue("ruleContent").jsonObject.getValue("content"))
                    }))
            }.use { source ->
                source.loginForm()
                val book = source.search("fixture").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                val before = fixture.server.requestCount
                repeat(before) { fixture.server.takeRequest(1, TimeUnit.SECONDS)!! }
                assertEquals(listOf(ContentPart(text = "Chapter body")), source.content(book.id, chapter.id).parts)
                val paths = List(fixture.server.requestCount - before) {
                    fixture.server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath
                }
                // The shipped loginCheckJs explicitly refetches once; neither request is for comments.
                assertEquals(listOf("/c/1", "/c/1"), paths)
            }
        }
    }

}
