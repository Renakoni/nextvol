@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.home.settings.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.ui.book.reader.ReaderFontLicensesEntry
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsDisableStatsDialog
import indi.renakoni.nextvol.ui.home.settings.SettingState
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AboutSettingsScreen(
    settingState: SettingState,
    onClickLicenses: () -> Unit,
    onOptOut: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.about_settings, onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SettingsCategory {
                    AboutSettingsList(settingState, onClickLicenses, onOptOut)
                }
            }
            navigationBarSpacer()
        }
    }
}

@Composable
private fun AboutSettingsList(
    settingState: SettingState,
    onClickLicenses: () -> Unit,
    onOptOut: () -> Unit
) {
    val appInfo: String = buildString {
        appendLine(BuildConfig.APPLICATION_ID)
        append("${BuildConfig.VERSION_NAME} [${BuildConfig.VERSION_CODE}] - ")
            .append(if (BuildConfig.DEBUG) "debug" else "release")
    }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showDisableStatsDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }

    if (showAppInfoDialog) {
        SettingsAboutInfoDialog(onDismissRequest = { showAppInfoDialog = false })
    }

    if (showPrivacyPolicy) {
        SettingsPrivacyPolicyDialog(
            onDismissRequest = {
                showPrivacyPolicy = false
                showDisableStatsDialog = false
            }
        )
    }

    if (showDisableStatsDialog) {
        SettingsDisableStatsDialog(
            onClickConfirm = {
                onOptOut()
                settingState.statisticsUserData.asynchronousSet(false)
                showDisableStatsDialog = false
            },
            onDismissRequest = { showDisableStatsDialog = false },
            onClickShowPrivacyPolicy = {
                showPrivacyPolicy = true
            }
        )
    }

    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.info_24px),
        title = stringResource(R.string.app_name),
        description = appInfo,
        onClick = { showAppInfoDialog = true },
        option = stringResource(R.string.item_view_details)
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.group_24px),
        title = stringResource(R.string.settings_communication),
        description = stringResource(R.string.settings_communication_desc),
        openUrl = "https://qm.qq.com/q/Tp80Hf9Oms"
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.archive_24px),
        title = stringResource(R.string.settings_github_repo),
        description = stringResource(R.string.settings_github_repo_desc),
        openUrl = "https://github.com/dmzz-yyhyy/LightNovelReader"
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.volunteer_activism_24px),
        title = stringResource(R.string.settings_support_author),
        description = stringResource(R.string.settings_support_author_desc),
        openUrl = "https://afdian.com/a/lightnovelreader"
    )
    SettingsSwitchEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable. data_usage_24px),
        title = stringResource(R.string.settings_statistics),
        description = stringResource(R.string.settings_statistics_desc),
        checked = if (BuildConfig.DEBUG) false else settingState.statistics,
        onCheckedChange = { checked ->
            if (!checked && settingState.statistics) {
                showDisableStatsDialog = true
            } else {
                settingState.statisticsUserData.asynchronousSet(checked)
            }
        },
        disabled = BuildConfig.DEBUG
    )
    ReaderFontLicensesEntry()
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.code_24px),
        title = stringResource(R.string.settings_open_source_licenses),
        onClick = onClickLicenses
    )
}
