package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.labelResource
import indi.renakoni.nextvol.tts.messageResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudSheet(
    state: ReadAloudState,
    onCommand: (SpeechAction) -> Unit,
    onSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
) {
    HideReadAloudOverlay()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        ReadAloudControls(
            state,
            onCommand = {
                onCommand(it)
                if (it == SpeechAction.Stop) onDismiss()
            },
            onSettings = onSettings,
            onSleepTimer = onSleepTimer,
            onChapterSleepTimer = onChapterSleepTimer,
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        )
    }
}

@Composable
fun ReadAloudControls(
    state: ReadAloudState,
    onCommand: (SpeechAction) -> Unit,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Let long titles and errors scroll while keeping playback actions within reach.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.bookTitle.ifEmpty {
                            stringResource(if (state.request?.isPreview == true) R.string.tts_preview else R.string.tts_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.chapterTitle.isNotEmpty()) Text(
                        state.chapterTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onSettings != null) IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.outline_settings_24px), stringResource(R.string.tts_settings))
                }
            }
            if (state.phase !in setOf(SpeechPhase.Playing, SpeechPhase.Stopped, SpeechPhase.Paused)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.phase == SpeechPhase.Preparing || state.phase == SpeechPhase.Buffering) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        stringResource(state.phase.labelResource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.error?.let {
                Text(
                    stringResource(it.messageResource),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val canStep = state.segmentCount > 0 && state.phase in setOf(
            SpeechPhase.Playing, SpeechPhase.Paused, SpeechPhase.Buffering,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onCommand(SpeechAction.Previous) },
                enabled = canStep,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.skip_previous_24px), stringResource(R.string.tts_previous_passage))
            }
            SpeechPlayPauseButton(state, onCommand, Modifier.size(72.dp).testTag("speech-play-pause"))
            IconButton(
                onClick = { onCommand(SpeechAction.Next) },
                enabled = canStep && (state.segmentIndex + 1 < state.segmentCount || state.nextChapterId != null),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.skip_next_24px), stringResource(R.string.tts_next_passage))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val hasChapters = state.previousChapterId != null || state.nextChapterId != null
            if (hasChapters) TextButton(
                onClick = { onCommand(SpeechAction.PreviousChapter) },
                enabled = state.previousChapterId != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.previous_chapter), textAlign = TextAlign.Center)
            }
            TextButton(
                onClick = { onCommand(SpeechAction.Stop) },
                enabled = state.request != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.tts_stop), textAlign = TextAlign.Center)
            }
            if (hasChapters) TextButton(
                onClick = { onCommand(SpeechAction.NextChapter) },
                enabled = state.nextChapterId != null,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.next_chapter), textAlign = TextAlign.Center)
            }
            if (onSleepTimer != null && state.request?.isPreview == false) {
                SpeechSleepTimerButton(state, onSleepTimer, onChapterSleepTimer)
            }
        }
    }
}

@Composable
internal fun SpeechPlayPauseButton(
    state: ReadAloudState,
    onCommand: (SpeechAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = when {
        state.isActive -> R.string.tts_pause
        state.phase == SpeechPhase.Failed -> R.string.tts_retry
        state.phase == SpeechPhase.Completed -> R.string.tts_restart
        else -> R.string.tts_resume
    }
    FilledIconButton(
        onClick = { onCommand(if (state.isActive) SpeechAction.Pause else SpeechAction.Resume) },
        enabled = state.request != null,
        modifier = modifier,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Icon(
            painterResource(if (state.isActive) R.drawable.pause_24px else R.drawable.play_arrow_24px),
            stringResource(description),
            Modifier.size(28.dp),
        )
    }
}
