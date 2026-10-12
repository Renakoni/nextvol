package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSliderEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    settingState: ReaderSettingsEditor,
    onClickThemeSettings: () -> Unit
) {
    var showPaper by rememberSaveable { mutableStateOf(false) }
    ReaderSettingsSheet(
        onDismissRequest = onDismissRequest,
        state = sheetState,
        backHandlerEnabled = !showPaper,
    ) {
        BackHandler(enabled = showPaper) { showPaper = false }
        var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
        Crossfade(targetState = showPaper, animationSpec = tween(180), label = "reader-paper-page") { paper ->
            if (paper) {
                ReaderPaperPage(settingState, onBack = { showPaper = false }, windowInsets = WindowInsets(0, 0, 0, 0))
            } else Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                ContentSettings(
                    settingState = settingState,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index -> selectedTabIndex = index },
                    onClickThemeSettings = onClickThemeSettings,
                    onClickPaper = { showPaper = true },
                )
            }
        }
    }
}

data class TabItem(val title: String, val iconRes: Int)

@Composable
fun ContentSettings(
    settingState: ReaderSettingsEditor,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onClickThemeSettings: () -> Unit,
    onClickPaper: () -> Unit,
) {
    val tabs = listOf(
        TabItem(stringResource(R.string.appearance_settings), R.drawable.filled_menu_book_24px),
        TabItem(stringResource(R.string.control_settings), R.drawable.settings_applications_24px),
        TabItem(stringResource(R.string.reader_layout_settings), R.drawable.aspect_ratio_24px),
    )

    val pagerState = rememberPagerState(initialPage = selectedTabIndex, pageCount = { tabs.size })

    LaunchedEffect(selectedTabIndex) {
        pagerState.scrollToPage(selectedTabIndex)
    }
    LaunchedEffect(pagerState.currentPage) {
        onTabSelected(pagerState.currentPage)
    }

    Column {
        TabsRow(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            userScrollEnabled = false
        ) { pageIndex ->
            if (pageIndex == 2) {
                ReaderLayoutSettingsPage(settingState)
            } else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (pageIndex) {
                    0 -> AppearancePage(settingState, onClickThemeSettings, onClickPaper)
                    1 -> ActionPage(settingState)
                }
            }
        }
    }
}

@Composable
fun TabsRow(
    tabs: List<TabItem>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    SecondaryTabRow(
        selectedTabIndex,
        Modifier, colorScheme.surfaceContainerHigh,
        TabRowDefaults.primaryContentColor, {
            SecondaryIndicator(
                modifier = Modifier
                    .tabIndicatorOffset(selectedTabIndex)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            )
        },
        @Composable { HorizontalDivider() }, {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    content = {
                        Row(
                            modifier = Modifier.heightIn(min = 50.dp).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(tab.iconRes),
                                contentDescription = tab.title,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = tab.title,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                )
            }
        })
}

fun LazyListScope.AppearancePage(
    settingState: ReaderSettingsEditor,
    onClickThemeSettings: () -> Unit,
    onClickPaper: () -> Unit,
) {
    item {
        ReaderPaperEntry(settingState, onClickPaper, Modifier.background(colorScheme.surfaceContainerHigh))
    }
    item { ReaderMotionSwitch(settingState, Modifier.background(colorScheme.surfaceContainerHigh)) }
    item {
        SettingsClickableEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.format_paint_24px),
            title = stringResource(R.string.settings_theme_settings),
            description = stringResource(R.string.settings_theme_settings_desc),
            onClick = onClickThemeSettings
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.lightbulb_24px),
            title = stringResource(R.string.settings_reader_keep_screen_on),
            description = stringResource(R.string.settings_reader_keep_screen_on_desc),
            checked = settingState.keepScreenOn,
            booleanUserData = settingState.keepScreenOnUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.toolbar_24px),
            title = stringResource(R.string.settings_hide_status_bar),
            description = stringResource(R.string.settings_hide_status_bar_desc),
            checked = settingState.enableHideStatusBar,
            booleanUserData = settingState.enableHideStatusBarUserData,
        )
    }
    item {
        SettingsMenuEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.battery_android_full_24px),
            title = stringResource(R.string.settings_reader_battery_indicator),
            description = stringResource(R.string.settings_reader_battery_indicator_desc),
            options = ReaderIndicatorBatteryDisplayModeOptions,
            selectedOptionKey = settingState.batteryIndicatorDisplayMode,
            stringUserData = settingState.batteryIndicatorDisplayModeUserData
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.outline_schedule_24px),
            title = stringResource(R.string.settings_reader_time_indicator),
            description = stringResource(R.string.settings_reader_time_indicator_desc),
            checked = settingState.enableTimeIndicator,
            booleanUserData = settingState.enableTimeIndicatorUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.contract_24px),
            title = stringResource(R.string.settings_reader_chapter_indicator),
            description = stringResource(R.string.settings_reader_chapter_indicator_desc),
            checked = settingState.enableChapterTitleIndicator,
            booleanUserData = settingState.enableChapterTitleIndicatorUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.clock_loader_40_24px),
            title = stringResource(R.string.settings_reader_progress_indicator),
            description = stringResource(R.string.settings_reader_progress_indicator_desc),
            checked = settingState.enableReadingChapterProgressIndicator,
            booleanUserData = settingState.enableReadingChapterProgressIndicatorUserData,
        )
    }
}

fun LazyListScope.ActionPage(settingState: ReaderSettingsEditor) {
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.menu_book_24px),
            title = stringResource(R.string.settings_reader_page_mode),
            description = stringResource(R.string.settings_reader_page_mode_desc),
            checked = settingState.isUsingFlipPage,
            booleanUserData = settingState.isUsingFlipPageUserData,
        )
    }
    item {
        ReaderPageLayoutEntry(settingState,
            Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)))
    }
    item {
        SettingsMenuEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.block_24px),
            title = stringResource(R.string.settings_reader_back_block_mode),
            description = stringResource(R.string.settings_reader_back_block_mode_desc),
            options = ReaderBackBlockModeOptions,
            selectedOptionKey = settingState.backBlockMode,
            stringUserData = settingState.backBlockModeUserData
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
            painter = painterResource(R.drawable.auto_stories_24px),
            title = stringResource(if (settingState.isUsingFlipPage)
                R.string.settings_reader_volume_key_control else R.string.reader_volume_scroll_title),
            description = stringResource(if (settingState.isUsingFlipPage)
                R.string.settings_reader_volume_key_control_desc else R.string.reader_volume_scroll_description),
            checked = settingState.isUsingVolumeKeyFlip,
            booleanUserData = settingState.isUsingVolumeKeyFlipUserData,
        )
    }
    if (settingState.isUsingVolumeKeyFlip) {
        if (!settingState.isUsingFlipPage) {
            item { VolumeScrollFractionEntry(settingState) }
        }
        item {
            val steps = listOf(-1f, 0.1f, 0.2f, 0.3f, 0.5f, 0.8f, 1.0f, 2.0f, 4.0f)
            SettingsSliderEntry(
                modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
                painter = painterResource(R.drawable.timer_24px),
                title = stringResource(if (settingState.isUsingFlipPage)
                    R.string.settings_reader_volume_key_interval else R.string.reader_volume_scroll_interval),
                unit = "s",
                value = settingState.volumeKeyContinuousFlipInterval,
                valueRange = steps.first()..steps.last(),
                steps = steps,
                floatUserData = settingState.volumeKeyContinuousFlipIntervalUserData,
            )
        }
    }
    if (!settingState.isUsingFlipPage) {
        item {
            SettingsSwitchEntry(
                modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
                painter = painterResource(R.drawable.unfold_more_double_24px),
                title = stringResource(R.string.settings_continous_scrolling),
                description = stringResource(R.string.settings_continous_scrolling_desc),
                checked = settingState.isUsingContinuousScrolling,
                booleanUserData = settingState.isUsingContinuousScrollingUserData,
            )
        }
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsSwitchEntry(
                modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
                painter = painterResource(R.drawable.touch_app_24px),
                title = stringResource(R.string.settings_reader_t2tp),
                description = stringResource(R.string.settings_reader_t2tp_desc),
                checked = settingState.isUsingClickFlipPage,
                booleanUserData = settingState.isUsingClickFlipPageUserData,
            )
        }
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsMenuEntry(
                modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
                painter = painterResource(R.drawable.transition_chop_24px),
                title = stringResource(R.string.settings_reader_page_turn_anim),
                description = stringResource(R.string.settings_reader_page_turn_anim_desc),
                options = FlipAnimationOptions,
                disabled = settingState.reduceMotion,
                selectedOptionKey = settingState.flipAnime,
                stringUserData = settingState.flipAnimeUserData
            )
        }
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsSwitchEntry(
                modifier = Modifier.background(colorScheme.surfaceContainerHigh).then(readerSettingsItemMotion(settingState.reduceMotion)),
                painter = painterResource(R.drawable.quick_reorder_24px),
                title = stringResource(R.string.settings_reader_quick_chapter_switch),
                description = stringResource(R.string.settings_reader_quick_chapter_switch_desc),
                checked = settingState.fastChapterChange,
                booleanUserData = settingState.fastChapterChangeUserData,
            )
        }
    }
}

@Composable
private fun VolumeScrollFractionEntry(settingState: ReaderSettingsEditor) {
    var fraction by remember(settingState.volumeKeyScrollFraction) {
        mutableFloatStateOf(settingState.volumeKeyScrollFraction)
    }
    Column(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(colorScheme.surfaceContainerHigh)
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Text(stringResource(R.string.reader_volume_scroll_step), style = typography.headlineSmall)
        Text("${(fraction * 100).roundToInt()}%", color = colorScheme.primary, style = typography.bodyMedium)
        Slider(
            value = fraction,
            onValueChange = { fraction = (it * 20).roundToInt() / 20f },
            onValueChangeFinished = { settingState.volumeKeyScrollFractionUserData.asynchronousSet(fraction) },
            valueRange = 0.1f..1f,
            steps = 17,
        )
    }
}

private fun LazyItemScope.readerSettingsItemMotion(reduced: Boolean): Modifier =
    if (reduced) Modifier else Modifier.animateItem()
