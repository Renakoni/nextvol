package indi.renakoni.nextvol.ui.tts

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.MAX_SLEEP_TIMER_MINUTES
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

@Composable
private fun timerRemaining(state: ReadAloudState): String? {
    val deadline = state.sleepTimerDeadline
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (deadline != null && now < deadline) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val remaining = deadline?.minus(now) ?: state.sleepTimerRemainingMillis
    return remaining?.let { DateUtils.formatElapsedTime((it.coerceAtLeast(0) + 999) / 1_000) }
}

@Composable
private fun chapterTimerLabel(chapters: Int): String = when (chapters) {
    1 -> stringResource(R.string.tts_timer_chapter_end)
    3 -> stringResource(R.string.tts_timer_three_chapters)
    5 -> stringResource(R.string.tts_timer_five_chapters)
    else -> pluralStringResource(R.plurals.tts_timer_chapters_remaining, chapters, chapters)
}

@Composable
internal fun SpeechSleepTimerButton(
    state: ReadAloudState,
    onSelect: (Int?) -> Unit,
    onChapters: ((Int) -> Unit)?,
    onOpen: (() -> Unit)? = null,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val remaining = timerRemaining(state)
    val title = stringResource(R.string.tts_sleep_timer)
    val label = state.sleepTimerChapters?.let { chapterTimerLabel(it) } ?: remaining
    val description = label ?: stringResource(R.string.tts_timer_off)
    val active = label != null
    val enabled = state.isActive || state.phase == SpeechPhase.Paused

    Row(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (active) tokens.activePillFill else tokens.cardSurface)
            .border(
                width = if (active) 1.dp else 0.75.dp,
                color = if (active) tokens.activePillBorder else tokens.cardBorder,
                shape = CircleShape,
            )
            .clickable(enabled = enabled) {
                if (onOpen != null) onOpen() else expanded = true
            }
            .semantics {
                role = Role.Button
                contentDescription = title
                stateDescription = description
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.timer_24px),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                active -> colors.primary
                else -> colors.onSurfaceVariant
            },
        )
        Text(
            text = label ?: title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (active) FontWeight.W600 else FontWeight.W500,
                fontFeatureSettings = "tnum",
            ),
            color = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                active -> colors.primary
                else -> colors.onSurface
            },
            maxLines = 1,
        )
    }
    if (expanded) SpeechSleepTimerSheet(state, onSelect, onChapters) { expanded = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSleepTimerSheet(
    state: ReadAloudState,
    onMinutes: (Int?) -> Unit,
    onChapters: ((Int) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var custom by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        sheetGesturesEnabled = !custom,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        SpeechSleepTimerContent(
            state = state,
            custom = custom,
            onCustomChange = { custom = it },
            onMinutes = onMinutes,
            onChapters = onChapters,
            onBack = onDismiss,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
internal fun SpeechSleepTimerContent(
    state: ReadAloudState,
    custom: Boolean,
    onCustomChange: (Boolean) -> Unit,
    onMinutes: (Int?) -> Unit,
    onChapters: ((Int) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val deadline = state.sleepTimerDeadline
    val initialMinutes = remember(deadline, state.sleepTimerRemainingMillis) {
        val remaining = deadline?.minus(SystemClock.elapsedRealtime()) ?: state.sleepTimerRemainingMillis
        remaining?.let { ((it + 59_999) / 60_000).toInt().coerceIn(1, MAX_SLEEP_TIMER_MINUTES) }
    }
    var hours by rememberSaveable { mutableIntStateOf((initialMinutes ?: 1) / 60) }
    var minutes by rememberSaveable { mutableIntStateOf((initialMinutes ?: 1) % 60) }
    val remaining = timerRemaining(state)
    val status = state.sleepTimerChapters?.let { chapterTimerLabel(it) }
        ?: remaining?.let { stringResource(R.string.tts_timer_remaining, it) }
    val cancelTimer = stringResource(R.string.tts_timer_off)
    val chooseMinutes: (Int?) -> Unit = {
        onMinutes(it)
        onBack()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (custom) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onCustomChange(false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                ) {
                    Text(stringResource(R.string.cancel), style = MaterialTheme.typography.bodyLarge)
                }
                TextButton(
                    onClick = { chooseMinutes(hours * 60 + minutes) },
                    enabled = hours * 60 + minutes > 0,
                ) {
                    Text(
                        stringResource(R.string.confirm),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W600),
                    )
                }
            }
            val itemHeight = 28.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .background(tokens.activePillFill, RoundedCornerShape(10.dp)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                ) {
                    DurationWheel(
                        initialValue = hours,
                        count = MAX_SLEEP_TIMER_MINUTES / 60 + 1,
                        unit = stringResource(R.string.tts_timer_hour_unit),
                        itemHeight = itemHeight,
                        modifier = Modifier.weight(1f),
                    ) { hours = it }
                    DurationWheel(
                        initialValue = minutes,
                        count = 60,
                        unit = stringResource(R.string.tts_timer_minute_unit),
                        itemHeight = itemHeight,
                        modifier = Modifier.weight(1f),
                    ) { minutes = it }
                }
            }
            Spacer(Modifier.height(14.dp))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EditorialIconButton(
                    onClick = onBack,
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(R.string.close),
                )
                Text(
                    text = stringResource(R.string.tts_sleep_timer),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            EditorialHairlineDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.tts_timer_by_chapter),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (count in listOf(1, 3, 5)) {
                        TimerOption(
                            label = chapterTimerLabel(count),
                            selected = state.sleepTimerChapters == count,
                            enabled = onChapters != null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (state.sleepTimerChapters == count) onMinutes(null) else onChapters?.invoke(count)
                                onBack()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tts_timer_by_time),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (status != null) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(tokens.activePillFill)
                                .clickable(onClickLabel = cancelTimer) { chooseMinutes(null) }
                                .semantics { contentDescription = cancelTimer }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.check_24px),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(colors.primary, CircleShape)
                                    .padding(2.dp),
                                tint = colors.onPrimary,
                            )
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = colors.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (value in listOf(10, 30, 60, 90)) {
                        TimerOption(
                            label = stringResource(R.string.tts_timer_minutes, value),
                            selected = initialMinutes == value,
                            modifier = Modifier.weight(1f),
                            onClick = { chooseMinutes(value.takeUnless { initialMinutes == value }) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.cardSurface)
                        .border(0.75.dp, tokens.cardBorder, RoundedCornerShape(14.dp))
                        .clickable { onCustomChange(true) }
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tts_timer_custom),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W500),
                        color = colors.onSurface,
                    )
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TimerOption(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (selected) tokens.activePillFill else tokens.cardSurface)
            .border(
                width = if (selected) 1.dp else 0.75.dp,
                color = if (selected) tokens.activePillBorder else tokens.cardBorder,
                shape = CircleShape,
            )
            .selectable(selected, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
                fontFeatureSettings = "tnum",
            ),
            textAlign = TextAlign.Center,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DurationWheel(
    initialValue: Int,
    count: Int,
    unit: String,
    itemHeight: Dp,
    modifier: Modifier,
    onChange: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialValue)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val selected by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: initialValue
        }
    }
    LaunchedEffect(selected) {
        onChange(selected)
        if (listState.isScrollInProgress) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    LazyColumn(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(vertical = itemHeight * 3),
        modifier = modifier
            .height(itemHeight * 7)
            .selectableGroup()
            .semantics { contentDescription = unit },
    ) {
        items(count) { value ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .selectable(
                        selected = value == selected,
                        role = Role.RadioButton,
                        onClick = { scope.launch { listState.animateScrollToItem(value) } },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.toString() + unit,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.graphicsLayer {
                        val layout = listState.layoutInfo
                        val item = layout.visibleItemsInfo.find { it.index == value }
                        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                        val distance = item?.let { (it.offset + it.size / 2f - center) / it.size } ?: 0f
                        val angle = distance * 0.34f
                        rotationX = -distance * 19.5f
                        cameraDistance = 12f * density
                        translationY = (sin(angle) / sin(0.34f) - distance) * itemHeight.toPx()
                        val visibility = (1f - abs(distance) / 3.5f).coerceIn(0f, 1f)
                        alpha = visibility * visibility
                        scaleX = 1f - abs(distance).coerceAtMost(3f) * 0.035f
                    },
                )
            }
        }
    }
}
