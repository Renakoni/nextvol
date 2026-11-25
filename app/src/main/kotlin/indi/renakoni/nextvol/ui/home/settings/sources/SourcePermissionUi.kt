package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hnovel.network.sourceOrigin
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard

/** Also validates external discovery-provider diagnostics before displaying them. */
@Composable
internal fun SourcePermissionLabel(origin: String, kind: String) {
    if (sourceOrigin(origin) != origin) return
    val tokens = NextVolDesign.tokens
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = origin,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.W500,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(tokens.activePillFill)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(
                    when (kind) {
                        "Image" -> R.string.sources_resource_image
                        "Script" -> R.string.sources_resource_script
                        "Api" -> R.string.sources_resource_api
                        "Import" -> R.string.sources_resource_import
                        else -> R.string.sources_resource_document
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun SourcePermissionCandidate(
    origin: String,
    kind: String,
    draft: String,
    busy: Boolean,
    onAdd: (String) -> Unit,
) {
    if (sourceOrigin(origin) != origin) return
    val added = draft.lineSequence().any { sourceOrigin(it.trim()) == origin }
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) {
                SourcePermissionLabel(origin, kind)
            }
            FilledTonalButton(
                onClick = { onAdd((draft.trim().takeIf(String::isNotEmpty)?.plus("\n") ?: "") + origin) },
                enabled = !busy && !added,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(if (added) R.string.sources_origin_in_draft else R.string.sources_origin_add),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
