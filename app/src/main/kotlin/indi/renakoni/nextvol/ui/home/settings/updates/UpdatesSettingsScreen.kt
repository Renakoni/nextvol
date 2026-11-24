package indi.renakoni.nextvol.ui.home.settings.updates

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingState
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.data.SettingsMenuOptions
import indi.renakoni.nextvol.utils.navigationBarSpacer

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UpdatesSettingsScreen(
    updatePhase: String,
    settingState: SettingState,
    checkUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.app_updates),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(id = R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 8.dp),
        ) {
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
        painter = painterResource(R.drawable.cloud_download_24px),
        title = stringResource(R.string.settings_auto_check_updates),
        description = stringResource(R.string.settings_auto_check_updates_desc),
        checked = settingState.checkUpdate,
        booleanUserData = settingState.checkUpdateUserData,
    )
    SettingsMenuEntry(
        painter = painterResource(R.drawable.outline_explore_24px),
        title = stringResource(R.string.settings_distribution_platform),
        options = SettingsMenuOptions.UpdatePlatformOptions,
        selectedOptionKey = settingState.distributionPlatformKey,
        onOptionChange = settingState.distributionPlatformKeyUserData::asynchronousSet,
    )
    SettingsClickableEntry(
        painter = painterResource(R.drawable.deployed_code_update_24px),
        title = stringResource(R.string.settings_get_updates),
        description = stringResource(R.string.settings_get_updates_desc),
        option = updatePhase,
        onClick = checkUpdate,
    )
}
