package indi.renakoni.nextvol.data.web.rules

import hnovel.content.*
import hnovel.network.BrowserChallengeKind
import indi.renakoni.nextvol.data.web.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceVerificationCoordinatorTest {
    private val owner = VerificationOwner(Identifier("rules", "fixture"), "revision", 0)
    private val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
        WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
    private val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> {
        every { sources } returns listings
    })
    private val verification = mockk<SourceVerification> {
        every { kind } returns BrowserChallengeKind.Cloudflare
        every { certificate } returns null
        every { origin } returns "https://first.test/"
        coEvery { complete() } returns Unit
    }
    private val failure = SourceContentException(ContentError.BrowserRequired, "searchUrl", verification = verification)

    @Test fun scriptVerificationReturnsToTheWaitingInvocation() = runTest {
        val ready = CompletableDeferred<Unit>()
        var body = ""
        var calls = 0
        coEvery { verification.complete() } coAnswers { ready.await(); body = "chapter" }
        val ui = ForegroundSourceRequest()
        val request = async(ui) { coordinator.execute(owner, "Chapter") {
            calls++
            currentCoroutineContext()[SourceScriptInteraction]!!.request(verification)
            body
        } }
        runCurrent()
        assertTrue(ui.verifying)
        assertEquals(1, calls)
        ready.complete(Unit)
        assertEquals("chapter", request.await())
        assertEquals(1, calls)
        assertFalse(ui.verifying)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun challengeAfterScriptInteractionDoesNotOpenTheGenericFallback() = runTest {
        var calls = 0
        val result = withContext(ForegroundSourceRequest()) { runCatching {
            coordinator.execute(owner, "Chapter") {
                calls++
                currentCoroutineContext()[SourceScriptInteraction]!!.request(verification)
                throw failure
            }
        } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun genericFallbackRetryCannotStartAnotherScriptInteraction() = runTest {
        var calls = 0
        val result = withContext(ForegroundSourceRequest()) { runCatching {
            coordinator.execute(owner, "Chapter") {
                if (++calls == 1) throw failure
                currentCoroutineContext()[SourceScriptInteraction]!!.request(verification)
            }
        } }
        assertEquals(ContentError.BrowserRequired, (result.exceptionOrNull() as SourceContentException).code)
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun certificateRetryCanResumeTheScriptBrowser() = runTest {
        var approved = false
        var openings = 0
        val certificateVerification = mockk<SourceVerification> {
            every { kind } returns null
            every { origin } returns null
            every { certificate } returns mockk<hnovel.network.CertificateProblem>()
            coEvery { complete() } coAnswers { approved = true }
        }
        val denied = SourceContentException(ContentError.Certificate, "loginCheckJs", verification = certificateVerification)
        coEvery { verification.complete() } coAnswers {
            if (++openings == 1) throw IllegalStateException("Broker request failed")
            assertTrue(approved)
        }
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Chapter") {
            try { currentCoroutineContext()[SourceScriptInteraction]!!.request(verification) }
            // The worker result maps the broker's certificate failure back to the content layer.
            catch (_: IllegalStateException) { throw denied }
            "chapter"
        } }
        runCurrent()
        assertFalse(request.isCompleted)
        assertEquals(1, openings)
        coordinator.approveCertificate(coordinator.prompts.value.single().id)
        assertEquals("chapter", request.await())
        assertEquals(2, openings)
        coVerify(exactly = 1) { certificateVerification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun backgroundScriptInteractionRestartsOnlyAfterUserActionAndReturnsItsResult() = runTest {
        var calls = 0
        var resumed = 0
        var body = ""
        var consumed = ""
        val actions = mutableListOf<SourceVerification>()
        suspend fun download() {
            consumed = coordinator.execute(owner, "Download") {
                val invocation = ++calls
                val action = mockk<SourceVerification> {
                    every { kind } returns null
                    every { certificate } returns null
                    every { origin } returns null
                    coEvery { complete() } coAnswers { body = "chapter-$invocation" }
                }.also(actions::add)
                currentCoroutineContext()[SourceScriptInteraction]!!.request(action)
                body
            }
        }
        withContext(BackgroundSourceRequest { { id ->
            resumed++
            withContext(BackgroundSourceRequest(id) { null }) { download() }
        } }) { runCatching { download() }.onFailure { assertTrue(it is SourceScriptInteractionRequired) } }
        val prompt = coordinator.prompts.value.single()
        assertFalse(prompt.foreground)
        assertEquals(1, calls)
        coVerify(exactly = 0) { actions.first().complete() }
        coordinator.verifyBackground(prompt.id)
        coordinator.verifyBackground(prompt.id)
        assertEquals(2, calls)
        assertEquals("chapter-2", consumed)
        assertEquals(1, resumed)
        coVerify(exactly = 0) { actions.first().complete() }
        coVerify(exactly = 1) { actions.last().complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun parallelSectionsRetryAfterOneSharedVerificationWithoutOpeningFourWindows() = runTest {
        val release = CompletableDeferred<Unit>()
        var verified = false
        coEvery { verification.complete() } coAnswers { release.await(); verified = true }
        val ui = ForegroundSourceRequest()
        val attempts = IntArray(4)
        val sections = (0..3).map { index -> async(ui) {
            coordinator.execute(owner, "Section $index") {
                attempts[index]++
                if (!verified) throw failure
                "section-$index"
            }
        } }
        runCurrent()
        assertEquals(4, coordinator.prompts.value.size)
        coVerify(exactly = 1) { verification.complete() }
        release.complete(Unit)
        assertEquals((0..3).map { "section-$it" }, sections.awaitAll())
        assertEquals(listOf(2, 2, 2, 2), attempts.toList())
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun cancellingUserActionAlsoCancelsTheResumedScriptAfterItsWindowCloses() = runTest {
        val resumed = CompletableDeferred<String>()
        val fetching = CompletableDeferred<Unit>()
        var saved = false
        suspend fun download(): String = coordinator.execute(owner, "Download") {
            currentCoroutineContext()[SourceScriptInteraction]!!.request(verification)
            fetching.complete(Unit)
            awaitCancellation()
        }
        withContext(BackgroundSourceRequest { { id -> resumed.complete(checkNotNull(id)) } }) {
            runCatching { download() }
        }
        val opening = async { coordinator.verifyBackground(coordinator.prompts.value.single().id) }
        val id = resumed.await()
        val worker = async(BackgroundSourceRequest(id) { null }) {
            runCatching { download(); saved = true }.exceptionOrNull()
        }
        fetching.await()
        opening.cancelAndJoin()
        assertEquals(ContentError.BrowserRequired, (worker.await() as SourceContentException).code)
        assertFalse(saved)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun aQueuedSectionStillReportingAChallengeFailsLocallyWithoutAnotherWindow() = runTest {
        val release = CompletableDeferred<Unit>()
        var verified = false
        coEvery { verification.complete() } coAnswers { release.await(); verified = true }
        val ui = ForegroundSourceRequest()
        val first = async(ui) { coordinator.execute(owner, "Ready") { if (!verified) throw failure; "ready" } }
        var attempts = 0
        val second = async(ui) { runCatching { coordinator.execute(owner, "Still challenged") { attempts++; throw failure } } }
        runCurrent()
        release.complete(Unit)
        assertEquals("ready", first.await())
        assertSame(failure, second.await().exceptionOrNull())
        assertEquals(2, attempts)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun aCompletedWindowDoesNotSkipOtherOriginsOrUnknownOrigins() = runTest {
        for (secondOrigin in listOf("https://second.test/", null)) {
            clearMocks(verification, answers = false)
            every { verification.origin } returns if (secondOrigin == null) null else "https://first.test/"
            val release = CompletableDeferred<Unit>()
            var firstReady = false
            var secondReady = false
            coEvery { verification.complete() } coAnswers { release.await(); firstReady = true }
            val secondVerification = mockk<SourceVerification> {
                every { kind } returns BrowserChallengeKind.Cloudflare
                every { certificate } returns null
                every { origin } returns secondOrigin
                coEvery { complete() } coAnswers { secondReady = true }
            }
            val secondFailure = SourceContentException(ContentError.BrowserRequired, "exploreUrl", verification = secondVerification)
            val ui = ForegroundSourceRequest()
            val first = async(ui) { coordinator.execute(owner, "First") { if (!firstReady) throw failure; "first" } }
            val second = async(ui) { runCatching { coordinator.execute(owner, "Second") {
                if (!secondReady) throw secondFailure
                "second"
            } } }
            runCurrent()
            release.complete(Unit)
            assertEquals("first", first.await())
            assertEquals("second", second.await().getOrThrow())
            coVerify(exactly = 1) { verification.complete(); secondVerification.complete() }
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun aCompletedWindowDoesNotSkipOtherAccountsOrChallengeKinds() = runTest {
        val original = listings.value
        for (otherAccount in listOf(false, true)) {
            clearMocks(verification, answers = false)
            val secondOwner = if (otherAccount) owner.copy(source = Identifier("rules", "other")) else owner
            listings.value = original + if (otherAccount) listOf(SourceListing(SourceMetadata(
                WebDataSourceItem(secondOwner.source, "Other", ""), emptySet(), revision = secondOwner.revision), SourceStatus.Ready)) else emptyList()
            val release = CompletableDeferred<Unit>()
            var firstReady = false
            var secondReady = false
            coEvery { verification.complete() } coAnswers { release.await(); firstReady = true }
            val secondVerification = mockk<SourceVerification> {
                every { kind } returns if (otherAccount) BrowserChallengeKind.Cloudflare else BrowserChallengeKind.Login
                every { certificate } returns null
                every { origin } returns "https://first.test/"
                coEvery { complete() } coAnswers { secondReady = true }
            }
            val secondFailure = SourceContentException(ContentError.BrowserRequired, "exploreUrl", verification = secondVerification)
            val ui = ForegroundSourceRequest()
            val first = async(ui) { coordinator.execute(owner, "First") { if (!firstReady) throw failure; "first" } }
            val second = async(ui) { coordinator.execute(secondOwner, "Second") { if (!secondReady) throw secondFailure; "second" } }
            runCurrent()
            release.complete(Unit)
            assertEquals(listOf("first", "second"), awaitAll(first, second))
            coVerify(exactly = 1) { verification.complete(); secondVerification.complete() }
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun queuedCertificatesStillRequireTheirOwnExplicitConfirmation() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        val attempts = IntArray(2)
        val ui = ForegroundSourceRequest()
        val requests = (0..1).map { index -> async(ui) { coordinator.execute(owner, "Certificate $index") {
            if (++attempts[index] == 1) throw failure
            "accepted-$index"
        } } }
        runCurrent()
        coordinator.approveCertificate(coordinator.prompts.value.single { it.confirmingCertificate }.id)
        runCurrent()
        assertEquals("accepted-0", requests[0].await())
        assertFalse(requests[1].isCompleted)
        coVerify(exactly = 1) { verification.complete() }
        coordinator.approveCertificate(coordinator.prompts.value.single().id)
        assertEquals("accepted-1", requests[1].await())
        coVerify(exactly = 2) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun foregroundAutomaticallyOpensAndRetriesOnce() = runTest {
        var calls = 0
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Fixture") {
            if (++calls == 1) throw failure
            "accepted"
        } }
        runCurrent()
        assertEquals(1, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.single().opening)
        completed.complete(Unit)
        assertEquals("accepted", request.await())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun cancelledQueuedRequestNeverOpensAndDoesNotCancelTheActiveBrowser() = runTest {
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        val first = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "First") { throw failure } } }
        val second = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Second") { throw failure } } }
        runCurrent()
        assertEquals(2, coordinator.prompts.value.size)
        second.cancelAndJoin()
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals("First", coordinator.prompts.value.single().name)
        coVerify(exactly = 1) { verification.complete() }
        completed.complete(Unit)
        assertSame(failure, first.await().exceptionOrNull())
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun backgroundReturnsImmediatelyAndOnlyUserActionOpensTheBrowser() = runTest {
        assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
        val prompt = coordinator.prompts.value.single()
        assertFalse(prompt.foreground)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.verifyBackground(prompt.id)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun downloadVerificationLetsTheResumedTaskConsumeTheOnlySuccessfulResponse() = runTest {
        var ready = false
        var calls = 0
        var resumed = 0
        var saved = ""
        coEvery { verification.complete() } coAnswers { ready = true }
        suspend fun download(): String = coordinator.execute(owner, "Download") {
            calls++
            if (!ready) throw failure
            "chapter"
        }
        val background = BackgroundSourceRequest { {
            resumed++
            withContext(BackgroundSourceRequest { null }) { saved = download() }
        } }
        withContext(background) { runCatching { saved = download() } }
        val id = coordinator.prompts.value.single().id
        coordinator.expireBackgroundNotice(id)
        assertEquals(id, coordinator.prompts.value.single().id)
        assertEquals(1, calls)
        assertEquals(0, resumed)
        coordinator.verifyBackground(id)
        coordinator.verifyBackground(id)
        assertEquals(2, calls)
        assertEquals(1, resumed)
        assertEquals("chapter", saved)
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun closingTheDownloadBrowserOnlySignalsResumeAndARepeatedChallengeStopsTheTask() = runTest {
        var calls = 0
        var resumed = 0
        var result: Throwable? = null
        suspend fun download() = coordinator.execute<Unit>(owner, "Download") { calls++; throw failure }
        withContext(BackgroundSourceRequest { {
            resumed++
            withContext(BackgroundSourceRequest { null }) { result = runCatching { download() }.exceptionOrNull() }
        } }) {
            runCatching { download() }
        }
        coordinator.verifyBackground(coordinator.prompts.value.single().id)
        assertSame(failure, result)
        assertEquals(2, calls)
        assertEquals(1, resumed)
        coVerify(exactly = 1) { verification.complete() }
        assertFalse(coordinator.prompts.value.single().opening)
    }

    @Test fun oneWindowResumesExistingAndLateDownloadsAndStaleResponsesOnlyForItsSite() = runTest {
        val release = CompletableDeferred<Unit>()
        val staleResponse = CompletableDeferred<Unit>()
        var ready = false
        val calls = IntArray(4)
        val saved = mutableMapOf<Int, String>()
        coEvery { verification.complete() } coAnswers { release.await(); ready = true }
        suspend fun download(index: Int): String = coordinator.execute(owner, "Chapter $index") {
            val attempt = ++calls[index]
            if (index == 3 && attempt == 1) { staleResponse.await(); throw failure }
            if (!ready) throw failure
            "chapter-$index"
        }
        suspend fun waitForVerification(index: Int) {
            withContext(BackgroundSourceRequest { {
                withContext(BackgroundSourceRequest { null }) { saved[index] = download(index) }
            } }) { runCatching { download(index) } }
        }
        waitForVerification(0)
        val oldId = coordinator.prompts.value.single().id
        val opening = async { coordinator.verifyBackground(oldId) }
        runCurrent()
        waitForVerification(1)
        var otherResumed = false
        val otherVerification = mockk<SourceVerification> {
            every { kind } returns BrowserChallengeKind.Cloudflare
            every { certificate } returns null
            every { origin } returns "https://other.test/"
        }
        withContext(BackgroundSourceRequest { { otherResumed = true } }) {
            runCatching { coordinator.execute<Unit>(owner, "Other site") {
                throw SourceContentException(ContentError.BrowserRequired, "chapter", verification = otherVerification)
            } }
        }
        val foreground = async(ForegroundSourceRequest()) { download(2) }
        val stale = async(BackgroundSourceRequest { error("The running task should consume its retry directly") }) { download(3) }
        runCurrent()
        release.complete(Unit)
        opening.await()
        staleResponse.complete(Unit)
        assertEquals("chapter-2", foreground.await())
        assertEquals("chapter-3", stale.await())
        assertEquals(mapOf(0 to "chapter-0", 1 to "chapter-1"), saved)
        assertEquals(listOf(2, 2, 2, 2), calls.toList())
        coordinator.verifyBackground(oldId)
        coVerify(exactly = 1) { verification.complete() }
        coVerify(exactly = 0) { otherVerification.complete() }
        assertFalse(otherResumed)
        assertEquals("Other site", coordinator.prompts.value.single().name)
        coordinator.dismiss(coordinator.prompts.value.single().id)
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun retiredDownloadNoticeDoesNotOpenTheBrowser() = runTest {
        withContext(BackgroundSourceRequest { null }) {
            runCatching { coordinator.execute<Unit>(owner, "Download") { throw failure } }
        }
        coordinator.verifyBackground(coordinator.prompts.value.single().id)
        coVerify(exactly = 0) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun aCancelledSharedWindowStopsQueuedRequestsUntilAnotherUserAction() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { release.await(); throw CancellationException("Browser cancelled") }
        var resumed = 0
        withContext(BackgroundSourceRequest { { resumed++ } }) {
            runCatching { coordinator.execute<Unit>(owner, "Download") { throw failure } }
        }
        val notice = coordinator.prompts.value.single().id
        val requests = (0..1).map { async(ForegroundSourceRequest()) {
            runCatching { coordinator.execute<Unit>(owner, "Foreground") { throw failure } }
        } }
        runCurrent()
        release.complete(Unit)
        requests.awaitAll().forEach { assertTrue(it.exceptionOrNull() is CancellationException) }
        assertEquals(0, resumed)
        coVerify(exactly = 1) { verification.complete() }
        coEvery { verification.complete() } returns Unit
        coordinator.verifyBackground(notice)
        assertEquals(1, resumed)
        coVerify(exactly = 2) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun expiryAtTheOpeningDeadlineDoesNotRemoveAnActiveVerification() = runTest {
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        runCatching { coordinator.execute<Unit>(owner, "Fixture") { throw failure } }
        val id = coordinator.prompts.value.single().id
        val opening = async { coordinator.verifyBackground(id) }
        runCurrent()
        coordinator.expireBackgroundNotice(id)
        assertTrue(coordinator.prompts.value.single().opening)
        completed.complete(Unit)
        opening.await()
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun accountRetirementPreventsRetryAfterTheBrowserReturns() = runTest {
        val monitor = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
        val completed = CompletableDeferred<Unit>()
        coEvery { verification.complete() } coAnswers { completed.await() }
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { calls++; throw failure } } }
        runCurrent()
        listings.value = listings.value.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
        runCurrent()
        completed.complete(Unit)
        assertEquals(ContentError.Unavailable, (request.await().exceptionOrNull() as SourceContentException).code)
        assertEquals(1, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
        monitor.cancel()
    }

    @Test fun challengeAfterVerificationDoesNotOpenAnAutomaticLoop() = runTest {
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { calls++; throw failure } } }
        runCurrent()
        assertSame(failure, request.await().exceptionOrNull())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun leavingTheUiCancelsOnlyItsWaitingVerification() = runTest {
        val ui = ForegroundSourceRequest()
        coEvery { verification.complete() } coAnswers { awaitCancellation() }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        ui.setActive(false)
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun browserCoverRetainsTheRequestButNavigationAwayCancelsIt() = runTest {
        val ui = ForegroundSourceRequest()
        coEvery { verification.complete() } coAnswers { awaitCancellation() }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        runCurrent()
        assertTrue(ui.verifying)
        ui.setActive(false, retainBrowser = true)
        runCurrent()
        assertFalse(request.isCompleted)
        ui.setActive(false)
        request.join()
        assertTrue(request.isCancelled)
        assertFalse(ui.verifying)
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun preloadExplicitlyDropsInheritedInteractionAuthority() = runTest {
        withContext(ForegroundSourceRequest(allowsInteraction = false)) {
            assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
        }
        assertFalse(coordinator.prompts.value.single().foreground)
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun inactiveUiCannotOpenVerification() = runTest {
        val ui = ForegroundSourceRequest().apply { setActive(false) }
        val request = async(ui) { coordinator.execute(owner, "Fixture") { throw failure } }
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun successfulPublicRequestNeverOpensVerification() = runTest {
        assertEquals("public catalog", withContext(ForegroundSourceRequest()) {
            coordinator.execute(owner, "Fixture") { "public catalog" }
        })
        assertTrue(coordinator.prompts.value.isEmpty())
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun foregroundCertificateWaitsForExplicitConfirmationBeforeTrustingAndRetrying() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        var calls = 0
        val request = async(ForegroundSourceRequest()) { coordinator.execute(owner, "Fixture") {
            if (++calls == 1) throw failure
            "chapter"
        } }
        runCurrent()
        val prompt = coordinator.prompts.value.single()
        assertTrue(prompt.confirmingCertificate)
        assertFalse(prompt.opening)
        assertEquals(1, calls)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.approveCertificate(prompt.id)
        assertEquals("chapter", request.await())
        assertEquals(2, calls)
        coVerify(exactly = 1) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun dismissingCertificateDoesNotTrustOrRetry() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        var calls = 0
        val request = async(ForegroundSourceRequest()) { runCatching {
            coordinator.execute(owner, "Fixture") { calls++; throw failure }
        } }
        runCurrent()
        val id = coordinator.prompts.value.single().id
        coordinator.dismiss(id)
        coordinator.approveCertificate(id)
        assertEquals(ContentError.Certificate, (request.await().exceptionOrNull() as SourceContentException).code)
        assertEquals(1, calls)
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun openingBackgroundCertificateNoticeStillRequiresTheSeparateConfirmation() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        runCatching { coordinator.execute(owner, "Fixture") { throw failure } }
        val id = coordinator.prompts.value.single().id
        coordinator.approveCertificate(id) // No dialog has been opened yet.
        val opened = async { coordinator.verifyBackground(id) }
        runCurrent()
        assertFalse(opened.isCompleted)
        assertTrue(coordinator.prompts.value.single().confirmingCertificate)
        coVerify(exactly = 0) { verification.complete() }
        coordinator.approveCertificate(id)
        opened.await()
        coVerify(exactly = 1) { verification.complete() }
    }

    @Test fun retiringAnAccountWhileItsCertificateDialogIsOpenPreventsConsent() = runTest {
        every { verification.certificate } returns mockk<hnovel.network.CertificateProblem>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
        val request = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { throw failure } } }
        runCurrent()
        val id = coordinator.prompts.value.single().id
        listings.value = listings.value.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
        runCurrent()
        coordinator.approveCertificate(id)
        assertEquals(ContentError.Unavailable, (request.await().exceptionOrNull() as SourceContentException).code)
        coVerify(exactly = 0) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }

    @Test fun replacedRevisionAndDisabledSourcesCannotOpenBackgroundTickets() = runTest {
        val original = listings.value
        for (retired in listOf(original.map { it.copy(metadata = it.metadata.copy(revision = "replaced")) },
            original.map { it.copy(status = SourceStatus.Failed) }, emptyList())) {
            listings.value = original
            assertSame(failure, runCatching { coordinator.execute(owner, "Fixture") { throw failure } }.exceptionOrNull())
            val ticket = coordinator.prompts.value.single()
            listings.value = retired
            val rejected = runCatching { coordinator.verifyBackground(ticket.id) }.exceptionOrNull() as SourceContentException
            assertEquals(ContentError.Unavailable, rejected.code)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
        coVerify(exactly = 0) { verification.complete() }
    }

    @Test fun certificateInsideVerificationBrowserRequiresConsentThenResumesTheBrowser() = runTest {
        for (foreground in listOf(true, false)) {
            clearMocks(verification, answers = false)
            val certificateVerification = mockk<SourceVerification> {
                every { kind } returns null
                every { certificate } returns mockk<hnovel.network.CertificateProblem>()
                coEvery { complete() } returns Unit
            }
            val certificateFailure = SourceContentException(ContentError.Certificate, "browser.verification", verification = certificateVerification)
            var openings = 0
            coEvery { verification.complete() } coAnswers { if (++openings == 1) throw certificateFailure }
            var requests = 0
            suspend fun request(): String = coordinator.execute(owner, "Fixture") {
                if (++requests == 1) throw failure
                "chapter"
            }
            if (!foreground) assertSame(failure, runCatching { request() }.exceptionOrNull())
            val work = async(ForegroundSourceRequest()) {
                if (foreground) request() else coordinator.verifyBackground(coordinator.prompts.value.single().id)
            }
            runCurrent()
            assertFalse(work.isCompleted)
            val prompt = coordinator.prompts.value.single()
            assertSame(certificateVerification.certificate, prompt.certificate)
            assertTrue(prompt.confirmingCertificate)
            assertFalse(prompt.opening)
            coVerify(exactly = 0) { certificateVerification.complete() }
            assertEquals(1, requests)
            coordinator.approveCertificate(prompt.id)
            work.await()
            coVerify(exactly = 1) { certificateVerification.complete() }
            assertEquals(2, openings)
            assertEquals(if (foreground) 2 else 1, requests)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun nestedCertificateDismissalOrAccountRetirementNeverTrustsOrResumes() = runTest {
        val original = listings.value
        for (retire in listOf(false, true)) {
            listings.value = original
            val certificateVerification = mockk<SourceVerification> {
                every { kind } returns null
                every { certificate } returns mockk<hnovel.network.CertificateProblem>()
                coEvery { complete() } returns Unit
            }
            coEvery { verification.complete() } throws SourceContentException(ContentError.Certificate,
                "browser.verification", verification = certificateVerification)
            var requests = 0
            val work = async(ForegroundSourceRequest()) { runCatching {
                coordinator.execute(owner, "Fixture") { requests++; throw failure }
            } }
            runCurrent()
            val id = coordinator.prompts.value.single().id
            if (retire) {
                listings.value = original.map { it.copy(metadata = it.metadata.copy(accountGeneration = 1)) }
                val monitor = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.observeRetirement() }
                runCurrent()
                monitor.cancelAndJoin()
            } else coordinator.dismiss(id)
            coordinator.approveCertificate(id)
            assertEquals(if (retire) ContentError.Unavailable else ContentError.Certificate,
                (work.await().exceptionOrNull() as SourceContentException).code)
            coVerify(exactly = 0) { certificateVerification.complete() }
            assertEquals(1, requests)
            assertTrue(coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun repeatedCertificateInsideVerificationBrowserDoesNotCreateAConsentLoop() = runTest {
        val certificateVerification = mockk<SourceVerification> {
            every { kind } returns null
            every { certificate } returns mockk<hnovel.network.CertificateProblem>()
            coEvery { complete() } returns Unit
        }
        val certificateFailure = SourceContentException(ContentError.Certificate, "browser.verification", verification = certificateVerification)
        coEvery { verification.complete() } throws certificateFailure
        val work = async(ForegroundSourceRequest()) { runCatching { coordinator.execute(owner, "Fixture") { throw failure } } }
        runCurrent()
        coordinator.approveCertificate(coordinator.prompts.value.single().id)
        assertSame(certificateFailure, work.await().exceptionOrNull())
        coVerify(exactly = 1) { certificateVerification.complete() }
        coVerify(exactly = 2) { verification.complete() }
        assertTrue(coordinator.prompts.value.isEmpty())
    }
}
