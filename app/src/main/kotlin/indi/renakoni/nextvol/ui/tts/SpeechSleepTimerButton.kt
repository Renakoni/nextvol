package indi.renakoni.nextvol.ui.tts

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.MAX_SLEEP_TIMER_MINUTES
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechPhase
import kotlinx.coroutines.delay

@Composable
internal fun SpeechSleepTimerButton(state: ReadAloudState, onSelect: (Int?) -> Unit, onChapterEnd: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val deadline = state.sleepTimerDeadline
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (deadline != null && now < deadline) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val title = stringResource(R.string.tts_sleep_timer)
    val chapterEnd = stringResource(R.string.tts_timer_chapter_end)
    val remaining = deadline?.let { DateUtils.formatElapsedTime(((it - now).coerceAtLeast(0) + 999) / 1_000) }
    val description = when {
        state.stopAtChapterEnd -> chapterEnd
        remaining != null -> stringResource(R.string.tts_timer_remaining, remaining)
        else -> stringResource(R.string.tts_timer_off)
    }
    val active = deadline != null || state.stopAtChapterEnd
    Box {
        TextButton(
            onClick = { custom = false; expanded = true },
            enabled = state.isActive || state.phase == SpeechPhase.Paused,
            modifier = Modifier.semantics { contentDescription = title; stateDescription = description },
            colors = ButtonDefaults.textButtonColors(contentColor = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(painterResource(R.drawable.timer_24px), null, Modifier.size(20.dp))
            Text(if (state.stopAtChapterEnd) chapterEnd else remaining ?: title, Modifier.padding(start = 6.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(280.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (!custom) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tts_timer_off)) },
                    leadingIcon = { Icon(painterResource(R.drawable.close_24px), null) },
                    onClick = { expanded = false; onSelect(null) },
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (presets in listOf(listOf(15, 30), listOf(45, 60))) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (minutes in presets) OutlinedButton(
                            onClick = { expanded = false; onSelect(minutes) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Text(stringResource(R.string.tts_timer_minutes, minutes))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(
                    text = { Text(chapterEnd) },
                    leadingIcon = { Icon(painterResource(R.drawable.menu_book_24px), null) },
                    trailingIcon = if (state.stopAtChapterEnd) ({ Icon(painterResource(R.drawable.check_24px), null) }) else null,
                    onClick = { expanded = false; onChapterEnd() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tts_timer_custom)) },
                    leadingIcon = { Icon(painterResource(R.drawable.edit_square_24px), null) },
                    onClick = { custom = true },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(title, style = MaterialTheme.typography.titleMedium) },
                    leadingIcon = { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) },
                    onClick = { custom = false },
                )
                val minutes = input.toIntOrNull()?.takeIf { it in 1..MAX_SLEEP_TIMER_MINUTES }
                val apply = { if (minutes != null) { expanded = false; onSelect(minutes) } }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.tts_timer_custom)) },
                        supportingText = { Text(stringResource(R.string.tts_timer_range, MAX_SLEEP_TIMER_MINUTES)) },
                        singleLine = true,
                        isError = input.isNotEmpty() && minutes == null,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { apply() }),
                    )
                    FilledTonalButton(onClick = apply, enabled = minutes != null, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tts_timer_set))
                    }
                }
            }
        }
    }
}
