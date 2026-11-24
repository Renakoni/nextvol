package indi.renakoni.nextvol.ui.book.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.SliderCommitState
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.resolveDiscreteStepIndex
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    settingState: ReaderSettingsEditor,
    onClickThemeSettings: () -> Unit,
) {
    var showPaper by rememberSaveable { mutableStateOf(false) }
    ReaderSettingsSheet(
        onDismissRequest = onDismissRequest,
        state = sheetState,
        backHandlerEnabled = !showPaper,
    ) {
        BackHandler(enabled = showPaper) { showPaper = false }
        var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
        Crossfade(
            targetState = showPaper,
            animationSpec = tween(if (settingState.reduceMotion) 0 else 180),
            label = "reader-paper-page",
        ) { paper ->
            if (paper) {
                ReaderPaperPage(
                    settings = settingState,
                    onBack = { showPaper = false },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    embedded = true,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
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
    val tabTitles = listOf(
        stringResource(R.string.appearance_settings),
        stringResource(R.string.control_settings),
        stringResource(R.string.reader_layout_settings),
    )

    val pagerState = rememberPagerState(initialPage = selectedTabIndex, pageCount = { tabTitles.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedTabIndex) {
        if (pagerState.currentPage != selectedTabIndex) {
            if (settingState.reduceMotion) {
                pagerState.scrollToPage(selectedTabIndex)
            } else {
                pagerState.animateScrollToPage(selectedTabIndex)
            }
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        onTabSelected(pagerState.currentPage)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        EditorialPillTabRow(
            tabs = tabTitles,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { index ->
                onTabSelected(index)
                scope.launch {
                    if (settingState.reduceMotion) {
                        pagerState.scrollToPage(index)
                    } else {
                        pagerState.animateScrollToPage(index)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        EditorialHairlineDivider(Modifier.padding(top = 6.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            userScrollEnabled = true,
        ) { pageIndex ->
            if (pageIndex == 2) {
                ReaderLayoutSettingsPage(settingState)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                ) {
                    when (pageIndex) {
                        0 -> AppearancePage(settingState, onClickThemeSettings, onClickPaper)
                        1 -> ActionPage(settingState)
                    }
                }
            }
        }
    }
}

fun LazyListScope.AppearancePage(
    settingState: ReaderSettingsEditor,
    onClickThemeSettings: () -> Unit,
    onClickPaper: () -> Unit,
) {
    item(key = "appearance-paper-group") {
        SettingsCategory(title = stringResource(R.string.settings_theme)) {
            ReaderPaperEntry(settingState, onClickPaper)
            SettingsClickableEntry(
                painter = painterResource(R.drawable.format_paint_24px),
                title = stringResource(R.string.settings_theme_settings),
                description = stringResource(R.string.settings_theme_settings_desc),
                onClick = onClickThemeSettings,
            )
            ReaderMotionSwitch(settingState)
        }
    }
    item(key = "appearance-display-group") {
        SettingsCategory(title = stringResource(R.string.appearance_settings)) {
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.lightbulb_24px),
                title = stringResource(R.string.settings_reader_keep_screen_on),
                description = stringResource(R.string.settings_reader_keep_screen_on_desc),
                checked = settingState.keepScreenOn,
                booleanUserData = settingState.keepScreenOnUserData,
            )
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.toolbar_24px),
                title = stringResource(R.string.settings_hide_status_bar),
                description = stringResource(R.string.settings_hide_status_bar_desc),
                checked = settingState.enableHideStatusBar,
                booleanUserData = settingState.enableHideStatusBarUserData,
            )
            SettingsMenuEntry(
                painter = painterResource(R.drawable.battery_android_full_24px),
                title = stringResource(R.string.settings_reader_battery_indicator),
                description = stringResource(R.string.settings_reader_battery_indicator_desc),
                options = ReaderIndicatorBatteryDisplayModeOptions,
                selectedOptionKey = settingState.batteryIndicatorDisplayMode,
                stringUserData = settingState.batteryIndicatorDisplayModeUserData,
            )
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.outline_schedule_24px),
                title = stringResource(R.string.settings_reader_time_indicator),
                description = stringResource(R.string.settings_reader_time_indicator_desc),
                checked = settingState.enableTimeIndicator,
                booleanUserData = settingState.enableTimeIndicatorUserData,
            )
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.contract_24px),
                title = stringResource(R.string.settings_reader_chapter_indicator),
                description = stringResource(R.string.settings_reader_chapter_indicator_desc),
                checked = settingState.enableChapterTitleIndicator,
                booleanUserData = settingState.enableChapterTitleIndicatorUserData,
            )
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.clock_loader_40_24px),
                title = stringResource(R.string.settings_reader_progress_indicator),
                description = stringResource(R.string.settings_reader_progress_indicator_desc),
                checked = settingState.enableReadingChapterProgressIndicator,
                booleanUserData = settingState.enableReadingChapterProgressIndicatorUserData,
            )
        }
    }
}

fun LazyListScope.ActionPage(settingState: ReaderSettingsEditor) {
    item(key = "action-reading-mode-group") {
        SettingsCategory(title = stringResource(R.string.control_settings)) {
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.menu_book_24px),
                title = stringResource(R.string.settings_reader_page_mode),
                description = stringResource(R.string.settings_reader_page_mode_desc),
                checked = settingState.isUsingFlipPage,
                booleanUserData = settingState.isUsingFlipPageUserData,
            )
            if (settingState.isUsingFlipPage) {
                SettingsSwitchEntry(
                    painter = painterResource(R.drawable.touch_app_24px),
                    title = stringResource(R.string.settings_reader_t2tp),
                    description = stringResource(R.string.settings_reader_t2tp_desc),
                    checked = settingState.isUsingClickFlipPage,
                    booleanUserData = settingState.isUsingClickFlipPageUserData,
                )
                SettingsMenuEntry(
                    painter = painterResource(R.drawable.transition_chop_24px),
                    title = stringResource(R.string.settings_reader_page_turn_anim),
                    description = stringResource(R.string.settings_reader_page_turn_anim_desc),
                    options = FlipAnimationOptions,
                    disabled = settingState.reduceMotion,
                    selectedOptionKey = settingState.flipAnime,
                    stringUserData = settingState.flipAnimeUserData,
                )
                SettingsSwitchEntry(
                    painter = painterResource(R.drawable.quick_reorder_24px),
                    title = stringResource(R.string.settings_reader_quick_chapter_switch),
                    description = stringResource(R.string.settings_reader_quick_chapter_switch_desc),
                    checked = settingState.fastChapterChange,
                    booleanUserData = settingState.fastChapterChangeUserData,
                )
            } else {
                SettingsSwitchEntry(
                    painter = painterResource(R.drawable.unfold_more_double_24px),
                    title = stringResource(R.string.settings_continous_scrolling),
                    description = stringResource(R.string.settings_continous_scrolling_desc),
                    checked = settingState.isUsingContinuousScrolling,
                    booleanUserData = settingState.isUsingContinuousScrollingUserData,
                )
            }
            SettingsMenuEntry(
                painter = painterResource(R.drawable.block_24px),
                title = stringResource(R.string.settings_reader_back_block_mode),
                description = stringResource(R.string.settings_reader_back_block_mode_desc),
                options = ReaderBackBlockModeOptions,
                selectedOptionKey = settingState.backBlockMode,
                stringUserData = settingState.backBlockModeUserData,
            )
        }
    }

    item(key = "action-volume-group") {
        SettingsCategory {
            SettingsSwitchEntry(
                painter = painterResource(R.drawable.auto_stories_24px),
                title = stringResource(
                    if (settingState.isUsingFlipPage) R.string.settings_reader_volume_key_control
                    else R.string.reader_volume_scroll_title,
                ),
                description = stringResource(
                    if (settingState.isUsingFlipPage) R.string.settings_reader_volume_key_control_desc
                    else R.string.reader_volume_scroll_description,
                ),
                checked = settingState.isUsingVolumeKeyFlip,
                booleanUserData = settingState.isUsingVolumeKeyFlipUserData,
            )
            if (settingState.isUsingVolumeKeyFlip) {
                if (!settingState.isUsingFlipPage) {
                    VolumeScrollFractionEntry(settingState)
                }
                VolumeKeyIntervalEntry(settingState)
            }
        }
    }
}

@Composable
private fun VolumeScrollFractionEntry(settingState: ReaderSettingsEditor) {
    val tokens = NextVolDesign.tokens
    val commitState = remember { SliderCommitState() }
    var fraction by remember(settingState.volumeKeyScrollFraction) {
        mutableFloatStateOf(settingState.volumeKeyScrollFraction)
    }
    val currentSave by rememberUpdatedState { value: Float ->
        settingState.volumeKeyScrollFractionUserData.asynchronousSet(value)
    }
    val title = stringResource(R.string.reader_volume_scroll_step)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.cardSurface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EditorialIconBadge(painter = painterResource(R.drawable.unfold_more_double_24px))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = typography.headlineSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.activePillFill)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = typography.labelMedium.copy(
                            fontWeight = FontWeight.W600,
                            fontFeatureSettings = "tnum",
                        ),
                        color = colorScheme.primary,
                    )
                }
            }
            MiuixSlider(
                value = fraction,
                onValueChange = { raw ->
                    val snapped = (raw * 20).roundToInt() / 20f
                    fraction = snapped
                    commitState.onValueChanged(snapped)?.let { currentSave(it) }
                },
                onValueChangeFinished = {
                    commitState.onValueChangeFinished()?.let { currentSave(it) }
                },
                valueRange = 0.1f..1f,
                steps = 17,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = title }
                    .pointerInput(commitState) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent(PointerEventPass.Initial)
                                if (down.changes.any { it.pressed }) {
                                    commitState.onPointerDown()
                                    while (true) {
                                        val next = awaitPointerEvent(PointerEventPass.Final)
                                        if (next.changes.none { it.pressed }) break
                                    }
                                    commitState.onPointerReleased()?.let { currentSave(it) }
                                }
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun VolumeKeyIntervalEntry(settingState: ReaderSettingsEditor) {
    val tokens = NextVolDesign.tokens
    val steps = remember { listOf(-1f, 0.1f, 0.2f, 0.3f, 0.5f, 0.8f, 1.0f, 2.0f, 4.0f) }
    val commitState = remember { SliderCommitState() }
    var tempValue by remember(settingState.volumeKeyContinuousFlipInterval) {
        mutableFloatStateOf(settingState.volumeKeyContinuousFlipInterval)
    }
    val currentSave by rememberUpdatedState { value: Float ->
        settingState.volumeKeyContinuousFlipIntervalUserData.asynchronousSet(value)
    }
    val title = stringResource(
        if (settingState.isUsingFlipPage) R.string.settings_reader_volume_key_interval
        else R.string.reader_volume_scroll_interval,
    )
    val index = resolveDiscreteStepIndex(steps, tempValue)
    val selectedStep = steps[index]
    val valueLabel = if (selectedStep <= 0f) {
        stringResource(R.string.discovery_filter_off)
    } else {
        "${selectedStep.readerValueText()} s"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.cardSurface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EditorialIconBadge(painter = painterResource(R.drawable.timer_24px))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = typography.headlineSmall,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedStep <= 0f) tokens.cardElevatedSurface else tokens.activePillFill)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = valueLabel,
                        style = typography.labelMedium.copy(
                            fontWeight = FontWeight.W600,
                            fontFeatureSettings = "tnum",
                        ),
                        color = if (selectedStep <= 0f) colorScheme.onSurfaceVariant else colorScheme.primary,
                    )
                }
            }
            MiuixSlider(
                value = index.toFloat(),
                onValueChange = { raw ->
                    val mapped = steps[raw.roundToInt().coerceIn(0, steps.lastIndex)]
                    tempValue = mapped
                    commitState.onValueChanged(mapped)?.let { currentSave(it) }
                },
                onValueChangeFinished = {
                    commitState.onValueChangeFinished()?.let { currentSave(it) }
                },
                valueRange = 0f..steps.lastIndex.toFloat(),
                steps = (steps.size - 2).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = title }
                    .pointerInput(commitState) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent(PointerEventPass.Initial)
                                if (down.changes.any { it.pressed }) {
                                    commitState.onPointerDown()
                                    while (true) {
                                        val next = awaitPointerEvent(PointerEventPass.Final)
                                        if (next.changes.none { it.pressed }) break
                                    }
                                    commitState.onPointerReleased()?.let { currentSave(it) }
                                }
                            }
                        }
                    },
            )
        }
    }
}
