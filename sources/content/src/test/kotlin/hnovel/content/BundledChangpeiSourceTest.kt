package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledChangpeiSourceTest {
    @Test fun renderedChapterOpensVerificationAndResumesWithoutLogin() = runBlocking {
        verifyReading(loginFirst = false)
    }

    @Test fun officialWebsiteLoginReadsTheRenderedChapter() = runBlocking {
        verifyReading(loginFirst = true)
    }

    private suspend fun verifyReading(loginFirst: Boolean) {
        var verified = false
        val browser = BrowserExecutor { _, request, options, _, _ ->
            assertFalse(request.headers.keys.any { it.equals("Cookie", true) })
            if (request.url.endsWith("/login/signin")) {
                assertTrue(options.interactive)
                verified = true
                return@BrowserExecutor BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(),
                    "<p>Signed in</p>".toByteArray(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
            assertTrue(request.url.endsWith("/read-71.html"))
            assertTrue(options.nativeWebsite)
            assertTrue(options.script.contains("content.innerText.trim()"))
            if (!options.interactive && !verified) BrokerResult.Failure(RequestStage.Response,
                FailureCode.BrowserRequired, challenge = BrowserChallengeKind.SiteVerification,
                verificationRequest = request.copy(browser = options))
            else {
                verified = true
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                    "<p>验证后的公开正文。</p>".toByteArray(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when {
                    request.path!!.contains("chapterGetInfo") -> error("Do not read the untrusted API payload")
                    request.path == "/book" -> "<h1>测试书</h1><a class='toc' href='/toc'>目录</a>"
                    request.path == "/toc" -> "<li><a href='/legacy?chapter_ids=71'>第一章</a></li>"
                    else -> "{}"
                })
            }
            val bundled = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Female.json").readText())
                .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.gongzicp.com" }
            fixture.source { raw ->
                val host = fixture.server.url("/").toString().removeSuffix("/")
                val content = Json.parseToJsonElement(bundled.getValue("ruleContent").toString()
                    .replace("https://webapi.gongzicp.com", host).replace("https://www.gongzicp.com", host))
                JsonObject(raw + mapOf("header" to bundled.getValue("header"), "ruleContent" to content,
                    "loginUrl" to JsonPrimitive(bundled.getValue("loginUrl").jsonPrimitive.content.replace("https://www.gongzicp.com", host)),
                    "loginUi" to bundled.getValue("loginUi"),
                    "ruleBookInfo" to buildJsonObject { put("name", "h1@text"); put("tocUrl", "a.toc@href") },
                    "ruleToc" to buildJsonObject { put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href") }))
            }.use { source ->
                val id = fixture.server.url("/book").toString()
                val chapter = source.directory(id).single()
                if (loginFirst) {
                    assertTrue(source.loginForm().fields.isEmpty())
                    source.login(emptyMap())
                } else {
                    val failure = runCatching { source.content(id, chapter.id) }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.BrowserRequired, failure.code)
                    val verification = requireNotNull(failure.verification)
                    assertEquals(BrowserChallengeKind.SiteVerification, verification.kind)
                    verification.complete()
                }
                assertTrue(source.content(id, chapter.id).parts.any { it.text?.contains("验证后的公开正文") == true })
            }
        }
    }
}
