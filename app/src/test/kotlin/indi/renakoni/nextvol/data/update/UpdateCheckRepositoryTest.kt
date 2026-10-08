package indi.renakoni.nextvol.data.update

import android.app.Application
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UpdateCheckRepositoryTest {
    private val preferences = mutableMapOf<String, String>()
    private val dao = mockk<UserDataDao>()
    private val release = GithubParser.GithubRelease(
        BuildConfig.VERSION_CODE + 1, "fixture", "notes", "https://example.invalid/update.apk",
    )

    @Before fun prepare() {
        coEvery { dao.get(any()) } answers { preferences[firstArg<String>()] }
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "false"
        mockkObject(GithubParser)
        every { GithubParser.parser(any()) } returns release
    }

    @After fun finish() {
        unmockkObject(GithubParser)
        coVerify(exactly = 0) { dao.insert(any(), any(), any(), any()) }
    }

    private suspend fun check(phaseId: Int): Pair<UpdateCheckRepository, UpdatePhase> {
        val repository = UpdateCheckRepository(RuntimeEnvironment.getApplication(), UserDataRepository(dao))
        repository.check()
        val phase = withTimeout(10_000) { repository.updatePhase.first { it.messageId == phaseId } }
        return repository to phase
    }

    @Test fun missingImportedAndInvalidPreferencesAllUseNextVolStableReleases() = runBlocking {
        for ((platform, channel) in listOf(null to null, "GitHub" to "Release",
            "LnrAPI" to "Development", "LnrAPI" to "CI", "old-platform" to "old-channel")) {
            preferences.clear()
            platform?.let { preferences[UserDataPath.Settings.App.DistributionPlatform.path] = it }
            channel?.let { preferences[UserDataPath.Settings.App.UpdateChannel.path] = it }
            val (repository, phase) = check(R.string.update_phase_available)
            assertSame(release, repository.release)
            assertEquals(listOf("fixture"), phase.arguments.drop(1))
            assertTrue(withTimeout(10_000) { repository.availableFlow.first { it } })
        }
        verify(exactly = 5) { GithubParser.parser(any()) }
        coVerify(exactly = 0) { dao.get(UserDataPath.Settings.App.DistributionPlatform.path) }
        coVerify(exactly = 0) { dao.get(UserDataPath.Settings.App.UpdateChannel.path) }
    }

    @Test fun repositoryWithNoReleasesReportsAnEmptyChannel() = runBlocking {
        every { GithubParser.parser(any()) } returns null
        val (repository, _) = check(R.string.update_phase_no_release)
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
    }

    @Test fun failuresAreVisibleAndDoNotOfferAnUpdate() = runBlocking {
        every { GithubParser.parser(any()) } throws IllegalStateException("fixture failure")
        val (repository, phase) = check(R.string.update_phase_check_failed)
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
        assertEquals(listOf("IllegalStateException", "fixture failure"), phase.arguments.drop(1))
    }

    @Test fun aFailedRecheckClearsThePreviousRelease() = runBlocking {
        val (repository, _) = check(R.string.update_phase_available)
        withTimeout(10_000) { repository.availableFlow.first { it } }
        every { GithubParser.parser(any()) } throws IllegalStateException("offline")
        withTimeout(10_000) {
            do {
                repository.check()
                delay(10)
            } while (repository.updatePhase.first().messageId != R.string.update_phase_check_failed)
        }
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
    }

    @Test fun equalAndOlderReleasesRemainUpToDate() = runBlocking {
        for (version in listOf(BuildConfig.VERSION_CODE, BuildConfig.VERSION_CODE - 1)) {
            val current = release.copy(version = version)
            every { GithubParser.parser(any()) } returns current
            val (repository, _) = check(R.string.update_phase_current)
            assertSame(current, repository.release)
            assertFalse(repository.availableFlow.first())
        }
    }
}
