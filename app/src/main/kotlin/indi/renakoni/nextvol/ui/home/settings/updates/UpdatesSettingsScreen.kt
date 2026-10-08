package indi.renakoni.nextvol.ui.home.settings.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.SettingState
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.utils.navigationBarSpacer

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UpdatesSettingsScreen(
    updatePhase: String,
    settingState: SettingState,
    checkUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    Column {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.app_updates, onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SettingsCategory {
                    UpdatesSettingsList(updatePhase, settingState, checkUpdate)
                }
            }
            navigationBarSpacer()
        }
    }
}

@Composable
private fun UpdatesSettingsList(
    updatePhase: String,
    settingState: SettingState,
    checkUpdate: () -> Unit,
) {
    SettingsSwitchEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.cloud_download_24px),
        title = stringResource(R.string.settings_auto_check_updates),
        description = stringResource(R.string.settings_auto_check_updates_desc),
        checked = settingState.checkUpdate,
        booleanUserData = settingState.checkUpdateUserData
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.outline_explore_24px),
        title = stringResource(R.string.settings_distribution_platform),
        description = "${stringResource(R.string.key_platform_github)} · ${ProjectLinks.REPOSITORY}",
        openUrl = ProjectLinks.RELEASES
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.deployed_code_update_24px),
        title = stringResource(R.string.settings_get_updates),
        description = stringResource(R.string.settings_get_updates_desc),
        option = updatePhase,
        onClick = checkUpdate
    )
}
