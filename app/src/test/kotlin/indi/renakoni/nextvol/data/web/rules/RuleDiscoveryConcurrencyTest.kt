package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.*
import hnovel.network.BrowserChallengeKind
import indi.renakoni.nextvol.data.web.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDiscoveryConcurrencyTest {
    private fun definition(raw: JsonObject, urls: List<String> = listOf("/one", "/two", "/three", "/four")) = JsonObject(raw + mapOf(
        "homepageModules" to JsonPrimitive(buildJsonArray { urls.forEachIndexed { index, url -> add(buildJsonObject {
            put("key", "module-$index"); put("type", "ranking"); put("title", "Module $index"); put("url", url)
        }) } }.toString()),
        "ruleExplore" to buildJsonObject { put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href") }
    ))
    private fun html(path: String) = (1..30).joinToString("") { "<li><h2>$path $it</h2><a href='/book/$it'>Read</a></li>" }

    @Test fun automaticVerificationCannotExhaustPreviewSlotsAndManualRetryStillVerifies() = runBlocking {
        verifyAutomaticFailures(concurrent = true)
    }

    private suspend fun verifyAutomaticFailures(concurrent: Boolean) = coroutineScope {
        val owner = VerificationOwner(Identifier("rules", "fixture"), "revision", 0)
        val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
            WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
        val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> { every { sources } returns listings })
        var verified = false
        val verification = mockk<SourceVerification> {
            every { kind } returns BrowserChallengeKind.Cloudflare
            every { certificate } returns null
            every { origin } returns "https://first.test/"
            coEvery { complete() } coAnswers { awaitCancellation() }
        }
        val failure = SourceContentException(ContentError.BrowserRequired, "exploreUrl", verification = verification)
        RuleSourceFixture().use { fixture -> fixture.source { definition(it, (0..9).map { "/module-$it" }) }.use { source ->
            val catalog = source.openDiscovery("catalog").catalog(homepage = true)
            val ready = RuleListPage(listOf(RuleBook("/book/ready", "Ready")), null, null)
            val pages = (0..9).map { index -> mockk<RuleListSession> {
                coEvery { page(1) } coAnswers { if (index < 8) throw failure else ready }
            } }
            val session = mockk<RuleDiscoverySession> {
                coEvery { catalog(homepage = true) } returns catalog
                coEvery { concurrentPreviews(any(), any()) } returns if (concurrent) pages else null
                (0..9).forEach { index ->
                    coEvery { preview("/module-$index", any()) } coAnswers { if (index < 8 && !verified) throw failure else ready }
                }
            }
            val provider = RuleDiscoveryProvider(source, session, RuleRequestRecovery(coordinator, owner, "Fixture"))
            val pending = async(ForegroundSourceRequest()) { provider.feed() }
            try {
                val feed = withTimeout(3000) { requireNotNull(pending.await().get()) }
                assertTrue(feed.all { !it.previewLoading })
                assertTrue(feed.take(8).all { it.previewFailure != null })
                assertTrue(feed.drop(8).all { it.books.size == 1 && it.previewFailure == null })
                coVerify(exactly = 0) { verification.complete() }
                assertTrue(coordinator.prompts.value.isNotEmpty())
                assertTrue(coordinator.prompts.value.none { it.foreground })
                coEvery { verification.complete() } coAnswers { verified = true }
                val retried = withContext(ForegroundSourceRequest()) { requireNotNull(provider.preview(feed.first().id).get()) }
                assertNull(retried.previewFailure)
                assertEquals(1, retried.books.size)
                coVerify(exactly = 1) { verification.complete() }
            } finally { pending.cancelAndJoin() }
        } }
    }

    @Test fun aCancelledSourceReadEndsTheFeedInsteadOfWaitingForAMissingResult() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { definition(it, listOf("/one", "/two")) }.use { source ->
            val catalog = source.openDiscovery("catalog").catalog(homepage = true)
            val first = mockk<RuleListSession> { coEvery { page(1) } throws CancellationException("Source retired") }
            val second = mockk<RuleListSession> { coEvery { page(1) } coAnswers { awaitCancellation() } }
            val session = mockk<RuleDiscoverySession> {
                coEvery { catalog(homepage = true) } returns catalog
                coEvery { concurrentPreviews(any(), any()) } returns listOf(first, second)
            }
            val failed = withTimeout(2000) { runCatching { RuleDiscoveryProvider(source, session).feed() }.exceptionOrNull() }
            assertTrue(failed is CancellationException && failed !is TimeoutCancellationException)
        } }
    }

    @Test fun aCancelledBootstrapNeverStartsTheRemainingPreviews() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { definition(it, listOf("/first", "/second", "/third")) }.use { source ->
            val catalog = source.openDiscovery("catalog").catalog(homepage = true)
            val session = mockk<RuleDiscoverySession> {
                coEvery { catalog(homepage = true) } returns catalog
                coEvery { concurrentPreviews(any(), any()) } returns null
                coEvery { preview("/first", any()) } throws CancellationException("Source retired")
            }
            val failed = withTimeout(2000) { runCatching { RuleDiscoveryProvider(source, session).feed() }.exceptionOrNull() }
            assertTrue(failed is CancellationException && failed !is TimeoutCancellationException)
            coVerify(exactly = 1) { session.concurrentPreviews(any(), any()) }
            coVerify(exactly = 0) { session.preview("/second", any()); session.preview("/third", any()) }
        } }
    }

    @Test fun localFailureAndReadySectionPublishBeforeASlowScriptSourceSection() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/slow") {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    } else check(entered.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { raw -> JsonObject(definition(raw, listOf(
                "/slow", "/invalid,{\"method\":\"INVALID\"}", "/ready")) +
                ("jsLib" to JsonPrimitive("var helper=1"))) }.use { source ->
                val updates = Channel<List<DiscoverySection>>(Channel.UNLIMITED)
                val pending = launch { RuleDiscoveryProvider(source).feedUpdates().collect { updates.send(requireNotNull(it.get())) } }
                try {
                    var snapshot = withTimeout(10000) { updates.receive() }
                    withTimeout(10000) { while (snapshot.drop(1).any { it.previewLoading }) snapshot = updates.receive() }
                    assertTrue(snapshot.first().previewLoading)
                    assertNotNull(snapshot[1].previewFailure)
                    assertEquals(6, snapshot[2].books.size)
                    assertEquals(2, fixture.server.requestCount)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun coldAccountBootstrapRechecksConcurrencyBeforeLoadingRemainingSections() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/user/42/slow") {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS))
                    } else if (request.path == "/user/42/ready") check(entered.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { raw -> JsonObject(definition(raw, listOf(
                "/bootstrap", "/user/{{cache.get('account')}}/slow", "/user/{{cache.get('account')}}/ready")) +
                ("loginCheckJs" to JsonPrimitive("cache.put('account','42');result"))) }.use { source ->
                val updates = Channel<List<DiscoverySection>>(Channel.UNLIMITED)
                val pending = launch { RuleDiscoveryProvider(source).feedUpdates().collect { updates.send(requireNotNull(it.get())) } }
                try {
                    var snapshot = withTimeout(10000) { updates.receive() }
                    withTimeout(10000) { while (snapshot[2].previewLoading) snapshot = updates.receive() }
                    assertEquals(6, snapshot[0].books.size)
                    assertTrue(snapshot[1].previewLoading)
                    assertEquals(6, snapshot[2].books.size)
                    assertNull(snapshot[2].previewFailure)
                    assertEquals(3, fixture.server.requestCount)
                } finally { release.countDown(); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun parallelFailuresRemainLocalAndRetryDoesNotReloadSuccessfulModules() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val visits = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
            var failed = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    visits.computeIfAbsent(request.path!!) { AtomicInteger() }.incrementAndGet()
                    return if (request.path == "/two" && failed) MockResponse().setResponseCode(503)
                        else MockResponse().setBody(html(request.path!!))
                }
            }
            fixture.source { definition(it, listOf("https://ungranted.test/list", "/two", "/three")) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val feed = requireNotNull(provider.feed().get())
                assertEquals(DiscoveryError.PermissionDenied, feed[0].previewFailure?.error)
                assertEquals(DiscoveryError.Network, feed[1].previewFailure?.error)
                assertEquals(6, feed[2].books.size)
                assertNull(feed[2].previewFailure)
                assertNull(provider.failureField)
                assertNull(provider.permissionFailure)
                failed = false
                val retried = requireNotNull(provider.preview(feed[1].id).get())
                assertNull(retried.previewFailure)
                assertEquals(6, retried.books.size)
                assertEquals(2, visits["/two"]?.get())
                assertEquals(1, visits["/three"]?.get())
            }
        }
    }
}
