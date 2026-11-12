package indi.renakoni.nextvol.ui.home.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequest
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.home.settings.list.AppSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.DataSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.DisplaySettingsList
import indi.renakoni.nextvol.ui.home.settings.list.ExtensionsSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.ReadingSettingsList
import indi.renakoni.nextvol.utils.navigationBarSpacer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(
    settingState: SettingState,
    importData: (Uri, Boolean) -> OneTimeWorkRequest,
    onClickLogcat: () -> Unit,
    onClickChangeSource: () -> Unit,
    onClickExportUserData: () -> Unit,
    onClickUpdates: () -> Unit,
    onClickAbout: () -> Unit,
    onClickThemeSettings: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickReadAloud: () -> Unit,
    onClickStorageManager: () -> Unit,
    onBack: () -> Unit,
    onClickBangumi: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    Column {
        SettingsTopBar(scrollBehavior, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(), listState
        ) {
            item {
                SettingsCategory(
                    title = stringResource(R.string.extensions_settings),
                ) {
                    ExtensionsSettingsList(
                        onClickChangeSource = onClickChangeSource,
                        onClickBangumi = onClickBangumi,
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.reading_settings),
                ) {
                    ReadingSettingsList(
                        settingState = settingState,
                        onClickTheme = onClickThemeSettings,
                        onClickTextFormatting = onClickTextFormatting,
                        onClickReadAloud = onClickReadAloud,
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.display_settings),
                ) {
                    DisplaySettingsList()
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.data_settings),
                ) {
                    DataSettingsList(
                        onClickExportUserData = onClickExportUserData,
                        importData = importData,
                        onClickStorageManager = onClickStorageManager,
                    )
                }
            }
            item {
                SettingsCategory(
                    title = stringResource(R.string.app_settings),
                ) {
                    AppSettingsList(
                        onClickLogcat = onClickLogcat,
                        onClickUpdates = onClickUpdates,
                        onClickAbout = onClickAbout,
                    )
                }
            }
            navigationBarSpacer()
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    @StringRes title: Int = R.string.nav_settings,
    onBack: () -> Unit,
) = SettingsTopBar(scrollBehavior, title, onBack, titleText = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    @StringRes title: Int = R.string.nav_settings,
    onBack: () -> Unit,
    titleText: String?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = titleText ?: stringResource(title), style = typography.displayLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(id = R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.sources_back)
                )
            }
        },
        scrollBehavior = scrollBehavior,
        actions = actions,
    )
}

@Composable
fun SettingsCategory(
    title: String? = null, content: @Composable ColumnScope.() -> Unit
) {
    title?.let {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), text = it
        )
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
}
