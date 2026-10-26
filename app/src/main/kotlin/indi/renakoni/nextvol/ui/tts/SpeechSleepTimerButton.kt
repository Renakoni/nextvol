package indi.renakoni.nextvol.ui.tts

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.MAX_SLEEP_TIMER_MINUTES
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun SpeechSleepTimerButton(state: ReadAloudState, onSelect: (Int?) -> Unit, onChapters: ((Int) -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val deadline = state.sleepTimerDeadline
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (deadline != null && now < deadline) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val title = stringResource(R.string.tts_sleep_timer)
    val remaining = deadline?.let { DateUtils.formatElapsedTime(((it - now).coerceAtLeast(0) + 999) / 1_000) }
    val chapters = state.sleepTimerChapters
    val description = when {
        chapters == 1 -> stringResource(R.string.tts_timer_chapter_end)
        chapters != null -> pluralStringResource(R.plurals.tts_timer_chapters_remaining, chapters, chapters)
        remaining != null -> stringResource(R.string.tts_timer_remaining, remaining)
        else -> stringResource(R.string.tts_timer_off)
    }
    val active = deadline != null || chapters != null
    TextButton(
        onClick = { expanded = true },
        enabled = state.isActive || state.phase == SpeechPhase.Paused,
        modifier = Modifier.semantics { contentDescription = title; stateDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(painterResource(R.drawable.timer_24px), null, Modifier.size(20.dp))
        Text(if (chapters != null) description else remaining ?: title, Modifier.padding(start = 6.dp))
    }
    if (expanded) SpeechSleepTimerSheet(
        state = state,
        status = description.takeIf { active },
        initialMinutes = deadline?.let { ((it - now + 59_999) / 60_000).toInt().coerceIn(1, MAX_SLEEP_TIMER_MINUTES) } ?: 30,
        onMinutes = { expanded = false; onSelect(it) },
        onChapters = onChapters?.let { select -> { count -> expanded = false; select(count) } },
        onDismiss = { expanded = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeechSleepTimerSheet(
    state: ReadAloudState,
    status: String?,
    initialMinutes: Int,
    onMinutes: (Int?) -> Unit,
    onChapters: ((Int) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf(false) }
    var hours by rememberSaveable { mutableIntStateOf(initialMinutes / 60) }
    var minutes by rememberSaveable { mutableIntStateOf(initialMinutes % 60) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (custom) IconButton(onClick = { custom = false }) {
                        Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(if (custom) R.string.tts_timer_custom else R.string.tts_sleep_timer),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        if (!custom && status != null) Text(status, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.close))
                    }
                }
                if (custom) {
                    val itemHeight = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(itemHeight)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)))
                        Row(Modifier.fillMaxWidth()) {
                            DurationWheel(hours, MAX_SLEEP_TIMER_MINUTES / 60 + 1, stringResource(R.string.tts_timer_hour_unit),
                                itemHeight, Modifier.weight(1f)) { hours = it }
                            DurationWheel(minutes, 60, stringResource(R.string.tts_timer_minute_unit),
                                itemHeight, Modifier.weight(1f)) { minutes = it }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.tts_timer_by_chapter), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (count in listOf(1, 3, 5)) TimerOption(
                                label = if (count == 1) stringResource(R.string.tts_timer_current_chapter)
                                    else pluralStringResource(R.plurals.tts_timer_chapters, count, count),
                                selected = state.sleepTimerChapters == count,
                                enabled = onChapters != null,
                                modifier = Modifier.weight(1f),
                                onClick = { onChapters?.invoke(count) },
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.tts_timer_by_time), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (value in listOf(15, 30, 45, 60)) TimerOption(
                                label = stringResource(R.string.tts_timer_minutes, value),
                                selected = false,
                                modifier = Modifier.weight(1f),
                                onClick = { onMinutes(value) },
                            )
                        }
                        TextButton(onClick = { custom = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.tts_timer_custom), Modifier.weight(1f), textAlign = TextAlign.Start)
                            Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (custom) {
                FilledTonalButton(
                    onClick = { onMinutes(hours * 60 + minutes) },
                    enabled = hours * 60 + minutes > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.tts_timer_set)) }
            } else {
                TextButton(onClick = { onMinutes(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(stringResource(R.string.tts_timer_off))
                }
            }
        }
    }
}

@Composable
private fun TimerOption(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DurationWheel(initialValue: Int, count: Int, unit: String, itemHeight: Dp, modifier: Modifier, onChange: (Int) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialValue)
    val scope = rememberCoroutineScope()
    val selected by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index ?: initialValue
        }
    }
    LaunchedEffect(selected) { onChange(selected) }
    LazyColumn(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(vertical = itemHeight * 2),
        modifier = modifier.height(itemHeight * 5).selectableGroup().semantics { contentDescription = unit },
    ) {
        items(count) { value ->
            val distance = abs(value - selected)
            val color = if (distance == 0) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (distance == 1) 0.55f else 0.25f)
            Row(
                Modifier.fillMaxWidth().height(itemHeight).selectable(selected = value == selected, role = Role.RadioButton,
                    onClick = { scope.launch { listState.animateScrollToItem(value) } }),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = color,
                    fontWeight = if (distance == 0) FontWeight.Medium else FontWeight.Normal)
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}
