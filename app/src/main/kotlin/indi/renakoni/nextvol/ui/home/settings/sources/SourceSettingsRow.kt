package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
internal fun SourceSettingsRow(title: String, enabled: Boolean = true, expanded: Boolean? = null,
    supporting: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)
        .alpha(if (enabled) 1f else 0.38f), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!supporting.isNullOrBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.arrow_forward_ios_24px), null,
            Modifier.size(16.dp).rotate(when (expanded) { true -> 270f; false -> 90f; null -> 0f }),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
