package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import indi.renakoni.nextvol.data.bangumi.*
import indi.renakoni.nextvol.data.explore.SearchPage
import indi.renakoni.nextvol.data.explore.SearchEvidence
import indi.renakoni.nextvol.defaultplugin.wenku8.search.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8SearchRecallTest {
    private val api = mockk<BangumiApi> {
        coEvery { search(any(), any()) } returns BangumiSearchPage()
        coEvery { searchTag(any(), any()) } returns BangumiSearchPage()
        coEvery { related(any()) } returns emptyList()
    }
    private fun support(entries: List<Wenku8SearchEntry>) = Wenku8SearchSupport(mockk {
        coEvery { snapshot() } returns entries
        every { refreshInBackground() } just Runs
        every { generation } returns 0L
        coEvery { remember(any(), any()) } just Runs
    }, BangumiSearchExpansion(BangumiSearchApi(api, api)))

    @Test fun fastChannelIsNotBlockedBySlowRelations() = runBlocking {
        val relationStarted = CompletableDeferred<Unit>()
        val releaseRelations = CompletableDeferred<Unit>()
        coEvery { api.searchTag("春物", 0) } returns BangumiSearchPage(listOf(
            BangumiSubject(100, name = "Unmatched volume", platform = "小说")), 1)
        coEvery { api.related(100) } coAnswers {
            relationStarted.complete(Unit)
            releaseRelations.await()
            emptyList()
        }
        coEvery { api.search("春物", 0) } coAnswers {
            relationStarted.await()
            BangumiSearchPage(listOf(BangumiSubject(19441, nameCn = "果然我的青春恋爱喜剧搞错了")), 21)
        }
        val entries = listOf(Wenku8SearchEntry("1213", "果然我的青春恋爱喜剧搞错了", "渡航"))
        val found = CompletableDeferred<Unit>()
        val job = async { support(entries).bangumi.search("春物", 1, entries).onEach { batch ->
            if (batch.matches.any { it.first.id == "1213" }) found.complete(Unit)
        }.toList() }
        try {
            withTimeout(3_000) { found.await() }
            assertFalse("Optional relations should still be blocked", releaseRelations.isCompleted)
            releaseRelations.complete(Unit)
            assertTrue(job.await().last().hasMore)
        } finally { job.cancelAndJoin() }
    }

    @Test fun clearingWhileTheCatalogueLoadsPreventsTheOldSearchFromRecreatingItsCache() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Wenku8SearchCatalog(context)
        catalog.clear()
        val wrapped = mockk<Wenku8SearchCatalog> {
            every { generation } answers { catalog.generation }
            coEvery { snapshot() } coAnswers { catalog.snapshot().also { catalog.clear() } }
            every { refreshInBackground() } just Runs
            coEvery { remember(any(), any()) } coAnswers { catalog.remember(firstArg(), secondArg()) }
        }
        val info = Wenku8SearchEntry("9999", "缓存竞争测试", "Author").preview().copy(lastUpdated = java.time.LocalDateTime.now())
        val support = Wenku8SearchSupport(wrapped, BangumiSearchExpansion(BangumiSearchApi(api, api)))
        try {
            val result = Wenku8SearchSession(support, "author", "Author") { _, _ ->
                SearchPage(listOf(io.nightfish.lightnovelreader.api.web.search.SearchResult.MultipleBook("9999", info)), null)
            }.page(1).last()
            assertTrue(result.books.any { it.bookId == "9999" })
            assertFalse(java.io.File(context.cacheDir, Wenku8SearchCatalog.DIRECTORY).exists())
        } finally { catalog.clear() }
    }

    @Test fun parallelChannelsShareRelationshipReadsAndTheRequestBudget() = runBlocking {
        val subjects = (1..8).map { BangumiSubject(it, nameCn = "系列", platform = "小说") }
        val publicApi = mockk<BangumiSearchApi> {
            coEvery { search("系列", 0, any()) } returns BangumiSearchPage(subjects, 8)
        }
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        coEvery { publicApi.related(any()) } coAnswers {
            assertTrue("Duplicate relationship lookup", ids.add(firstArg()))
            delay(10)
            emptyList()
        }
        BangumiSearchExpansion(publicApi).search("系列", 1, emptyList()).toList()
        assertEquals(6, ids.size)
        coVerify(exactly = 6) { publicApi.related(any()) }
    }

    @Test fun normalizationPreservesOriginalNamesAndNumericTitles() = runBlocking {
        val catalog = Wenku8SearchCatalog(RuntimeEnvironment.getApplication())
        val entries = catalog.snapshot()
        assertEquals(4335, entries.size)
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        val title = entries.single { it.id == "2883" }.title
        assertNotNull(entries.single { it.id == "2883" }.score(Wenku8SearchText.key(" 義妹生活！ "), false))
        assertEquals(title, entries.single { it.id == "2883" }.title)
        assertEquals("re从零开始的异世界生活", Wenku8SearchText.key(" Ｒｅ：從零開始的異世界生活 "))
        assertNotNull(Wenku8SearchText.score("义妺生活", "义妹生活"))
        assertNull(Wenku8SearchText.score("春物", "果然我的青春恋爱喜剧搞错了"))
        assertNull(Wenku8SearchText.explicitId("86"))
        assertEquals("1973", Wenku8SearchText.explicitId("#1973"))
        assertEquals("1973", Wenku8SearchText.explicitId("https://www.wenku8.cc/book/1973.htm"))
        assertNull(Wenku8SearchText.explicitId("https://evil.example/book/1973.htm"))
        assertNull(Wenku8SearchText.explicitId("https://evil.example@www.wenku8.cc/book/1973.htm"))
    }

    @Test fun localResultsArriveBeforeSlowNetworkAndRetryAfterCancellationKeepsThem() = runBlocking {
        val wait = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val entry = Wenku8SearchEntry("2883", "义妹生活", "Author")
        val session = Wenku8SearchSession(support(listOf(entry)), "articlename", "義妹生活") { _, _ ->
            started.complete(Unit); wait.await(); SearchPage(emptyList(), null)
        }
        withTimeout(5_000) {
            val batch = session.page(1).first { it.books.isNotEmpty() }
            assertFalse(batch.complete)
            assertEquals("2883", batch.books.single().bookId)
            wait.complete(Unit)
            val retried = session.page(1).last()
            assertEquals(listOf("2883"), retried.books.map { it.bookId })
        }
    }

    @Test fun directoryPaginationIsStableAndRemoteFailureDoesNotDiscardCandidates() = runBlocking {
        val entries = (1..45).map { Wenku8SearchEntry("$it", "测试小说 $it", "Author") }
        val session = Wenku8SearchSession(support(entries), "articlename", "测试小说") { _, _ ->
            throw IOException("offline")
        }
        val first = session.page(1).last()
        assertEquals(20, first.books.size)
        assertEquals(2, first.nextPage)
        assertNotNull(first.failure)
        val second = session.page(2).last()
        val third = session.page(3).last()
        assertEquals(20, second.books.size)
        assertEquals(5, third.books.size)
        assertNull(third.nextPage)
        val ids = (first.books + second.books + third.books).map { it.bookId }
        assertEquals(45, ids.distinct().size)
        assertEquals(first.books.map { it.bookId }, session.page(1).last().books.map { it.bookId })
    }

    @Test fun tagsAndVolumeRelationsRecallTheOriginalWithoutAnAbbreviationDictionary() = runBlocking {
        val wrong = BangumiSubject(68344, nameCn = "恶之教典", platform = "小说")
        val volume = BangumiSubject(132824, name = "ようこそ実力至上主義の教室へ (1)", platform = "小说")
        coEvery { api.search("实教", 0) } returns BangumiSearchPage(listOf(wrong), 1)
        coEvery { api.searchTag("实教", 0) } returns BangumiSearchPage(listOf(volume), 1)
        coEvery { api.related(132824) } returns listOf(BangumiRelatedSubject(132823, 1,
            nameCn = "欢迎来到实力至上主义的教室", relation = "系列"))
        val entries = listOf(Wenku8SearchEntry("1973", "欢迎来到实力至上主义的教室", "衣笠彰梧"),
            Wenku8SearchEntry("3314", "恶之教典", "Author"))
        val session = Wenku8SearchSession(support(entries), "articlename", "实教") { _, _ -> SearchPage(emptyList(), null) }
        val result = session.page(1).last()
        assertEquals(listOf("1973", "3314"), result.books.map { it.bookId })
        assertTrue(result.scores.getValue("1973") < result.scores.getValue("3314"))
        assertEquals(SearchEvidence.Related, result.evidence["1973"])
        assertEquals(SearchEvidence.Loose, result.evidence["3314"])
    }

    @Test fun unavailableBangumiDoesNotFailAnEmptyOrLocallyMatchedSourceSearch() = runBlocking {
        coEvery { api.search(any(), any()) } throws IOException("blocked")
        coEvery { api.searchTag(any(), any()) } throws IOException("blocked")
        val entries = listOf(Wenku8SearchEntry("2883", "义妹生活", "Author"))
        for (query in listOf("義妹生活", "春物")) {
            val result = Wenku8SearchSession(support(entries), "articlename", query) { _, _ ->
                SearchPage(emptyList(), null)
            }.page(1).last()
            assertNull(result.failure)
            assertEquals(if (query == "春物") emptyList<String>() else listOf("2883"), result.books.map { it.bookId })
        }
    }

    @Test fun keywordFailureStillAllowsTagRecallAndSourceErrorsRemainVisible() = runBlocking {
        coEvery { api.search(any(), any()) } throws IOException("blocked")
        coEvery { api.searchTag(any(), any()) } returns BangumiSearchPage(listOf(
            BangumiSubject(19441, nameCn = "果然我的青春恋爱喜剧搞错了", platform = "小说")), 1)
        val entries = listOf(Wenku8SearchEntry("1213", "果然我的青春恋爱喜剧搞错了", "渡航"))
        val originalError = IOException("Wenku8 unavailable")
        for (failure in listOf(null, originalError)) {
            val result = Wenku8SearchSession(support(entries), "articlename", "春物") { _, _ ->
                SearchPage(emptyList(), null, failure = failure)
            }.page(1).last()
            assertSame(failure, result.failure)
            assertEquals("1213", result.books.single().bookId)
        }
    }

    @Test fun catalogueCacheRoundTripClearAndCorruptionNeverRemoveTheBundledBase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val catalog = Wenku8SearchCatalog(context)
        catalog.clear()
        val version = catalog.generation
        val info = Wenku8SearchEntry("9999", "缓存测试", "Author").preview().copy(lastUpdated = java.time.LocalDateTime.now())
        catalog.remember(listOf(info), version)
        assertEquals("缓存测试", Wenku8SearchCatalog(context).snapshot().single { it.id == "9999" }.title)
        val directory = java.io.File(context.cacheDir, Wenku8SearchCatalog.DIRECTORY)
        assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > 0L)
        catalog.clear()
        catalog.remember(listOf(info), version)
        assertFalse(directory.exists())
        assertEquals(4335, catalog.snapshot().size)
        directory.mkdirs()
        java.io.File(directory, "catalog.tsv.gz").writeText("interrupted download")
        assertEquals(4335, Wenku8SearchCatalog(context).snapshot().size)
        catalog.clear()
    }

    @Test fun sequelsHaveSeparateSourceIdsAndKeywordAndTagDuplicatesMerge() = runBlocking {
        val volume = BangumiSubject(13247, name = "新約 とある魔術の禁書目録 (1)", platform = "小说")
        coEvery { api.search("魔禁", 0) } returns BangumiSearchPage(listOf(
            BangumiSubject(3559, nameCn = "魔法禁书目录", platform = "小说", series = true)), 1)
        coEvery { api.searchTag("魔禁", 0) } returns BangumiSearchPage(listOf(volume), 1)
        coEvery { api.related(13247) } returns listOf(BangumiRelatedSubject(45251, 1, nameCn = "新约 魔法禁书目录", relation = "系列"))
        coEvery { api.related(45251) } returns listOf(
            BangumiRelatedSubject(3559, 1, nameCn = "魔法禁书目录", relation = "前传"),
            BangumiRelatedSubject(296165, 1, nameCn = "创约 魔法禁书目录", relation = "续集"))
        val entries = listOf(Wenku8SearchEntry("3", "魔法禁书目录", "镰池和马"),
            Wenku8SearchEntry("1297", "新约 魔法禁书目录", "镰池和马"),
            Wenku8SearchEntry("2728", "创约 魔法禁书目录", "镰池和马"))
        val session = Wenku8SearchSession(support(entries), "articlename", "魔禁") { _, _ -> SearchPage(emptyList(), null) }
        val result = session.page(1).last()
        assertEquals(setOf("3", "1297", "2728"), result.books.map { it.bookId }.toSet())
        assertEquals(3, result.books.size)
    }

    @Test fun numericInputRetainsTitleResultsAndExplicitIdAvoidsSearch() = runBlocking {
        val entries = listOf(Wenku8SearchEntry("1973", "欢迎来到实力至上主义的教室", "Author"),
            Wenku8SearchEntry("4350", "假面骑士1971-1973", "Author"))
        var calls = 0
        val search: suspend (String, Int) -> SearchPage = { _, _ -> calls++; SearchPage(emptyList(), null) }
        val result = Wenku8SearchSession(support(entries), "articlename", "1973", search).page(1).last()
        assertEquals(listOf("4350", "1973"), result.books.map { it.bookId })
        assertEquals(SearchEvidence.Loose, result.evidence["1973"])
        val direct = Wenku8SearchSession(support(entries), "articlename", "#1973", search).page(1).last()
        assertEquals("1973", direct.books.single().bookId)
        assertEquals(SearchEvidence.ExplicitId, direct.evidence["1973"])
        assertEquals(1, calls)
        coVerify(exactly = 0) { api.search(any(), any()) }
        coVerify(exactly = 0) { api.searchTag(any(), any()) }
    }

    @Test fun exactAliasEvidenceDoesNotPromoteRelatedVolumesOrLooseNameMappings() = runBlocking {
        val entries = listOf(Wenku8SearchEntry("1", "原作", "Author", listOf("Alias")),
            Wenku8SearchEntry("2", "原作续集", "Author"), Wenku8SearchEntry("3", "AB", "Author"))
        coEvery { api.search("Alias", 0) } returns BangumiSearchPage(listOf(
            BangumiSubject(10, nameCn = "原作", name = "Alias", platform = "小说"),
            BangumiSubject(30, nameCn = "A-B", name = "Alias", platform = "小说")), 2)
        coEvery { api.related(10) } returns listOf(BangumiRelatedSubject(20, 1, nameCn = "原作续集", relation = "续集"))
        val page = Wenku8SearchSession(support(entries), "articlename", "Alias") { _, _ -> SearchPage(emptyList(), null) }.page(1).last()
        assertEquals(SearchEvidence.VerifiedAlias, page.evidence["1"])
        assertEquals(SearchEvidence.Related, page.evidence["2"])
        assertEquals(SearchEvidence.Loose, page.evidence["3"])
    }

    @Test fun recordedPublicPagesRecallAbbreviationsRegardlessOfWhichChannelFinishesFirst() = runBlocking {
        // Full first pages, including unrelated manga and novels, retain their recorded order.
        val fixture = Json.parseToJsonElement(javaClass.getResource("/wenku8-search/bangumi-recall.json")!!.readText()).jsonObject
        val relations = fixture.getValue("relations").jsonObject
        coEvery { api.related(any()) } answers {
            val response = relations[firstArg<Int>().toString()] ?: throw IOException("Unrecorded optional relation")
            bangumiJson.decodeFromJsonElement<List<BangumiRelatedSubject>>(response)
        }
        val catalog = Wenku8SearchCatalog(RuntimeEnvironment.getApplication()).snapshot()
        for (sample in fixture.getValue("cases").jsonArray) {
            val case = sample.jsonObject
            val keyword = case.getValue("keyword").jsonPrimitive.content
            val expected = case.getValue("expectedIds").jsonArray.map { it.jsonPrimitive.content }
            for (keywordFirst in listOf(true, false)) {
                coEvery { api.search(keyword, 0) } coAnswers {
                    if (!keywordFirst) delay(20)
                    bangumiJson.decodeFromJsonElement<BangumiSearchPage>(case.getValue("keywordPage"))
                }
                coEvery { api.searchTag(keyword, 0) } coAnswers {
                    if (keywordFirst) delay(20)
                    bangumiJson.decodeFromJsonElement<BangumiSearchPage>(case.getValue("tagPage"))
                }
                val page = Wenku8SearchSession(support(catalog), "articlename", keyword) { _, _ ->
                    SearchPage(emptyList(), null)
                }.page(1).last()
                assertNull(page.failure)
                assertTrue("$keyword, keywordFirst=$keywordFirst: ${page.books.map { it.bookId }}",
                    page.books.map { it.bookId }.containsAll(expected))
                assertEquals(page.books.size, page.books.map { it.bookId }.distinct().size)
            }
        }
    }
}
