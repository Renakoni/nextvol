package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.labelResource
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton

@Composable
internal fun ReadAloudState.playbackTitle(): String = if (request?.isPreview == true) {
    request.previewLabel.ifBlank { voiceLabel }.ifBlank { stringResource(R.string.tts_preview) }
} else {
    bookTitle.ifEmpty { stringResource(R.string.tts_title) }
}

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
    val tokens = NextVolDesign.tokens
    var showTimer by rememberSaveable { mutableStateOf(false) }
    var customTimer by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        sheetGesturesEnabled = !(showTimer && customTimer),
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = !showTimer,
            isAppearanceLightStatusBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        BackHandler(enabled = showTimer) {
            if (customTimer) customTimer = false else showTimer = false
        }
        // Switch in-place inside a single sheet so opening the sleep timer never flickers a second modal.
        AnimatedContent(
            targetState = showTimer && onSleepTimer != null,
            transitionSpec = {
                fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(120))
            },
            label = "readAloudSheetPage",
            modifier = Modifier.navigationBarsPadding(),
        ) { timerPage ->
            if (timerPage && onSleepTimer != null) {
                SpeechSleepTimerContent(
                    state = state,
                    custom = customTimer,
                    onCustomChange = { customTimer = it },
                    onMinutes = onSleepTimer,
                    onChapters = onChapterSleepTimer,
                    onBack = {
                        customTimer = false
                        showTimer = false
                    },
                )
            } else {
                ReadAloudControls(
                    state = state,
                    onCommand = {
                        onCommand(it)
                        if (it == SpeechAction.Stop) onDismiss()
                    },
                    onSettings = onSettings,
                    onSleepTimer = onSleepTimer,
                    onChapterSleepTimer = onChapterSleepTimer,
                    onOpenSleepTimer = {
                        customTimer = false
                        showTimer = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, bottom = 18.dp),
                )
            }
        }
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
    onOpenSleepTimer: (() -> Unit)? = null,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme

    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Let long titles and errors scroll while keeping playback actions within reach.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = state.playbackTitle(),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.W700),
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.chapterTitle.isNotEmpty()) {
                        Text(
                            text = state.chapterTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onSettings != null) {
                    EditorialIconButton(
                        onClick = onSettings,
                        painter = painterResource(R.drawable.outline_settings_24px),
                        contentDescription = stringResource(R.string.tts_settings),
                    )
                }
            }

            if (state.phase !in setOf(SpeechPhase.Playing, SpeechPhase.Stopped, SpeechPhase.Paused)) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.activePillFill)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.phase == SpeechPhase.Preparing || state.phase == SpeechPhase.Buffering) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = stringResource(state.phase.labelResource),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600),
                        color = colors.primary,
                    )
                }
            }

            state.error?.takeIf { state.replacementFailure == null && state.roleFailure == null }?.let {
                val role = state.failedRole
                val voice = state.failedVoice?.label.orEmpty().ifBlank { state.voiceLabel }
                val subject = listOfNotNull(
                    role?.let { match -> match.person.ifBlank { match.ruleName } },
                    voice.takeIf { role == null || role.voiceRef != null },
                ).filter { it.isNotBlank() }.joinToString(" · ")
                val reason = stringResource(
                    if (role != null && role.voiceRef == null) R.string.tts_role_voice_missing else it.messageResource,
                )
                Text(
                    text = if (subject.isBlank()) reason else stringResource(R.string.tts_voice_error, subject, reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
            state.replacementFailure?.let { SpeechReplacementError(it) }
            state.roleFailure?.let { SpeechRoleError(it) }
        }

        val canStep = state.segmentCount > 0 && state.phase in setOf(
            SpeechPhase.Playing,
            SpeechPhase.Paused,
            SpeechPhase.Buffering,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorialIconButton(
                onClick = { onCommand(SpeechAction.Previous) },
                enabled = canStep,
                painter = painterResource(R.drawable.skip_previous_24px),
                contentDescription = stringResource(R.string.tts_previous_passage),
            )
            SpeechPlayPauseButton(
                state = state,
                onCommand = onCommand,
                modifier = Modifier
                    .size(68.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = tokens.glassShadow,
                        spotColor = tokens.glassShadow,
                        clip = false,
                    )
                    .testTag("speech-play-pause"),
            )
            EditorialIconButton(
                onClick = { onCommand(SpeechAction.Next) },
                enabled = canStep && (state.segmentIndex + 1 < state.segmentCount || state.nextChapterId != null),
                painter = painterResource(R.drawable.skip_next_24px),
                contentDescription = stringResource(R.string.tts_next_passage),
            )
        }

        EditorialHairlineDivider()

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hasChapters = state.previousChapterId != null || state.nextChapterId != null
            if (hasChapters) {
                ReadAloudActionPill(
                    text = stringResource(R.string.previous_chapter),
                    enabled = state.previousChapterId != null,
                    onClick = { onCommand(SpeechAction.PreviousChapter) },
                )
            }
            if (onSleepTimer != null && state.request?.isPreview == false) {
                SpeechSleepTimerButton(state, onSleepTimer, onChapterSleepTimer, onOpenSleepTimer)
            }
            if (hasChapters) {
                ReadAloudActionPill(
                    text = stringResource(R.string.next_chapter),
                    enabled = state.nextChapterId != null,
                    onClick = { onCommand(SpeechAction.NextChapter) },
                )
            }
            ReadAloudActionPill(
                text = stringResource(R.string.tts_stop),
                enabled = state.request != null,
                destructive = true,
                onClick = { onCommand(SpeechAction.Stop) },
            )
        }
    }
}

@Composable
private fun ReadAloudActionPill(
    text: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val fg = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
        destructive -> colors.error
        else -> colors.onSurface
    }
    Box(
        modifier = Modifier
            .heightIn(min = 38.dp)
            .clip(CircleShape)
            .background(if (destructive && enabled) colors.errorContainer.copy(alpha = 0.45f) else tokens.cardSurface)
            .border(
                width = 0.75.dp,
                color = if (destructive && enabled) colors.error.copy(alpha = 0.35f) else tokens.cardBorder,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W500),
            color = fg,
            maxLines = 1,
        )
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
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(
            painter = painterResource(if (state.isActive) R.drawable.pause_24px else R.drawable.play_arrow_24px),
            contentDescription = stringResource(description),
            modifier = Modifier.size(28.dp),
        )
    }
}
