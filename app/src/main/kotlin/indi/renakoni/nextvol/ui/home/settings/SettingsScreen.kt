package indi.renakoni.nextvol.ui.home.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequest
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.list.AppSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.DataSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.DisplaySettingsList
import indi.renakoni.nextvol.ui.home.settings.list.ExtensionsSettingsList
import indi.renakoni.nextvol.ui.home.settings.list.ReadingSettingsList
import indi.renakoni.nextvol.utils.navigationBarSpacer
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

private const val DisplaySection = "display"

// Translated rows above Language can wrap differently after the Activity is recreated.
private val SettingsScrollSaver = Saver<LazyListState, IntArray>(
    save = { state ->
        val display = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == DisplaySection }
        if (display != null) intArrayOf(display.index, -display.offset)
        else intArrayOf(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    },
    restore = { (index, offset) -> LazyListState().apply { requestScrollToItem(index, offset) } },
)

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
    val listState = rememberSaveable(saver = SettingsScrollSaver) { LazyListState() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.nav_settings),
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
            state = listState,
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
            item(key = DisplaySection) {
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
    actions: @Composable RowScope.() -> Unit = {},
) {
    EditorialTopBar(
        title = titleText ?: stringResource(title),
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
        actions = actions,
    )
}

@Composable
fun SettingsCategory(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = NextVolDesign.tokens
    title?.let {
        SectionHeader(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            text = it,
        )
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .squircleBackground(color = tokens.divider, cornerRadius = tokens.cardRadius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = tokens.cardRadius)
            .squircleClip(cornerRadius = tokens.cardRadius),
        verticalArrangement = Arrangement.spacedBy(0.75.dp),
    ) {
        content()
    }
}
