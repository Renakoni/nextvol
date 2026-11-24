package indi.renakoni.nextvol.ui.home.settings.list

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry

@Composable
fun ExtensionsSettingsList(
    onClickChangeSource: () -> Unit,
    onClickBangumi: () -> Unit = {},
) {
    SettingsClickableEntry(
        painter = painterResource(R.drawable.public_24px),
        title = stringResource(R.string.sources_title),
        description = stringResource(R.string.sources_manage_help),
        onClick = onClickChangeSource,
    )
    SettingsClickableEntry(
        painter = painterResource(R.drawable.live_tv_24px),
        title = stringResource(R.string.bangumi_title),
        description = stringResource(R.string.bangumi_description),
        onClick = onClickBangumi,
    )
}
