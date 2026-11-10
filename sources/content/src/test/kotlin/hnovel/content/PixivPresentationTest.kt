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
    private val titles = listOf("R18 推荐", "R18 今日榜", "R18 周榜", "R18 原创热门 · 男性",
        "R18 原创热门 · 女性", "常规推荐", "常规今日榜", "常规原创热门")
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

    @Test fun configurationAndLocalListsAreOnlyInSourceSettings() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture,
                "settings.SHOW_SETTINGS=true;settings.SHOW_DISCOVER=true;settings.SHOW_FURRY=true;") }.use { source ->
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    val names = form.fields.map { it.name.substringAfterLast(' ') }
                    assertEquals("updateSource()", form.fields.single { it.name.endsWith("更新书源") }.action)
                    assertTrue(names.containsAll(listOf("整合系列", "快速模式", "搜索作者", "繁简通搜", "显示描述", "显示插图",
                        "成人其他榜单", "常规原创热门", "屏蔽管理")))
                    assertTrue(names.none { it in listOf("章节名称", "收藏本章", "刷新本章", "发送评论",
                        "删除评论", "显示评论", "调试模式", "备份恢复", "反馈问题", "显示投票", "兽人小说", "兽人作者",
                        "当前发现", "书源相关入口") })
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun settingsAreSectionedAndAccountAvailabilityComesFromLocalSession() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture) }.use { source ->
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    assertEquals(listOf("阅读与搜索", "发现页设置"), form.fields.mapNotNull { it.section }.distinct())
                    assertEquals("阅读与搜索", form.fields.single { it.name.endsWith("显示描述") }.section)
                    assertEquals("发现页设置", form.fields.single { it.name == "成人原创分类" }.section)
                    val discoverySettings = form.fields.filter { it.section == "发现页设置" }
                    assertEquals("R18 推荐", discoverySettings.first().name)
                    assertEquals(titles, discoverySettings.filter { it.checked == true }.map { it.name })
                    assertTrue(discoverySettings.filter { it.action?.contains("SHOW_BOOKMARKS_") == true }
                        .all { it.enabled && it.checked == false })
                    assertTrue(form.fields.single { it.action == "startPixivSettings()" }.enabled)
                    assertTrue(form.fields.none { it.action?.contains("SHOW_SETTINGS") == true || it.action?.contains("SHOW_DISCOVER") == true })
                    assertTrue(form.fields.none { it.name == "分类设置" })
                    val fast = form.fields.single { it.action == "editSettings('FAST')" }
                    assertEquals(false, fast.checked)
                    panel.login(form.values, fast.id, form.id)
                    val updated = panel.loginForm()
                    assertEquals(true, updated.fields.single { it.id == fast.id }.checked)
                    assertEquals(false, updated.fields.single { it.action == "editSettings('SHOW_PICTURES')" }.checked)
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun loggedOutAccountSettingsAreDisabledWithoutRequests() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) {
                Json.parseToJsonElement(original.toString().replace("https://www.pixiv.net", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            }.use { source ->
                val form = source.loginForm()
                val account = form.fields.single { it.action == "startPixivSettings()" }
                assertFalse(account.enabled)
                assertTrue(account.description.orEmpty().contains("登录后"))
                assertTrue(runCatching { source.login(form.values, account.id, form.id) }.isFailure)
                val bookmarks = form.fields.filter { it.action?.contains("SHOW_BOOKMARKS_") == true }
                assertEquals(2, bookmarks.size)
                bookmarks.forEach { bookmark ->
                    assertFalse(bookmark.enabled)
                    assertEquals("登录后可开启", bookmark.description)
                    assertTrue(runCatching { source.login(form.values, bookmark.id, form.id) }.isFailure)
                }
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun homepageIsEightUniqueListsWithoutFetchingCategoriesOrLegacyRecommendations() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture, """
                settings.SHOW_FURRY=true;
                delete settings.DISCOVERY_DEFAULTS_VERSION;
                settings.SHOW_ADULT=true;settings.SHOW_GENERAL=true;
                settings.SHOW_NEW_ADULT=true;settings.SHOW_RANK_ADULT=true;
            """.trimIndent()) }.use { source ->
                val catalog = source.openDiscovery("homepage").catalog(homepage = true)
                val feed = RuleDiscoveryClassifier.feed(catalog)
                assertEquals(titles, feed.map { it.title })
                assertEquals(8, feed.map { it.url }.distinct().size)
                assertEquals(listOf(
                    "top/novel?mode=r18&lang=zh",
                    "ranking/novel?mode=daily_r18&content=novel&p={{page}}",
                    "ranking/novel?mode=weekly_r18&content=novel&p={{page}}",
                    "genre/novel/male?mode=r18&lang=zh",
                    "genre/novel/female?mode=r18&lang=zh",
                    "top/novel?mode=all&lang=zh",
                    "ranking/novel?mode=daily&content=novel&p={{page}}",
                    "genre/novel/all?mode=safe&lang=zh"), feed.map { it.url.substringAfter("/ajax/") })
                assertTrue(feed.none { it.url.contains("/bookmarks") || it.url.contains("/profile/all") })
                assertEquals(8, catalog.rows.count { it.type == "url" })
                assertEquals(0, catalog.rows.count { it.url.contains("/novels/bookmarks?") })
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun categorySwitchesControlHomepageWithoutReintroducingHiddenLists() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture, """
                Object.keys(getDefaultDiscoverSettings()).forEach(key => settings[key]=false);
                settings.SHOW_RANK_ADULT=true;
            """.trimIndent()) }.use { source ->
                val discovery = source.openDiscovery("categories-disabled")
                val catalog = discovery.catalog(homepage = true)
                assertTrue(RuleDiscoveryClassifier.feed(catalog).isEmpty())
                assertEquals(3, catalog.rows.count { it.url.isNotBlank() })
                assertTrue(catalog.rows.none { it.url.contains("mode=daily_r18") || it.url.contains("mode=weekly_r18") })
                source.openLoginSession().use { panel ->
                    val form = panel.loginForm()
                    val ranks = form.fields.single { it.action == "editSettings('SHOW_RANK_ADULT')" }
                    assertTrue(panel.login(form.values, ranks.id, form.id).refreshDiscovery)
                    val empty = discovery.catalog(refresh = true, homepage = true)
                    assertTrue(empty.rows.none { it.type == "url" })
                    assertTrue(RuleDiscoveryClassifier.feed(empty).isEmpty())
                    val updated = panel.loginForm()
                    val weekly = updated.fields.single { it.action == "editSettings('SHOW_WEEKLY_ADULT')" }
                    assertTrue(panel.login(updated.values, weekly.id, updated.id).refreshDiscovery)
                    val selected = discovery.catalog(refresh = true, homepage = true)
                    assertEquals(listOf("R18 周榜"), RuleDiscoveryClassifier.feed(selected).map { it.title })
                }
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun homepageRetainsSourceDirectConnectionOptions() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) {
                definition(fixture, "settings.IPDirect=true;", localUrls = false)
            }.use { source ->
                val feed = RuleDiscoveryClassifier.feed(source.openDiscovery("direct").catalog(homepage = true))
                assertEquals(titles, feed.map { it.title })
                assertTrue(feed.all { it.url.startsWith("https://210.140.139.155/") && it.url.contains("X-csrf-token") })
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun previewsRequestOnlySelectedListsAndBookmarksRemainOnDemand() = runBlocking {
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":false,"body":{"page":{"recommend":{"ids":[]}},
                        "thumbnails":{"novel":[],"novelSeries":[]},"display_a":{"rank_a":[]},"works":[]}}""")
            }
            fixture.source(profile = EXTENSION_PROFILE) { definition(fixture,
                "settings.SHOW_BOOKMARKS_PUBLIC=true;settings.SHOW_BOOKMARKS_PRIVATE=true;") }.use { source ->
                val discovery = source.openDiscovery("requests")
                val catalog = discovery.catalog(homepage = true)
                assertEquals(0, fixture.server.requestCount)
                val feed = RuleDiscoveryClassifier.feed(catalog)
                assertEquals(titles + listOf("我的收藏", "私密收藏"), feed.map { it.title })
                for (row in feed.take(titles.size)) {
                    assertTrue(discovery.preview(row.url, emptyMap()).books.isEmpty())
                    // The shipped loginCheckJs explicitly refetches the same request.
                    repeat(2) {
                        val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals(row.url.removePrefix(fixture.server.url("/").toString().removeSuffix("/"))
                            .replace("{{page}}", "1"), request.path)
                    }
                }
                assertEquals(16, fixture.server.requestCount)
                for ((index, bookmark) in feed.takeLast(2).withIndex()) {
                    assertTrue(discovery.preview(bookmark.url, emptyMap()).books.isEmpty())
                    repeat(2) {
                        val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals("/ajax/user/12345/novels/bookmarks", request.requestUrl!!.encodedPath)
                        assertEquals(if (index == 0) "show" else "hide", request.requestUrl!!.queryParameter("rest"))
                    }
                }
                assertEquals(20, fixture.server.requestCount)
            }
        }
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

    @Test fun removedFeaturesHaveNoRemainingEntrypointsImplementationsOrEndpoints() {
        val serialized = original.toString()
        for (removed in listOf("novelCommentAdd", "novelCommentDelete", "getNovelCommentID", "splitComments",
            "novelPollAnswer", "getPollData", "pollData", "问卷调查", "SHOW_QUESTION", "SHOW_FURRY",
            "urlLinpxAuthors", "api.linpx.ink", "furrynovel", "getFurryAuthors", "updatePixivAuthors",
            "DEBUG", "debugFunc", "checkPixiv", "function profile(", "调试模式",
            "backupRestore", "backupData", "restoreData", "stripCfCookies", "备份恢复",
            "startGithubIssue", "反馈问题", "github.com/DowneyRem/PixivSource/issues",
            "SHOW_COMMENTS", "urlNovelComments", "getComment", "processComment", "formatComment",
            "/novels/comments/", "urlEmojiUrl", "urlStampUrl", "let emoji =", "发送评论", "显示评论",
            "MORE_INFORMATION", "ADD_CHAPTER_INDEX", "SHOW_UPDATE_TIME", "SHOW_ORIGINAL_LINK",
            "REPLACE_TITLE_MARKS", "AUTO_LIKE_NOVELS", "AUTO_DISLIKE_NOVELS",
            "HIDE_LIKE_NOVELS", "HIDE_WATCHED_SERIES", "function addBookShelf(", "function delBookShelf(")) {
            assertFalse(removed, serialized.contains(removed))
        }
        assertTrue(original.getValue("jsLib").jsonPrimitive.content.contains("function updateSource()"))
        assertTrue(original.getValue("jsLib").jsonPrimitive.content.contains("function urlMessageThreadLatest("))
        assertTrue(original.getValue("exploreUrl").jsonPrimitive.content
            .contains("https://cdn.jsdelivr.net/gh/DowneyRem/PixivSource@main/pixiv.json"))
        assertTrue(original.getValue("ruleToc").jsonObject.getValue("chapterList").jsonPrimitive.content
            .contains("urlSeriesNovelsTitles"))
    }
}
