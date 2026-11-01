package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechVoiceSelection

@Composable
internal fun SpeechSourceRow(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    avatar: @Composable () -> Unit, onPreview: (() -> Unit)?, previewEnabled: Boolean = enabled,
    description: String = "", problem: String? = null, actions: @Composable () -> Unit = {},
    onLongClick: (() -> Unit)? = null, selection: SpeechVoiceSelection? = null) {
    val colors = MaterialTheme.colorScheme
    val selectionLabel = selection?.let { stringResource(if (it == SpeechVoiceSelection.Narration)
        R.string.tts_voice_narrator else R.string.tts_voice_dialogue) }
    val interaction = if (onLongClick == null) Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else Modifier.combinedClickable(enabled = enabled, role = Role.Checkbox,
            onClickLabel = stringResource(R.string.tts_voice_select_narrator),
            onLongClickLabel = stringResource(R.string.tts_voice_select_dialogue),
            onClick = onClick, onLongClick = onLongClick).semantics {
                this.selected = selected
                selectionLabel?.let { stateDescription = it }
            }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (selected) colors.primary.copy(alpha = 0.10f) else colors.surface)
        .then(interaction)
        .heightIn(min = 72.dp).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp)) {
            avatar()
            if (selected) Box(Modifier.align(Alignment.BottomEnd).size(18.dp)
                .background(colors.surface, CircleShape).padding(2.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.check_24px), null, Modifier.size(12.dp), tint = colors.onPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
                if (description.isNotBlank()) Text(description, Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selectionLabel != null) Text(selectionLabel, style = MaterialTheme.typography.labelMedium, color = colors.primary)
            if (problem != null) Text(problem, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (onPreview != null) SpeechPreviewButton(name, previewEnabled, onPreview)
        actions()
    }
}
