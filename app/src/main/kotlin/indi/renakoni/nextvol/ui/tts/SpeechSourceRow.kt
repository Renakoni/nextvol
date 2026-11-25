package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechVoiceSelection
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun SpeechSourceRow(
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    onPreview: (() -> Unit)?,
    previewEnabled: Boolean = enabled,
    description: String = "",
    problem: String? = null,
    actions: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    selection: SpeechVoiceSelection? = null,
    management: VoiceRowManagement? = null,
    previewPlaying: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val isHighlighted = management?.checked ?: selected
    val selectionLabel = selection?.let {
        stringResource(if (it == SpeechVoiceSelection.Narration) R.string.tts_voice_narrator else R.string.tts_voice_dialogue)
    }
    val interaction = if (management != null) {
        Modifier.toggleable(
            value = management.checked,
            enabled = management.selectable,
            role = Role.Checkbox,
            onValueChange = { management.onToggle() },
        )
    } else if (onLongClick == null) {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    } else {
        Modifier
            .combinedClickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClickLabel = stringResource(R.string.tts_voice_select_narrator),
                onLongClickLabel = stringResource(R.string.tts_voice_select_dialogue),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                this.selected = selected
                selectionLabel?.let { stateDescription = it }
            }
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .squircleBackground(
                color = if (isHighlighted) tokens.activePillFill else tokens.cardSurface,
                cornerRadius = 18.dp,
            )
            .squircleBorder(
                width = if (isHighlighted) 1.25.dp else 0.75.dp,
                color = if (isHighlighted) colors.primary else tokens.cardBorder,
                cornerRadius = 18.dp,
            )
            .squircleClip(18.dp)
            .then(interaction)
            .heightIn(min = 68.dp)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (management != null) {
                Checkbox(checked = management.checked, onCheckedChange = null, enabled = management.selectable)
            } else {
                avatar()
            }
            if (management == null && selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .background(colors.surface, CircleShape)
                        .padding(2.dp)
                        .background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.check_24px),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = colors.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                )
                if (selectionLabel != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = selectionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.W600,
                            color = colors.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (problem != null) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (management == null && onPreview != null) {
            SpeechPreviewButton(name, previewEnabled, previewPlaying, onPreview)
        }
        if (management == null) {
            actions()
        } else {
            IconButton(onClick = {}, modifier = management.dragHandle) {
                Icon(
                    painter = painterResource(R.drawable.drag_indicator_24px),
                    contentDescription = stringResource(R.string.tts_voice_reorder, name),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}
