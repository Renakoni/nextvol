package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class LoginCheckVerificationTest {
    private val challenge = "<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/test'></script>"
    private val chapter = "<article><p>Recovered chapter</p></article>"

    private fun rules(raw: JsonObject, hook: String) = JsonObject(raw + mapOf(
        "loginCheckJs" to JsonPrimitive(hook),
        "ruleContent" to buildJsonObject { put("content", "article@html") }
    ))

    @Test fun automaticRecoveryConsumesTheScriptResponseWithoutASecondHookOrHostRefetch() = runBlocking {
        RuleSourceFixture(BrowserExecutor { _, _, _, _, _ -> error("Automatic recovery must not open a browser") }).use { fixture ->
            val original = fixture.server.dispatcher
            var reads = 0
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/c/1") {
                    reads++
                    if (reads == 1) MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody(challenge)
                    else MockResponse().setBody(chapter)
                } else original.dispatch(request)
            }
            fixture.source { rules(it, """
                if(result.getUrl().endsWith('/c/1')) {
                    if(source.get('checked')) throw 'recursive hook';
                    source.put('checked','yes');
                    if(result.code()!==403 || result.getBody().indexOf('Just a moment')<0) throw 'missing challenge';
                    result=java.getStrResponse();
                }
                result;
            """.trimIndent()) }.use { source ->
                val book = source.search("fixture").single()
                source.information(book.id)
                val content = source.content(book.id, fixture.server.url("/c/1").toString())
                assertEquals(listOf("Recovered chapter"), content.parts.mapNotNull { it.text })
                assertEquals(2, reads)
            }
        }
    }

    @Test fun chapterHookAwaitsHostInteractionAndUsesBothBrowserResponseContracts() = runBlocking {
        for (dom in listOf(false, true)) {
            var opened = 0
            var hostCalls = 0
            val browser = BrowserExecutor { _, request, options, guard, _ ->
                assertTrue(options.interactive)
                guard.commit { opened++ }
                BrokerResult.Success(BrokerResponse(if (options.verificationCode) 200 else 0, request.url, emptyMap(),
                    (if (options.verificationCode) "A7c" else chapter).toByteArray(), "UTF-8", 0,
                    kind = if (options.verificationCode) ResponseKind.Http else ResponseKind.BrowserDocument))
            }
            RuleSourceFixture(browser).use { fixture ->
                val original = fixture.server.dispatcher
                var reads = 0
                fixture.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/c/1") {
                        reads++
                        MockResponse().setBody(if (reads == 1) challenge else chapter)
                    } else original.dispatch(request)
                }
                fixture.source { rules(it, """
                    if(result.getBody().indexOf('Just a moment')>=0) {
                        ${if (dom) "if(java.getVerificationCode('/captcha')!=='A7c') throw 'captcha';" else ""}
                        result=java.startBrowserAwait(result.getUrl(),'verify'${if (dom) ",false" else ""});
                        if(result.code()!==200 || result.getBody()!==result.body() || result.getUrl()!==result.url()) throw 'StrResponse';
                    }
                    result;
                """.trimIndent()) }.use { source ->
                    val book = source.search("fixture").single()
                    source.information(book.id)
                    val content = withContext(SourceScriptInteraction { verification -> hostCalls++; verification.complete() }) {
                        source.content(book.id, fixture.server.url("/c/1").toString())
                    }
                    assertEquals(listOf("Recovered chapter"), content.parts.mapNotNull { it.text })
                    assertEquals(if (dom) 1 else 2, reads)
                    assertEquals(if (dom) 2 else 1, opened)
                    assertEquals(opened, hostCalls)
                }
            }
        }
    }

    @Test fun unchangedChallengeOffersFallbackButAnAlreadyUsedInteractionDoesNot() = runBlocking {
        for (manual in listOf(false, true)) {
            var opened = 0
            val browser = BrowserExecutor { _, request, options, _, _ ->
                assertTrue(options.interactive)
                opened++
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), challenge.toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("cf-mitigated", "challenge").setBody("<html>blocked</html>")
                }
                fixture.source { rules(it, if (manual) "java.startBrowserAwait(result.getUrl(),'verify',false)" else "result") }.use { source ->
                    val failure = runCatching {
                        withContext(SourceScriptInteraction { it.complete() }) { source.search("fixture") }
                    }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.BrowserRequired, failure.code)
                    if (manual) assertNull(failure.verification)
                    else assertEquals(BrowserChallengeKind.Cloudflare, failure.verification!!.kind)
                    assertEquals(if (manual) 1 else 0, opened)
                    assertEquals(1, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun deferredBrowserDocumentReachesTheHookAndBackgroundInteractionStopsTheWorker() = runBlocking {
        var opened = 0
        val browser = BrowserExecutor { _, request, options, _, _ ->
            assertFalse(options.interactive)
            assertTrue(request.deferChallenge)
            opened++
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), challenge.toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source { raw -> JsonObject(rules(raw, """
                if(!result.isBrowserDocument() || result.getBody().indexOf('Just a moment')<0) throw 'missing document';
                try { java.startBrowserAwait(result.getUrl(),'verify'); } catch(e) { java.ajax('/must-not-run'); }
                result;
            """.trimIndent()) + ("browserRead" to JsonPrimitive(true))) }.use { source ->
                val failure = runCatching { source.search("fixture") }.exceptionOrNull()
                assertTrue(failure.toString(), failure is SourceScriptInteractionRequired)
                assertEquals(1, opened)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun cancelledVerificationStopsTheHookEvenWhenItCatchesBridgeErrors() = runBlocking {
        var opened = 0
        val browser = BrowserExecutor { _, _, _, _, _ ->
            opened++
            BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired)
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source { rules(it, """
                try { java.startBrowserAwait(result.getUrl(),'verify'); } catch(e) { java.ajax('/must-not-run'); }
                result;
            """.trimIndent()) }.use { source ->
                val failure = runCatching {
                    withContext(SourceScriptInteraction { it.complete() }) { source.search("fixture") }
                }.exceptionOrNull()
                assertTrue(failure.toString(), failure is CancellationException)
                assertEquals(1, opened)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test(timeout = 10000) fun earlierFallbackCanCompleteWhileTheCurrentHookOwnsTheSourceQueue() = runBlocking {
        var challenged = true
        val page = "<li><a href='/book'><h2>Recovered</h2></a></li>"
        val browser = BrowserExecutor { _, request, _, _, _ ->
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), page.toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (challenged) challenge else page)
            }
            fixture.source { rules(it, """
                result.getBody().indexOf('Just a moment')>=0 ? result : java.startBrowserAwait(result.getUrl(),'verify',false)
            """.trimIndent()) }.use { source ->
                val first = runCatching { source.search("first") }.exceptionOrNull() as SourceContentException
                challenged = false
                val books = withContext(SourceScriptInteraction { current ->
                    first.verification!!.complete()
                    current.complete()
                }) { source.search("second") }
                assertEquals("Recovered", books.single().title)
            }
        }
    }
}
