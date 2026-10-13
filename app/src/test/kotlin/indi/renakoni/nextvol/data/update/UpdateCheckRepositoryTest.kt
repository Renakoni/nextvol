package indi.renakoni.nextvol.data.update

import android.app.Application
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
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
    private val platformPath = UserDataPath.Settings.App.DistributionPlatform.path
    private val channelPath = UserDataPath.Settings.App.UpdateChannel.path
    private val preferences = mutableMapOf<String, String>()
    private val dao = mockk<UserDataDao>()
    private val parsers = arrayOf<UpdateParser>(
        GithubParser.ReleaseParser, GithubParser.DevelopmentParser, GithubParser.CIParser,
        APIParser.StableParser, APIParser.BetaParser, APIParser.UnstableParser,
    )
    private val release = APIParser.APIRelease(
        BuildConfig.VERSION_CODE + 1, "fixture", "notes", "https://example.invalid/update.apk",
    )

    @Before fun prepare() {
        coEvery { dao.get(any()) } answers { preferences[firstArg<String>()] }
        // Each test drives checks explicitly, without racing the initialization check.
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "false"
        mockkObject(*parsers)
        parsers.forEach { parser -> every { parser.parser(any()) } returns release }
    }

    @After fun finish() {
        unmockkObject(*parsers)
        coVerify(exactly = 0) { dao.insert(any(), any(), any(), any()) }
    }

    private suspend fun check(phaseId: Int = R.string.update_phase_available): Pair<UpdateCheckRepository, UpdatePhase> {
        val repository = UpdateCheckRepository(RuntimeEnvironment.getApplication(), UserDataRepository(dao), mockk(), mockk())
        repository.check()
        val phase = withTimeout(10_000) { repository.updatePhase.first { it.messageId == phaseId } }
        return repository to phase
    }

    private suspend fun assertSelection(platform: String?, channel: String?, expected: UpdateParser) {
        parsers.forEach { clearMocks(it, answers = false) }
        preferences.clear()
        platform?.let { preferences[platformPath] = it }
        channel?.let { preferences[channelPath] = it }
        val (repository, phase) = check()
        assertSame(release, repository.release)
        assertEquals(listOf("fixture"), phase.arguments.drop(1))
        assertTrue(withTimeout(10_000) { repository.availableFlow.first { it } })
        verify(exactly = 1) { expected.parser(any()) }
        parsers.filter { it !== expected }.forEach { parser -> verify(exactly = 0) { parser.parser(any()) } }
    }

    @Test fun savedPlatformAndChannelSelectTheExistingParser() = runBlocking {
        listOf(
            Triple("GitHub", "Release", GithubParser.ReleaseParser),
            Triple("GitHub", "Development", GithubParser.DevelopmentParser),
            Triple("GitHub", "CI", GithubParser.CIParser),
            Triple("LnrAPI", "Release", APIParser.StableParser),
            Triple("LnrAPI", "Development", APIParser.BetaParser),
            Triple("LnrAPI", "CI", APIParser.UnstableParser),
        ).forEach { (platform, channel, parser) -> assertSelection(platform, channel, parser) }
    }

    @Test fun onlyMissingPreferencesUseTheExistingDefaults() = runBlocking {
        assertSelection(null, null, APIParser.BetaParser)
        assertSelection("GitHub", null, GithubParser.DevelopmentParser)
        assertSelection(null, "CI", APIParser.UnstableParser)
    }

    @Test fun invalidPreferencesFailWithoutCallingAnyParserOrRewritingValues() = runBlocking {
        listOf(
            Triple("", "Release", ""),
            Triple("github", "Release", "github"),
            Triple("LnrAPI", "", ""),
            Triple("GitHub", "release", "release"),
            Triple("old-platform", "old-channel", "old-platform"),
        ).forEach { (platform, channel, invalid) ->
            preferences[platformPath] = platform
            preferences[channelPath] = channel
            val (repository, phase) = check(R.string.update_phase_check_failed)
            assertNull(repository.release)
            assertFalse(repository.availableFlow.first())
            assertEquals(listOf("NoSuchElementException", "OptionWithValue '$invalid' not found"), phase.arguments.drop(1))
            assertEquals(platform, preferences[platformPath])
            assertEquals(channel, preferences[channelPath])
        }
        parsers.forEach { parser -> verify(exactly = 0) { parser.parser(any()) } }
    }

    @Test fun parserExceptionsKeepTheExistingFailurePhase() = runBlocking {
        every { APIParser.BetaParser.parser(any()) } throws IllegalStateException("fixture failure")
        val (repository, phase) = check(R.string.update_phase_check_failed)
        assertNull(repository.release)
        assertFalse(repository.availableFlow.first())
        assertEquals(listOf("IllegalStateException", "fixture failure"), phase.arguments.drop(1))
        verify(exactly = 1) { APIParser.BetaParser.parser(any()) }
    }

    @Test fun equalAndOlderReleasesRemainUpToDate() = runBlocking {
        for (version in listOf(BuildConfig.VERSION_CODE, BuildConfig.VERSION_CODE - 1)) {
            val current = APIParser.APIRelease(version, "existing", "notes", "https://example.invalid/update.apk")
            every { APIParser.BetaParser.parser(any()) } returns current
            val (repository, phase) = check(R.string.update_phase_current)
            assertSame(current, repository.release)
            assertFalse(repository.availableFlow.first())
            assertEquals(listOf("existing"), phase.arguments.drop(1))
        }
    }
}
