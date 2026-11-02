package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

internal data class VoiceRowManagement(
    val checked: Boolean,
    val selectable: Boolean,
    val onToggle: () -> Unit,
    val dragHandle: Modifier,
)

@Composable
internal fun SpeechVoiceManagementBar(hasVoices: Boolean, hasSelection: Boolean, hasVisibleSelection: Boolean,
    busy: Boolean, onAll: () -> Unit, onNone: () -> Unit, onInverse: () -> Unit, onDelete: () -> Unit,
    onAddToPool: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ManagementAction(R.drawable.select_all_24px, R.string.tts_manage_select_all, "voice-select-all",
                hasVoices && !busy, onAll)
            ManagementAction(R.drawable.deselect_24px, R.string.tts_manage_select_none, "voice-select-none",
                hasSelection && !busy, onNone)
            ManagementAction(R.drawable.select_inverse_24px, R.string.tts_manage_select_inverse, "voice-select-inverse",
                hasVoices && !busy, onInverse)
            onAddToPool?.let { add ->
                ManagementAction(R.drawable.person_edit_24px, R.string.tts_pool_join, "voice-add-to-pool",
                    hasVisibleSelection && !busy, add)
            }
            ManagementAction(R.drawable.delete_forever_24px, R.string.tts_manage_delete, "voice-delete-selected",
                hasVisibleSelection && !busy, onDelete, destructive = true)
        }
    }
}

@Composable
private fun RowScope.ManagementAction(icon: Int, label: Int, tag: String, enabled: Boolean,
    onClick: () -> Unit, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val color = if (destructive && enabled) colors.onErrorContainer else colors.onSurfaceVariant
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).testTag(tag),
        shape = RoundedCornerShape(12.dp),
        color = if (destructive && enabled) colors.errorContainer else colors.surfaceContainer,
        contentColor = if (enabled) color else colors.onSurface.copy(alpha = 0.38f)) {
        Column(Modifier.heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}
