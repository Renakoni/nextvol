package indi.renakoni.nextvol.data.update

import android.app.Application
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UpdateCheckRepositoryTest {
    private val preferences = mutableMapOf<String, String>()
    private val dao = mockk<UserDataDao>()
    private val downloader = mockk<UpdateArtifactDownloader>()
    private val release = Release(
        BuildConfig.VERSION_CODE + 1, "fixture", "notes", "https://example.invalid/update.apk", "a".repeat(64),
    )

    @Before fun prepare() {
        coEvery { dao.get(any()) } answers { preferences[firstArg<String>()] }
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "false"
        mockkObject(GithubParser, NextvolApiParser)
        every { GithubParser.parser(any()) } returns release
        every { NextvolApiParser.parser(any()) } returns release
    }

    @After fun finish() {
        unmockkObject(GithubParser, NextvolApiParser)
        coVerify(exactly = 0) { dao.insert(any(), any(), any(), any()) }
    }

    private suspend fun check(phaseId: Int = R.string.update_phase_available): Pair<UpdateCheckRepository, UpdatePhase> {
        val repository = UpdateCheckRepository(RuntimeEnvironment.getApplication(), UserDataRepository(dao), downloader, mockk())
        repository.check()
        val phase = withTimeout(10_000) { repository.updatePhase.first { it.messageId == phaseId } }
        return repository to phase
    }

    @Test fun manualCheckUsesTheSelectedGitHubPlatform() = runBlocking {
        preferences[UserDataPath.Settings.App.DistributionPlatform.path] = "GitHub"
        val (repository, phase) = check()
        assertSame(release, repository.release)
        assertEquals(listOf("fixture"), phase.arguments.drop(1))
        assertTrue(withTimeout(10_000) { repository.availableFlow.first { it } })
        verify(exactly = 1) { GithubParser.parser(any()) }
        verify(exactly = 0) { NextvolApiParser.parser(any()) }
    }

    @Test fun enabledAutomaticCheckUsesTheDefaultNextvolApi() = runBlocking {
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "true"
        val repository = UpdateCheckRepository(RuntimeEnvironment.getApplication(), UserDataRepository(dao), downloader, mockk())
        withTimeout(10_000) { repository.availableFlow.first { it } }
        verify(exactly = 1) { NextvolApiParser.parser(any()) }
    }

    @Test fun repositoryWithNoReleasesReportsAnEmptyChannel() = runBlocking {
        every { NextvolApiParser.parser(any()) } returns null
        val (repository, _) = check(R.string.update_phase_no_release)
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
    }

    @Test fun recheckBlocksStaleDownloadsAndClearsTheReleaseOnFailure() = runBlocking {
        val (repository, _) = check()
        withTimeout(10_000) { repository.availableFlow.first { it } }
        val checking = CompletableDeferred<Unit>()
        val finishCheck = CountDownLatch(1)
        every { NextvolApiParser.parser(any()) } answers {
            checking.complete(Unit)
            kotlin.check(finishCheck.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("offline")
        }
        try {
            withTimeout(10_000) {
                while (!checking.isCompleted) {
                    repository.check()
                    delay(10)
                }
            }
            assertNull(repository.release)
            assertFalse(repository.availableFlow.first())
            repository.downloadUpdate()
            verify(exactly = 0) { downloader.prepare() }
        } finally { finishCheck.countDown() }
        val phase = withTimeout(10_000) {
            repository.updatePhase.first { it.messageId == R.string.update_phase_check_failed }
        }
        assertEquals(listOf("IllegalStateException", "offline"), phase.arguments.drop(1))
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
    }

    @Test fun equalReleaseRemainsUpToDate() = runBlocking {
        val current = release.copy(version = BuildConfig.VERSION_CODE)
        every { NextvolApiParser.parser(any()) } returns current
        val (repository, _) = check(R.string.update_phase_current)
        assertSame(current, repository.release)
        assertFalse(repository.availableFlow.first())
    }
}
