@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.home.settings.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderFontLicensesEntry
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.utils.navigationBarSpacer

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AboutSettingsScreen(
    onClickLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.about_settings),
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
                    AboutSettingsList(onClickLicenses)
                }
            }
            navigationBarSpacer()
        }
    }
}

@Composable
private fun AboutSettingsList(
    onClickLicenses: () -> Unit,
) {
    val appInfo = BuildConfig.VERSION_NAME
    var showAppInfoDialog by remember { mutableStateOf(false) }

    if (showAppInfoDialog) {
        SettingsAboutInfoDialog(onDismissRequest = { showAppInfoDialog = false })
    }

    SettingsClickableEntry(
        painter = painterResource(R.drawable.info_24px),
        title = stringResource(R.string.app_name),
        description = appInfo,
        onClick = { showAppInfoDialog = true },
        option = stringResource(R.string.item_view_details),
    )
    SettingsClickableEntry(
        painter = painterResource(R.drawable.archive_24px),
        title = stringResource(R.string.settings_github_repo),
        description = stringResource(R.string.settings_github_repo_desc),
        openUrl = ProjectLinks.GITHUB,
    )
    ReaderFontLicensesEntry()
    SettingsClickableEntry(
        painter = painterResource(R.drawable.code_24px),
        title = stringResource(R.string.settings_open_source_licenses),
        onClick = onClickLicenses,
    )
}
