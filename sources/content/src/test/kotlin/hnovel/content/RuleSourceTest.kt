package hnovel.content

import hnovel.execution.*
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

class RuleSourceTest {
    @Test fun httpCaptchaDuringPaginationDoesNotReturnOrSaveATruncatedDirectory() = runBlocking {
        var verified = false
        val opened = mutableListOf<String>()
        val browser = BrowserExecutor { session, request, options, _, _ ->
            assertTrue(options.interactive)
            assertFalse(session.browserRead)
            opened += request.url
            verified = true
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "verified".toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath == "/toc/2" && !verified ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/WAF/VERIFY/CAPTCHA?from=/toc/2")
                    request.requestUrl!!.encodedPath == "/WAF/VERIFY/CAPTCHA" ->
                        MockResponse().setBody("<html><title>Verify Yourself</title><form id='ui-form'>Slide to Unlock</form></html>")
                    else -> original.dispatch(request)
                }
            }
            fixture.source().use { source ->
                val book = source.search("fixture").single()
                // Information also attempts a directory when no update marker exists.
                val first = failure { source.information(book.id) }
                assertEquals(ContentError.BrowserRequired, first.code)
                assertEquals(BrowserChallengeKind.SiteVerification, first.verification!!.kind)
                assertTrue(opened.isEmpty())
                assertEquals(ContentError.BrowserRequired, failure { source.directory(book.id) }.code)
                first.verification.complete()
                assertEquals(listOf(fixture.server.url("/toc/2").toString()), opened)
                val chapters = source.directory(book.id)
                assertEquals(listOf("Volume one", "One", "Two"), chapters.map { it.title })
                assertTrue(source.content(book.id, chapters.last().id).parts.any { it.text == "second chapter" })
            }
        }
    }

    @Test fun bookAndTocUrlRulesSelectTheFirstLinkBeforeLaterScripts() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                    "/search" -> MockResponse().setBody("<li><h2>Book</h2><a href='/book/one'>Read</a><a href='/author'>Author</a></li>")
                    "/book/one" -> MockResponse().setBody("<h1>Book</h1><nav><a href='/toc/1'>Chapters</a><a href='/comments'>Comments</a></nav>")
                    else -> original.dispatch(request)
                }
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("bookUrl" to JsonPrimitive("a@href@js:result"))),
                "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject + ("tocUrl" to JsonPrimitive("nav@a@href@js:result")))
            )) }.use { source ->
                val book = source.search("fixture").single()
                assertEquals(fixture.server.url("/book/one").toString(), book.id)
                assertEquals(fixture.server.url("/toc/1").toString(), source.information(book.id).tocUrl)
                assertEquals(2, source.directory(book.id).count { !it.isVolume })
            }
        }
    }

    @Test fun sourceUrlBeanPropertyUsesTheSameIdentityAsGetKey() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + ("ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
                ("bookUrl" to JsonPrimitive("@js:source.bookSourceUrl.replace(/source-A$/, '')+'book/one'"))))) }.use { source ->
                val book = source.search("fixture").single()
                assertEquals(fixture.server.url("/book/one").toString(), book.id)
                assertEquals(2, source.directory(book.id).count { !it.isVolume })
            }
        }
    }

    @Test fun searchKeepsLargeListsAndDoesNotRejectAllBooksAtOneThousandRows() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val html = (1..1462).joinToString("") { "<li><a href='/book/$it'><h2>Book $it</h2></a></li>" }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(html)
            }
            fixture.source { raw -> JsonObject(raw + ("ruleSearch" to buildJsonObject {
                put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
            })) }.use { source ->
                val books = source.search("fixture")
                assertEquals(1462, books.size)
                assertEquals("Book 1462", books.last().title)
            }
        }
    }

    @Test fun invalidLockedChapterLinksDoNotDiscardTheReadableCatalogue() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath == "/toc/2")
                    MockResponse().setBody("<li><a href='javascript:void(0);'>Locked</a></li>".repeat(3))
                else original.dispatch(request)
            }
            fixture.source().use { source ->
                val book = source.search("fixture").single()
                val chapters = source.directory(book.id)
                assertEquals(listOf("Volume one", "One", "Locked"), chapters.map { it.title })
                assertTrue(source.content(book.id, chapters[1].id).parts.any { it.text == "A first" })
                val before = fixture.server.requestCount
                assertEquals(ContentError.InvalidRule, failure { source.content(book.id, chapters.last().id) }.code)
                assertEquals(before, fixture.server.requestCount)
            }
        }
    }

    @Test fun largePageChromeDoesNotPreventReadingItsSmallChapter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val html = "<!--" + "x".repeat(9 * 1024 * 1024) + "--><article>Readable chapter</article>"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath == "/c/1")
                    MockResponse().setBody(html) else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject { put("content", "article@text") })) }.use { source ->
                val book = source.search("fixture").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                assertEquals(listOf("Readable chapter"), source.content(book.id, chapter.id).parts.mapNotNull { it.text })
            }
        }
    }

    @Test fun loginCheckCanReturnTheUnchangedCatalogueResponse() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath == "/toc/2")
                    MockResponse().setBody("<!--" + "x".repeat(350000) + "--><li><a href='/c/2'>Two</a></li>")
                else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(raw + ("loginCheckJs" to JsonPrimitive("result"))) }.use { source ->
                val book = source.search("fixture").single()
                assertEquals(listOf("Volume one", "One", "Two"), source.directory(book.id).map { it.title })
            }
        }
    }

    @Test fun scriptLedBookListsCanRecoverFromAnHttpErrorResponse() = runBlocking {
        for (discovery in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.requestUrl!!.encodedPath == "/placeholder")
                        okhttp3.mockwebserver.MockResponse().setResponseCode(404).setBody("placeholder")
                    else original.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "searchUrl" to JsonPrimitive("/placeholder"),
                "exploreUrl" to JsonPrimitive("Latest::/placeholder"),
                (if (discovery) "ruleExplore" else "ruleSearch") to JsonObject(raw.getValue("ruleSearch").jsonObject +
                    ("bookList" to JsonPrimitive("<js>java.ajax('/search')</js>li")))
            )) }).use { source ->
                val books = if (discovery) source.openDiscovery("http-error").page("/placeholder", 1, emptyMap())
                    else source.search("title")
                assertEquals("Same title", books.single().title)
                assertEquals(listOf("/placeholder", "/search"), (1..2).map { fixture.server.takeRequest().requestUrl!!.encodedPath })
            }
        }
    }

    @Test fun aDirectoryContainingOnlyVolumeHeadingsIsNotAReadableSuccess() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleToc" to JsonObject(
                raw.getValue("ruleToc").jsonObject + ("isVolume" to JsonPrimitive("@js:true")))))
            }).use { source ->
                val id = source.search("title").single().id
                assertEquals(ContentError.EmptyContent, failure { source.directory(id) }.code)
            }
        }
    }

    @Test fun decimalZeroFlagsDoNotTurnChaptersIntoVolumesOrPaidEntries() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + ("ruleToc" to JsonObject(
                raw.getValue("ruleToc").jsonObject + listOf("isVolume", "isVip", "isPay")
                    .associateWith { JsonPrimitive("@js:'0.0'") }
            ))) }.use { source ->
                val chapters = source.directory(source.search("title").single().id)
                assertTrue(chapters.isNotEmpty())
                assertTrue(chapters.none { it.isVolume || it.isVip || it.isPay })
            }
        }
    }

    @Test fun regexChapterIdsInsideScriptsRemainDistinctAcrossCataloguePages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + ("ruleToc" to buildJsonObject {
                put("chapterList", """:<li><a href='/c/(\d+)'>([^<]+)</a></li>""")
                put("chapterName", "${'$'}2")
                put("chapterUrl", "@js:'/c/${'$'}1'")
                put("nextTocUrl", "a.next@href")
            })) }.use { source ->
                val book = source.search("fixture").single()
                val chapters = source.directory(book.id)
                assertEquals(listOf("One", "Two"), chapters.map { it.title })
                assertEquals(listOf("/c/1", "/c/2").map { fixture.server.url(it).toString() }, chapters.map { it.id })
                assertTrue(source.content(book.id, chapters.first().id).parts.any { it.text == "A first" })
            }
        }
    }

    @Test fun importedProfilesApplyTheSameDesktopIdentityToHttpAndBrowserRequests() = runBlocking {
        for (profile in listOf(hnovel.imports.LEGADO_PROFILE, hnovel.imports.EXTENSION_PROFILE)) {
            val browserHeaders = mutableListOf<Map<String, String>>()
            val browser = BrowserExecutor { _, request, _, _, _ ->
                browserHeaders += request.headers
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                    "<li><a href='/book/one'><h2>Fixture</h2></a></li>".toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.source(profile = profile).use { source ->
                    source.search("fixture")
                    assertEquals(DESKTOP_USER_AGENT, fixture.server.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS)!!.getHeader("User-Agent"))
                }
                fixture.source("browser", profile) { JsonObject(it + ("browserRead" to JsonPrimitive(true))) }.use { source ->
                    source.search("fixture")
                    assertEquals(DESKTOP_USER_AGENT, browserHeaders.single().entries.single { it.key.equals("User-Agent", true) }.value)
                }
            }
        }
    }

    @Test fun sourceFallbackAndRowVariablesPersistThroughTheProductionPipeline(): Unit = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
            "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject + mapOf(
                "bookList" to JsonPrimitive("<js>java.put('fromList','list');source.put('fallback','stored');result</js>li"),
                "name" to JsonPrimitive("<js>java.put('row','row value');result</js>h2@text")
            )),
            "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                ("intro" to JsonPrimitive("""@js:[book.getVariable('row'),java.get('fallback'),source.get('fromList'),java.get('bookName')].join('|')""")))
        )) }).use { source ->
            val book = source.search("title").single()
            assertEquals("row value|stored|list|Same title", source.information(book.id).description)
        } }
    }

    @Test fun importedNumericAndTextRatesReachTheBrowserSession() = runBlocking {
        for (rate in listOf(JsonPrimitive(350), JsonPrimitive("1/350"))) {
            val starts = mutableListOf<Long>()
            val browser = BrowserExecutor { session, request, _, _, _ ->
                session.awaitBrowserAdmission()
                starts += System.nanoTime() / 1_000_000
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                    "<li><a href='/book/one'><h2>Fixture</h2></a></li>".toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
            RuleSourceFixture(browser).use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "browserRead" to JsonPrimitive(true), "concurrentRate" to rate,
                "ruleSearch" to buildJsonObject { put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href") }
            )) }).use { source ->
                source.search("first")
                source.search("second")
                assertEquals(2, starts.size)
                assertTrue(starts[1] - starts[0] >= 300)
            } }
        }
    }

    @Test fun verificationKeepsTheExactFailedRequestAndCannotOutliveItsSource() = runBlocking {
        val opened = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, options, guard, _ ->
            guard.commit {}
            if (!options.interactive) BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                challenge = BrowserChallengeKind.Cloudflare, verificationRequest = request)
            else {
                assertEquals("document.documentElement.outerHTML", options.script)
                opened += request.url
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "verified".toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            val source = fixture.source(customize = { JsonObject(it + ("browserRead" to JsonPrimitive(true))) })
            val first = runCatching { source.search("first") }.exceptionOrNull() as SourceContentException
            val second = runCatching { source.search("second") }.exceptionOrNull() as SourceContentException
            assertEquals(fixture.server.url("/").toString(), first.verification!!.origin)
            assertEquals(first.verification.origin, second.verification!!.origin)
            first.verification!!.complete()
            assertTrue(opened.single(), opened.single().contains("q=first"))
            source.close()
            assertEquals(ContentError.Unavailable,
                (runCatching { second.verification!!.complete() }.exceptionOrNull() as SourceContentException).code)
            assertEquals(1, opened.size)
        }
    }

    @Test fun verificationRetainsTheOriginalDynamicReadinessScript() = runBlocking {
        val script = "document.querySelector('#ready') ? document.documentElement.outerHTML : null"
        val browser = BrowserExecutor { _, request, options, _, _ ->
            if (!options.interactive) BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                challenge = BrowserChallengeKind.Cloudflare, verificationRequest = request.copy(browser = options))
            else {
                assertEquals(script, options.script)
                assertEquals(1200L, options.delayMillis)
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "ready".toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
            "browserRead" to JsonPrimitive(true), "searchUrl" to JsonPrimitive("/search," + buildJsonObject {
                put("webView", true); put("webJs", script); put("webViewDelayTime", 1200)
            })
        )) }).use { source ->
            val failure = runCatching { source.search("fixture") }.exceptionOrNull() as SourceContentException
            failure.verification!!.complete()
        } }
    }

    @Test fun verificationSavesOnlyTheSessionAndFailuresKeepThePreviousLoginFact() = runBlocking {
        lateinit var session: SourceSession
        var response: BrokerResult? = null
        var cancel = false
        val browser = BrowserExecutor { current, request, options, _, _ ->
            session = current
            if (!options.interactive) BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                challenge = BrowserChallengeKind.Login, verificationRequest = request)
            else if (cancel) throw CancellationException("Fixture cancelled")
            else response ?: BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "ready".toByteArray(), "UTF-8", 0,
                kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture -> fixture.source(customize = { raw -> JsonObject(raw +
            ("browserRead" to JsonPrimitive(true))) }).use { source ->
            val failure = runCatching { source.search("fixture") }.exceptionOrNull() as SourceContentException
            val status = StorageRequest(StorageArea.Account, "login/status")
            session.write(status.copy(value = "authenticated"))
            for (result in listOf(BrokerResult.Failure(RequestStage.Connect, hnovel.network.FailureCode.Dns),
                BrokerResult.Failure(RequestStage.Permission, hnovel.network.FailureCode.OriginDenied),
                BrokerResult.Success(BrokerResponse(503, fixture.server.url("/").toString(), emptyMap(), byteArrayOf(), "UTF-8", 0)))) {
                response = result
                assertTrue(runCatching { failure.verification!!.complete() }.isFailure)
                assertEquals(StorageResult.Value("authenticated"), session.read(status))
            }
            cancel = true
            assertTrue(runCatching { failure.verification!!.complete() }.exceptionOrNull() is CancellationException)
            assertEquals(StorageResult.Value("authenticated"), session.read(status))
            cancel = false; response = null
            failure.verification!!.complete()
            assertEquals(StorageResult.Value("session"), session.read(status))
        } }
    }

    @Test fun scriptNetworkChallengeRetainsItsHostOwnedVerificationAction() = runBlocking {
        val opened = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, options, _, _ ->
            if (request.url.endsWith("/protected") && !options.interactive)
                BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                    challenge = BrowserChallengeKind.SiteVerification, verificationRequest = request)
            else {
                if (options.interactive) opened += request.url
                BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "<p>fixture</p>".toByteArray(), "UTF-8", 0,
                    kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(customize = { JsonObject(it + mapOf("browserRead" to JsonPrimitive(true),
                "loginCheckJs" to JsonPrimitive("""java.connect('/protected,{"webView":true}');result;"""))) }).use { source ->
                val error = runCatching { source.search("fixture") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.BrowserRequired, error.code)
                assertEquals(BrowserChallengeKind.SiteVerification, error.verification!!.kind)
                error.verification.complete()
                assertEquals(listOf(fixture.server.url("/protected").toString()), opened)
            }
        }
    }

    @Test fun declaredBrowserReadsAndLoginHooksAcceptDocumentsWithoutClaimingHttpSuccess() = runBlocking {
        val requests = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, _, _, _ ->
            requests += request.url
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(),
                "<li><a href='/book/one'><h2>Browser novel</h2></a></li>".toByteArray(), "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "browserRead" to JsonPrimitive(true),
                "loginCheckJs" to JsonPrimitive("if(!result.isBrowserDocument() || result.code()!==0 || result.getUrl()!==java.getUrl())throw 'wrong document';result;"),
                "ruleSearch" to buildJsonObject { put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href") }
            )) }).use { source ->
                assertEquals("Browser novel", source.search("fixture").single().title)
                assertEquals(1, requests.size)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun contentScriptsCanResolveAnHttpErrorPlaceholderWhileSelectorsKeepHttpFailures(): Unit = runBlocking {
        for (mode in listOf("@js:", "<js>", "selector")) RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/c/1" -> MockResponse().setResponseCode(404).setBody("Placeholder response")
                    "/real-chapter" -> MockResponse().setBody("<p>Resolved through source script.</p>")
                    else -> original.dispatch(request)
                }
            }
            val script = "java.ajax(${JsonPrimitive(fixture.server.url("/real-chapter").toString())})"
            val rule = when (mode) { "@js:" -> mode + script; "<js>" -> "$mode$script</js>"; else -> "article@html" }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject { put("content", rule) })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                if (mode == "selector") assertEquals(ContentError.Network, failure { source.content(book.id, chapter.id) }.code)
                else assertEquals(listOf("Resolved through source script."), source.content(book.id, chapter.id).parts.mapNotNull { it.text })
            }
        }
    }
    @Test fun invalidOptionalMetadataDoesNotDiscardBooksButRequiredFieldsStillFail(): Unit = runBlocking {
        for (field in listOf("kind", "wordCount", "lastChapter", "intro", "name", "author")) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + listOf("ruleSearch", "ruleBookInfo").associateWith { group ->
                JsonObject(raw.getValue(group).jsonObject + (field to JsonPrimitive("@js:''.match(/novel/).join('')")))
            }) }).use { source ->
                if (field in listOf("name", "author")) {
                    val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.InvalidRule, failure.code)
                    assertEquals("ruleSearch.$field", failure.field)
                } else {
                    val book = source.search("title").single()
                    assertEquals("Same title", source.information(book.id).title)
                    assertTrue(source.directory(book.id).isNotEmpty())
                }
            }
        }
    }

    @Test fun reverseTocConfigChangesFinalOrderBeforeChapterIndexesAreAssigned(): Unit = runBlocking {
        for (prefix in listOf("", "-")) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject + ("name" to JsonPrimitive(
                    "h1@text@js:book.readConfig={pageAnim:2};book.setReverseToc(true);result"))),
                "ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject + ("chapterList" to JsonPrimitive(prefix + "li")))
            )) }).use { source ->
                val book = source.search("title").single()
                val chapters = source.directory(book.id)
                assertEquals(if (prefix.isEmpty()) listOf("Two", "One", "Volume one") else listOf("Volume one", "One", "Two"), chapters.map { it.title })
                assertEquals(listOf(0, 1, 2), chapters.map { it.state.metadata.getValue("index").jsonPrimitive.int })
                val config = source.information(book.id).state.metadata.getValue("readConfig").jsonObject
                assertEquals(JsonPrimitive(true), config["reverseToc"])
                assertEquals(JsonPrimitive(2), config["pageAnim"])
            }
        }
    }

    @Test fun contentScriptsReceiveTitleAndNextLogicalChapterAcrossContinuationPages(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", """article@html<js>
                    if(title!==chapter.title)throw 'missing title';
                    if(title==='One' && nextChapterUrl!==${JsonPrimitive(fixture.server.url("/c/2").toString())})throw 'wrong next chapter';
                    if(title==='Two' && nextChapterUrl!==null)throw 'last chapter must have no successor';
                    result</js>""")
                put("nextContentUrl", "a.next@href")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapters = source.directory(book.id).filterNot { it.isVolume }
                assertTrue(source.content(book.id, chapters[0].id).parts.any { it.text == "last page" })
                assertTrue(source.content(book.id, chapters[1].id).parts.any { it.text == "second chapter" })
            }
        }
    }

    @Test fun emptyLegacyExploreRulesFallBackToSearchAndKeepReadingAvailable(): Unit = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleExplore" to JsonArray(emptyList()), "ruleReview" to JsonArray(emptyList())
            )) }).use { source ->
                val book = source.search("title").single()
                assertEquals(book.id, source.discovery(fixture.server.url("/search").toString()).single().id)
                val chapters = source.directory(book.id).filterNot { it.isVolume }
                assertTrue(chapters.isNotEmpty())
                assertTrue(source.content(book.id, chapters.first().id).parts.any { !it.text.isNullOrBlank() })
            }
        }
    }

    @Test fun cataloguePageArraysAreExpandedOnceAndKeepScriptChapterUrls() = runBlocking {
        val rendered = mutableListOf<String>()
        val browser = BrowserExecutor { session, request, _, guard, _ ->
            rendered += java.net.URI(request.url).path
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture(browser).use { fixture ->
            val normal = fixture.server.dispatcher
            val pages = java.util.Collections.synchronizedList(mutableListOf<String>())
            var expansions = 0
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleToc.nextTocUrl") expansions++
            }
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.path.orEmpty()
                    if (!path.startsWith("/toc/")) return normal.dispatch(request)
                    pages += path
                    val entries = when (path) {
                        "/toc/1" -> """{"sort":1,"chapterName":"One"}"""
                        "/toc/2" -> """{"sort":2,"chapterName":"Two words"}"""
                        "/toc/3" -> """{"sort":3,"chapterName":"Three"}"""
                        else -> ""
                    }
                    return okhttp3.mockwebserver.MockResponse().setBody("""{"chapters":[$entries]}""")
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleToc" to buildJsonObject {
                    put("chapterList", "$.chapters"); put("chapterName", "$.chapterName")
                    put("chapterUrl", "<js>baseUrl.replace(/toc\\/\\d+/, 'c/{{$.sort}}')+'?cName={{$.chapterName}}'+\",{'webView':true}\"</js>\n")
                    put("nextTocUrl", "<js>[baseUrl,'/toc/2','/toc/3','/toc/4']</js>\n")
                },
                "ruleContent" to buildJsonObject { put("content", "article@html") }
            )) }).use { source ->
                val id = fixture.server.url("/book/one").toString()
                val chapters = source.directory(id)
                assertEquals(listOf("One", "Two words", "Three"), chapters.map { it.title })
                assertEquals(chapters.mapIndexed { index, chapter -> fixture.server.url("/c/${index + 1}").toString() +
                    "?cName=${chapter.title},{'webView':true}" }, chapters.map { it.id })
                assertEquals(listOf("/toc/1", "/toc/2", "/toc/3", "/toc/4"), pages.sorted())
                assertEquals(1, expansions)
                source.content(id, chapters.first().id)
                assertEquals(listOf("/c/1"), rendered)
            }
        }
    }

    @Test fun namelessRowsAndInvalidOptionalCoversDoNotDiscardSearchResults() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setBody("<li><a>Advertisement</a></li><li><a href='/book/one'><h2>Novel</h2></a><img src='javascript:void(0)'></li>")
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to buildJsonObject {
                put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
                put("author", "@js:if(!book.name)throw 'nameless row';'Author'")
                put("coverUrl", "img@src")
            })) }).use { source ->
                val book = source.search("novel").single()
                assertEquals("Novel", book.title)
                assertEquals("Author", book.author)
                assertEquals("", book.coverUrl)
                assertEquals(fixture.server.url("/book/one").toString(), book.id)
            }
        }
    }

    @Test fun cookieRefreshChallengeRetriesWithTheCapturedCookie() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            var challenge = true
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path?.startsWith("/search") == true && challenge) {
                        challenge = false
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                            .setHeader("Set-Cookie", "_wa_=challenge; Path=/; Max-Age=30")
                            .setBody("<meta http-equiv=refresh content=0>")
                    }
                    return normal.dispatch(request)
                }
            }
            fixture.source().use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun exploreRuleWithoutBookListReusesSearchListSelectors() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw +
                ("ruleExplore" to buildJsonObject { put("author", "b@text") }))
            }).use { source ->
                assertEquals("Same title", source.discovery(fixture.server.url("/search").toString()).single().title)
            }
        }
    }

    @Test fun ruleBrowserCallsInheritHeadersAndRefreshThemAfterVerification() = runBlocking {
        for (inline in listOf(false, true)) for (dynamic in listOf(false, true)) {
            val browserPaths = mutableListOf<String>()
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            val generation = java.util.concurrent.atomic.AtomicReference("before")
            val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val missing = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val browser = BrowserExecutor { session, request, _, guard, _ ->
                browserPaths += java.net.URI(request.url).path
                val response = session.execute(request.copy(browser = null), guard)
                if (request.url.endsWith("/await")) generation.set("after")
                response
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        if (request.path == "/token") return okhttp3.mockwebserver.MockResponse()
                            .setBody("${generation.get()}-${tokens.incrementAndGet()}")
                        seen += request.path!!
                        val token = if (dynamic) "${generation.get()}-${tokens.get()}" else "static"
                        val explicit = inline && request.path in listOf("/start", "/rendered", "/await")
                        val agent = if (explicit) "inline-agent" else "source-agent"
                        val accepted = request.getHeader("Authorization") == token && request.getHeader("User-Agent") == agent &&
                            request.getHeader("Cookie") == "source=login" && (!explicit || request.getHeader("X-Inline") == "kept")
                        if (!accepted) missing += request.path!!
                        return okhttp3.mockwebserver.MockResponse().setBody(if (accepted) "accepted" else "missing header")
                    }
                }
                val url = fixture.server.url("/").toString()
                val options = if (inline) """, {"headers":{"user-agent":"inline-agent","X-Inline":"kept"}}""" else ""
                fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                    "header" to JsonPrimitive(if (dynamic)
                        "@js:JSON.stringify({Authorization:java.ajax('/token'),'User-Agent':'source-agent',Cookie:'source=login'})"
                        else """{"Authorization":"static","User-Agent":"source-agent","Cookie":"source=login"}"""),
                    "loginUrl" to JsonPrimitive("""
                        function login(){
                            if(java.webView(null,'${url}view','')!=='accepted')throw 'view header';
                            if(java.webViewGetSource(null,'${url}source','','')!=='accepted')throw 'source header';
                            if(java.webViewGetOverrideUrl(null,'${url}override','','')!=='accepted')throw 'override header';
                            if(java.getVerificationCode('${url}captcha')!=='accepted')throw 'captcha header';
                            java.startBrowser(${JsonPrimitive("/start$options")},'verify');
                            if(java.startBrowserAwait(${JsonPrimitive("/rendered$options")},'verify',false).body()!=='accepted')throw 'rendered header';
                            if(java.startBrowserAwait(${JsonPrimitive("/await$options")},'verify').body()!=='accepted')throw 'refetch header';
                        }
                    """.trimIndent())
                )) }).use { source -> source.login(emptyMap()) }
                assertEquals(listOf("/view", "/source", "/override", "/captcha", "/start", "/rendered", "/await"), browserPaths)
                assertEquals(browserPaths + "/await", seen.toList())
                assertTrue(missing.toString(), missing.isEmpty())
                assertEquals(if (dynamic) 8 else 0, tokens.get())
            }
        }
    }

    @Test fun directBrowserLoginUsesEvaluatedSourceHeadersOnItsFirstRequest() = runBlocking {
        for (dynamic in listOf(false, true)) {
            var navigations = 0
            val browser = BrowserExecutor { session, request, options, guard, _ ->
                assertTrue(options.interactive)
                navigations++
                session.execute(request.copy(browser = null), guard)
            }
            RuleSourceFixture(browser).use { fixture ->
                fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        okhttp3.mockwebserver.MockResponse().setResponseCode(
                            if (request.getHeader("User-Agent") == "source-agent" &&
                                request.getHeader("Authorization") == "Bearer alice" &&
                                request.getHeader("Cookie") == "source=login") 200 else 401)
                            .setHeader("Content-Type", "text/html").setBody("<title>Login</title>")
                }
                val header = """{"User-Agent":"source-agent","Authorization":"Bearer alice","Cookie":"source=login"}"""
                fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                    "loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()),
                    "loginUi" to JsonPrimitive("""[{"name":"user"}]"""),
                    "header" to JsonPrimitive(if (dynamic) """@js:JSON.stringify({
                        'User-Agent':'source-agent',Authorization:'Bearer '+source.getLoginInfoMap().get('user'),Cookie:'source=login'})""" else header)
                )) }).use { source ->
                    source.login(mapOf("user" to "alice"))
                    assertEquals(1, navigations)
                    assertEquals(1, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun nestedRuleRequestsInheritStaticAndScriptSourceHeaders() = runBlocking {
        for (dynamic in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    seen.add(request.path!!.substringBefore('?'))
                    if (request.path == "/explicit") return okhttp3.mockwebserver.MockResponse().setResponseCode(
                        if (request.getHeader("Authorization") == null && request.getHeader("X-Api-Key") == null &&
                            request.getHeader("User-Agent") == "explicit-agent") 200 else 401)
                    if (request.getHeader("Authorization") != "Bearer source" ||
                        request.getHeader("X-Api-Key") != "source-key" ||
                        request.getHeader("User-Agent") != if (request.path == "/override") "request-agent" else "source-agent")
                        return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    return if (request.path!!.substringBefore('?') in setOf("/search", "/retry"))
                        okhttp3.mockwebserver.MockResponse().setBody("<li><a href='/book/one'><h2>Same title</h2></a></li>")
                    else okhttp3.mockwebserver.MockResponse().setBody("accepted")
                }
            }
            val header = """{"Authorization":"Bearer source","X-Api-Key":"source-key","User-Agent":"source-agent"}"""
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive(if (dynamic) "@js:JSON.stringify($header)" else header),
                "loginCheckJs" to JsonPrimitive("""
                    if (java.ajax('/ajax') !== 'accepted') throw 'ajax headers';
                    if (java.ajaxAll(['/batch-a','/batch-b']).some(function(r){return r.code()!==200})) throw 'batch headers';
                    if (java.connect('/override,{"headers":{"user-agent":"request-agent"}}').code()!==200) throw 'override headers';
                    if (java.connect('/explicit','{"User-Agent":"explicit-agent"}').code()!==200) throw 'explicit headers';
                    if (java.connect('/null',null).code()!==200) throw 'null headers';
                    java.connect('/retry')
                """.trimIndent()),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
                    ("name" to JsonPrimitive("h2@text@js:if(java.ajax('/row')!=='accepted')throw 'row headers';result")))
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(setOf("/search", "/ajax", "/batch-a", "/batch-b", "/override", "/explicit", "/null", "/retry", "/row"), seen.toSet())
                assertEquals(9, seen.size)
            }
        }
    }

    @Test fun headerScriptCanFetchItsValueWithoutRecursivelyEvaluatingItself() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == "/token") okhttp3.mockwebserver.MockResponse().setBody("source-token").also { tokens.incrementAndGet() }
                    else if (request.getHeader("Authorization") == "source-token") normal.dispatch(request)
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("@js:JSON.stringify({Authorization:java.ajax('/token')})"),
                "loginCheckJs" to JsonPrimitive("java.connect(result.url())")
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals("Only the search and its explicit retry need headers", 2, tokens.get())
            }
        }
    }

    @Test fun localFieldsDoNotMultiplyHeaderRequestsAcrossReadingStages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == "/token") okhttp3.mockwebserver.MockResponse().setBody("token-${tokens.incrementAndGet()}")
                    else if (request.getHeader("Authorization") == "token-${tokens.get()}") normal.dispatch(request)
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            }
            fixture.source(customize = { raw -> JsonObject(raw +
                ("header" to JsonPrimitive("@js:JSON.stringify({Authorization:java.ajax('/token'),'X-Source':'A'})")))
            }).use { source ->
                val book = source.search("title").single()
                assertEquals(1, tokens.get())
                val chapters = source.directory(book.id)
                source.content(book.id, chapters[1].id)
                source.image(book.id, fixture.server.url("/cover.png").toString(), true)
                assertEquals("Each business request evaluates its header exactly once", fixture.documents.get(), tokens.get())
                val before = tokens.get()
                source.search("title", page = 2)
                assertEquals(before + 1, tokens.get())
            }
        }
    }

    @Test fun nestedRequestsRefreshOneTimeHeadersAndPreserveTheirCallingResult() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val tokens = java.util.concurrent.atomic.AtomicInteger()
            val requests = java.util.concurrent.atomic.AtomicInteger()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    if (request.path == "/token") return okhttp3.mockwebserver.MockResponse().setBody("token-${tokens.incrementAndGet()}")
                    if (request.path == "/explicit") return okhttp3.mockwebserver.MockResponse().setBody("explicit")
                    val expected = requests.incrementAndGet()
                    if (request.getHeader("Authorization") != "token-$expected") return okhttp3.mockwebserver.MockResponse().setResponseCode(401)
                    return okhttp3.mockwebserver.MockResponse().setBody(when (request.path) {
                        "/lookup" -> "/search"
                        "/search" -> "<li><a href='/book/one'><h2>Same title</h2></a></li>"
                        else -> "accepted"
                    })
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("@js:var local='header';var result=java.ajax('/token');JSON.stringify({Authorization:result})"),
                "searchUrl" to JsonPrimitive("@js:java.ajax('/lookup')"),
                "loginCheckJs" to JsonPrimitive("java.connect(result.url())"),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("name" to JsonPrimitive("""
                    h2@text@js:var local='caller';if(java.ajax('/nested')!=='accepted')throw 'nested';
                    if(local!=='caller')throw 'header overwrote caller';
                    if(java.connect('/explicit','{}').body()!=='explicit')throw 'explicit';result
                """.trimIndent())))
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                assertEquals(4, requests.get())
                assertEquals(4, tokens.get())
            }
        }
    }

    @Test fun redirectsToNextChapterNeverBecomeCurrentChapterContent() = runBlocking {
        for (firstPage in listOf(false, true)) RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val book = source.search("title").single()
            val chapters = source.directory(book.id)
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse =
                    if (request.path == if (firstPage) "/c/1" else "/c/1b")
                        okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", "/c/2")
                    else normal.dispatch(request)
            }
            if (firstPage) assertEquals(ContentError.EmptyContent, failure { source.content(book.id, chapters[1].id) }.code)
            else {
                val content = source.content(book.id, chapters[1].id)
                assertEquals(chapters[1].id, content.id)
                assertEquals("One", content.title)
                assertNull(content.previous)
                assertEquals(chapters[2].id, content.next)
                assertEquals(listOf("A first", null, "after image", "from-search:One"), content.parts.map { it.text })
            }
            assertEquals("second chapter", source.content(book.id, chapters[2].id).parts.first().text)
            assertEquals(chapters.map { it.id to it.title }, source.directory(book.id).map { it.id to it.title })
        } }
    }

    @Test fun importedRulesReachSearchDirectoryReadingAndImagesThroughRealWorkerAndBroker() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val found = source.search("some title").single()
            assertEquals("Same title", found.title)
            val information = source.information(found.id)
            assertEquals("Same author", information.author)
            val chapters = source.directory(found.id)
            assertEquals(listOf("Volume one", "One", "Two"), chapters.map { it.title })
            assertTrue(chapters.first().isVolume)
            val content = source.content(found.id, chapters[1].id)
            assertEquals(chapters[1].id, content.id)
            assertNull(content.previous); assertEquals(chapters[2].id, content.next)
            assertEquals(listOf("A first", null, "after image", "from-search:One", "last replaced", "from-search:One"), content.parts.map { it.text })
            assertEquals(fixture.server.url("/image.png").toString(), content.parts[1].image)
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(found.id, information.coverUrl, cover = true))
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(found.id, content.parts[1].image!!, cover = false))
        } }
    }

    @Test fun sameRemoteBookAcrossSourcesRetainsSeparateVariablesAndContent() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source("A").use { a -> fixture.source("B").use { b ->
            val bookA = a.search("title").single(); val bookB = b.search("title").single()
            assertEquals(bookA.id, bookB.id); assertEquals(bookA.title, bookB.title)
            assertNotEquals(a.definition.sourceId, b.definition.sourceId)
            val chaptersA = a.directory(bookA.id); val chaptersB = b.directory(bookB.id)
            assertEquals(chaptersA[1].id, chaptersB[1].id)
            assertEquals("A first", a.content(bookA.id, chaptersA[1].id).parts.first().text)
            assertEquals("B first", b.content(bookB.id, chaptersB[1].id).parts.first().text)
            a.close()
            assertEquals(ContentError.Unavailable, failure { a.information(bookA.id) }.code)
            assertEquals("Same title", b.information(bookB.id).title)
        } } }
    }

    @Test fun directBookUrlAndMissingSearchUseExplicitCapabilities() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { JsonObject(it - "searchUrl") }).use { source ->
            assertEquals(ContentError.MissingCapability, failure { source.search("title") }.code)
            val id = fixture.server.url("/book/one").toString()
            assertEquals(id, source.information(id).id)
            assertEquals(3, source.directory(id).size)
        } }
    }

    @Test fun catalogueCyclesEndWithCollectedChaptersWhileHttpFailuresStillFail() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            fixture.cycle = true
            assertEquals(listOf("Volume one", "One", "Two"), source.directory(id).map { it.title })
            fixture.cycle = false
            assertEquals(3, source.directory(id).size)
            fixture.status = 401
            assertEquals(ContentError.Network, failure { source.information(id) }.code)
        } }
    }

    @Test fun publicHttpDenialsDoNotChangeTheAccountOrRequireLogin() = runBlocking {
        for (withLogin in listOf(false, true)) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> if (withLogin) JsonObject(raw +
                ("loginUrl" to JsonPrimitive(fixture.server.url("/login").toString()))) else raw
            }).use { source ->
                val definition = source.definition
                val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                    listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
                for (status in listOf(401, 403)) {
                    fixture.status = status
                    assertEquals(ContentError.Network, failure { source.search("title") }.code)
                    assertEquals("session", (session.read(StorageRequest(StorageArea.Account, "login/status")) as StorageResult.Value).value)
                }
                fixture.status = 200
                assertEquals("Same title", source.search("title").single().title)
            }
        }
    }

    @Test fun responseHooksAndSharedCatalogFormattingUsePinnedContracts() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw + mapOf(
            "loginCheckJs" to JsonPrimitive("@js:if (result.code()!==200 || !result.getBody()) throw 'bad response'; result"),
            "coverDecodeJs" to JsonPrimitive("@js:result.reverse()"),
            "ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject + ("formatJs" to JsonPrimitive("@js:++gInt; index+':'+gInt+':'+title")))
        )) }).use { source ->
            val book = source.search("title").single()
            val chapters = source.directory(book.id)
            assertEquals(listOf("1:1:Volume one", "2:2:One", "3:3:Two"), chapters.map { it.title })
            assertEquals("2:2:One", source.content(book.id, chapters[1].id).title)
            assertArrayEquals(byteArrayOf(3, 2, 1), source.image(book.id, fixture.server.url("/cover.png").toString(), true))
        } }
    }

    @Test fun failedHooksAndEmptyContentAreFailuresAndCannotReplaceStoredCatalog() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("loginCheckJs" to JsonPrimitive("false"))) }).use { source ->
                assertEquals(ContentError.InvalidRule, failure { source.search("title") }.code)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject { put("content", "missing@html") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(ContentError.EmptyContent, failure { source.content(book.id, chapter.id) }.code)
                assertEquals(3, source.directory(book.id).size)
            }
        }
    }

    @Test fun linksNormalizeSchemesAndRetainRequestOptions() {
        assertEquals("https://example.test/chapter, {\"method\":\"POST\"}",
            sourceLink("HTTPS://example.test/book, {\"headers\":{}}", "chapter, {\"method\":\"POST\"}"))
        assertThrows(SourceContentException::class.java) { sourceLink("https://example.test/", "javascript:alert(1)") }
    }

    @Test fun matchingBookUrlPatternParsesSearchResponseAsInformationWithoutRefetching() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { JsonObject(it + mapOf(
            "searchUrl" to JsonPrimitive("/book/one"), "bookUrlPattern" to JsonPrimitive("http://.*/book/one")
        )) }).use { source ->
            val book = source.search("title").single()
            assertEquals("Same title", book.title)
            assertEquals(fixture.server.url("/book/one").toString(), book.id)
            assertEquals(1, fixture.documents.get())
        } }
    }

    @Test fun informationInitializationMutatesTheSameBookUsedByLaterFields() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source(customize = { raw -> JsonObject(raw +
            ("ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject +
                ("init" to JsonPrimitive("@js:book.setName('From initialization');result")))))
        }).use { source ->
            val book = source.search("title").single()
            assertEquals("From initialization", source.information(book.id).title)
        } }
    }

    @Test fun backgroundUpdateMarkerChangesOnlyWhenSourceContentChanges() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            val first = source.information(id)
            assertEquals(first.observedUpdate, source.information(id).observedUpdate)
            fixture.extraChapter = true
            val updated = source.information(id)
            assertTrue(updated.observedUpdate > first.observedUpdate)
            assertEquals(updated.observedUpdate, source.information(id).observedUpdate)
        } }
    }

    @Test fun informationSuppliesItsCompletedDirectoryOnceWithoutDisablingExplicitRefresh() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val id = fixture.server.url("/book/one").toString()
            source.information(id)
            val fetched = fixture.server.requestCount
            val original = source.directory(id)
            assertEquals(fetched, fixture.server.requestCount)
            fixture.extraChapter = true
            assertEquals(original.size + 1, source.directory(id).size)
            assertTrue(fixture.server.requestCount > fetched)
        } }
    }

    @Test fun repeatedCatalogueRowsKeepTheLastOccurrenceWithoutLosingReadableChapters() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            fixture.duplicateToc = true
            val chapters = source.directory(fixture.server.url("/book/one").toString())
            assertEquals(listOf("Volume one", "One"), chapters.map { it.title })
            assertEquals(fixture.server.url("/c/1").toString(), chapters.last().id)
        } }
    }

    @Test fun decodedImagesUseCompactByteTransport() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            fixture.imageBytes = ByteArray(16000) { (it % 256).toByte() }
            val id = fixture.server.url("/book/one").toString()
            assertArrayEquals(fixture.imageBytes.reversedArray(), source.image(id, fixture.server.url("/image.png").toString(), false))
        } }
    }

    @Test fun revocationAndCancellationDiscardLateRuleWrites() = runBlocking {
        for (cancel in listOf(false, true)) RuleSourceFixture().use { fixture -> fixture.source(customize = { raw ->
            JsonObject(raw + ("ruleContent" to JsonObject(raw.getValue("ruleContent").jsonObject +
                ("title" to JsonPrimitive("@js:chapter.putVariable('chapterKey','pending');'Pending title'")))))
        }).use { source ->
            val id = source.search("title").single().id
            source.directory(id)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task -> if (task is ExecutionTask.ContentMarkup) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            } }
            val operation = async { runCatching { source.content(id, fixture.server.url("/c/1").toString()) } }
            withTimeout(5000) { entered.await() }
            if (cancel) operation.cancel() else source.close()
            release.complete(Unit)
            if (cancel) operation.join() else assertEquals(ContentError.Unavailable, (operation.await().exceptionOrNull() as SourceContentException).code)
            val definition = source.definition
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val saved = session.read(StorageRequest(StorageArea.BookState, "content/book/" + digest(id))) as StorageResult.Value
            val record = Json.decodeFromString(BookRecord.serializer(), saved.value!!)
            assertEquals("One", record.chapters[1].title)
            assertEquals("One", record.chapters[1].state.variables["chapterKey"])
        } }
    }

    private suspend fun failure(block: suspend () -> Any): SourceContentException = try {
        block(); throw AssertionError("Expected an explicit content failure")
    } catch (failure: SourceContentException) { failure }
}
