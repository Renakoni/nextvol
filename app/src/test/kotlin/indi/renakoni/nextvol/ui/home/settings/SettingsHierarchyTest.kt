package indi.renakoni.nextvol.ui.home.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.home.settings.about.AboutSettingsScreen
import indi.renakoni.nextvol.ui.home.settings.logcat.LogcatScreen
import indi.renakoni.nextvol.ui.home.settings.logcat.MutableLogcatUiState
import indi.renakoni.nextvol.ui.home.settings.updates.UpdatesSettingsScreen
import indi.renakoni.nextvol.ui.storagemanager.MutableStorageManagerUiState
import indi.renakoni.nextvol.ui.storagemanager.StorageManagerScreen
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SettingsHierarchyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var database: NextVolDatabase
    private lateinit var data: UserDataRepository
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        database = Room.inMemoryDatabaseBuilder(activity.get(), NextVolDatabase::class.java)
            .allowMainThreadQueries().build()
        data = UserDataRepository(database.userDataDao())
        WorkManagerTestInitHelper.initializeTestWorkManager(activity.get(), Configuration.Builder().build())
    }

    @After fun destroy() {
        activity.pause().stop().destroy()
        runBlocking { job.cancelAndJoin() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        database.close()
    }

    private fun show(content: @Composable () -> Unit) {
        activity.get().setContent {
            CompositionLocalProvider(LocalNavController provides rememberNavController()) {
                MaterialTheme(typography = AppTypography, content = content)
            }
        }
        compose.waitForIdle()
    }

    private fun label(id: Int) = activity.get().getString(id)
    private fun storageAction(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("storage_overview").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun entry(id: Int): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(id)))
        return compose.onNodeWithText(label(id))
    }

    @Test fun homeKeepsFourPageEntriesWithoutTheirDetailedControls() {
        val opened = mutableListOf<String>()
        val state = SettingState(data, scope)
        show {
            SettingsScreen(
                settingState = state,
                importData = { _, _ -> error("Import was not requested") },
                onClickLogcat = { opened += "logs" },
                onClickChangeSource = {}, onClickExportUserData = {},
                onClickUpdates = { opened += "updates" }, onClickAbout = { opened += "about" },
                onClickThemeSettings = {}, onClickTextFormatting = {},
                onClickReadAloud = {}, onClickStorageManager = { opened += "storage" }, onBack = {},
            )
        }
        entry(R.string.settings_storage_manager).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.settings_clear_reading_cache)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_clear_downloads)).assertDoesNotExist()
        entry(R.string.settings_app_logs).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.settings_app_logs_desc)).assertIsDisplayed()
        entry(R.string.app_updates).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.settings_updates_desc)).assertIsDisplayed()
        entry(R.string.about_settings).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.settings_about_desc)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.debug_settings)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.settings_debug_tools)).assertDoesNotExist()
        for (id in listOf(R.string.settings_app_log_level, R.string.settings_auto_check_updates,
            R.string.settings_distribution_platform, R.string.settings_get_updates,
            R.string.settings_github_repo,
            R.string.settings_open_source_licenses)) {
            compose.onNodeWithText(label(id)).assertDoesNotExist()
        }
        assertEquals(listOf("storage", "logs", "updates", "about"), opened)
    }

    @Test fun logLevelIsEditableInsideAnEmptyLogPageAndKeepsItsStoragePath() {
        val preference = data.stringUserData(UserDataPath.Settings.Data.LogLevel.path)
        show {
            val level by preference.getFlow().collectAsState("none")
            LogcatScreen(
                uiState = MutableLogcatUiState(), logFiles = listOf(""), logEntries = emptyList(),
                logLevelKey = level ?: "none", onLogLevelChange = preference::asynchronousSet,
                onClickBack = {}, onClickShareLogs = {},
                onClickClearLogs = { true }, onSelectLogFile = {},
            )
        }
        compose.onNodeWithText(label(R.string.settings_app_log_level)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.key_log_level_warning)).performClick()
        compose.waitUntil { runBlocking { preference.get() } == "warning" }
        compose.onNodeWithText(label(R.string.key_log_level_warning)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.settings_app_log_level_desc)).assertIsDisplayed()
        assertEquals("warning", runBlocking {
            UserDataRepository(database.userDataDao()).stringUserData(UserDataPath.Settings.Data.LogLevel.path).get()
        })
    }

    @Test fun emptyLogPageExplainsDisabledRecordingWithoutASourcePicker() {
        show {
            LogcatScreen(
                uiState = MutableLogcatUiState(), logFiles = listOf(""), logEntries = emptyList(),
                logLevelKey = "none", onLogLevelChange = {}, onClickBack = {}, onClickShareLogs = {},
                onClickClearLogs = { error("There are no logs to delete") }, onSelectLogFile = {},
            )
        }
        compose.onNodeWithText(label(R.string.log_recording_off)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.log_recording_off_desc)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.log_source)).assertDoesNotExist()
    }

    @Test fun anEmptyArchiveCanBeDeletedAfterConfirmationAndCancelKeepsIt() {
        val archive = "lnr_panic_20260920_120000.log"
        val state = MutableLogcatUiState().apply { isFileMode = true; selectedLogFile = archive }
        var deletions = 0
        show {
            LogcatScreen(
                uiState = state, logFiles = listOf(archive, ""), logEntries = emptyList(),
                logLevelKey = "none", onLogLevelChange = {}, onClickBack = {}, onClickShareLogs = {},
                onClickClearLogs = { deletions++; true }, onSelectLogFile = {},
            )
        }
        compose.onNodeWithContentDescription(label(R.string.log_clear)).assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals(0, deletions)
        compose.onNodeWithContentDescription(label(R.string.log_clear)).performClick()
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        assertEquals(1, deletions)
    }

    @Test fun clearAllIsAvailableFromTheToolbarAndRequiresConfirmation() {
        val archive = "lnr_export_20260920_120000.log"
        var deletions = 0
        show {
            LogcatScreen(
                uiState = MutableLogcatUiState(), logFiles = listOf(archive, ""), logEntries = emptyList(),
                logLevelKey = "none", onLogLevelChange = {}, onClickBack = {}, onClickShareLogs = {},
                onClickClearLogs = { deletions++; true }, onSelectLogFile = {},
            )
        }
        compose.onNodeWithContentDescription(label(R.string.action_more_options)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.export_and_share)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.log_clear)).performClick()
        assertEquals(0, deletions)
        compose.onNodeWithText(label(R.string.log_clear_all_confirm)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        assertEquals(1, deletions)
    }

    @Test fun updatesPageUsesGitHubDespiteImportedLegacySettings() {
        runBlocking {
            data.stringUserData(UserDataPath.Settings.App.DistributionPlatform.path).set("LnrAPI")
            data.stringUserData(UserDataPath.Settings.App.UpdateChannel.path).set("CI")
        }
        val state = SettingState(data, scope)
        var checks = 0
        var backs = 0
        show { UpdatesSettingsScreen("Not checked", state, { checks++ }, { backs++ }) }
        assertEquals(0, checks)
        entry(R.string.settings_auto_check_updates).performClick()
        compose.waitUntil { !state.checkUpdate }
        entry(R.string.settings_distribution_platform).assertIsDisplayed()
        compose.onNodeWithText("Renakoni/nextvol", substring = true).assertIsDisplayed()
        compose.onNodeWithText("GitHub", substring = true).assertIsDisplayed()
        compose.onNodeWithText("LightNovelReader API").assertDoesNotExist()
        compose.onNodeWithText("Test Update Channel").assertDoesNotExist()
        entry(R.string.settings_distribution_platform).performClick()
        assertEquals("https://github.com/Renakoni/nextvol/releases",
            org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity.data.toString())
        entry(R.string.settings_get_updates).performClick()
        compose.onNodeWithContentDescription(label(R.string.sources_back)).performClick()
        assertEquals(1, checks)
        assertEquals(1, backs)
        assertEquals(false, runBlocking { state.checkUpdateUserData.get() })
    }

    @Test fun aboutPageKeepsAppInformationAndLicensesWithoutUpstreamServices() {
        var licenses = 0
        var backs = 0
        show { AboutSettingsScreen({ licenses++ }, { backs++ }) }
        entry(R.string.app_name).assertIsDisplayed()
        compose.onNodeWithText(indi.renakoni.nextvol.BuildConfig.APPLICATION_ID).assertDoesNotExist()
        entry(R.string.settings_github_repo).assertIsDisplayed().performClick()
        assertEquals("https://github.com/Renakoni/nextvol",
            org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity.data.toString())
        for (removed in listOf("Communication", "Buy the Author a Tea", "Statistics")) {
            compose.onNodeWithText(removed).assertDoesNotExist()
        }
        entry(R.string.settings_open_source_licenses).performClick()
        compose.onNodeWithContentDescription(label(R.string.sources_back)).performClick()
        assertEquals(1, licenses)
        assertEquals(1, backs)
    }

    @Test fun storageCleanupRequiresMeasuredContentAndExplicitConfirmation() {
        val state = MutableStorageManagerUiState()
        var cacheClears = 0
        var downloadClears = 0
        show { StorageManagerScreen({}, state, { cacheClears++; true }, { downloadClears++ }, {}) }
        compose.onNodeWithTag("clear_reading_cache").assertDoesNotExist()
        compose.runOnIdle {
            state.isLoading = false
            state.snapshot = indi.renakoni.nextvol.data.storage.StorageUsageSnapshot()
        }
        storageAction("clear_reading_cache").assertIsNotEnabled()
        compose.runOnIdle { state.snapshot = state.snapshot!!.copy(readingContentBytes = 1024, downloadedContentBytes = 2048) }
        storageAction("clear_reading_cache").performScrollTo().performClick()
        compose.onNodeWithText(label(android.R.string.cancel)).performClick()
        assertEquals(0, cacheClears)
        storageAction("clear_reading_cache").performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.storage_clear_cache)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { cacheClears == 1 }
        storageAction("delete_all_downloads").performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.storage_delete_all)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { downloadClears == 1 }
        assertEquals(1, cacheClears)
    }

    @Test fun cleanupPreventsRepeatedConfirmationAndCanRetryAfterFailure() {
        val pending = CompletableDeferred<Unit>()
        var attempts = 0
        val state = MutableStorageManagerUiState().apply {
            isLoading = false
            snapshot = indi.renakoni.nextvol.data.storage.StorageUsageSnapshot(downloadedContentBytes = 1024)
        }
        show { StorageManagerScreen({}, state, { true }, {
            attempts++
            if (attempts == 1) pending.await()
        }, {}) }
        storageAction("delete_all_downloads").performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.storage_delete_all)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(label(R.string.processing)).assertIsNotEnabled()
        compose.onNodeWithText(label(android.R.string.cancel)).assertIsNotEnabled()
        assertEquals(1, attempts)
        compose.runOnIdle { pending.completeExceptionally(IllegalStateException("fixture failure")) }
        compose.waitUntil { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        storageAction("delete_all_downloads").performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.storage_delete_all)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { attempts == 2 }
    }
}
