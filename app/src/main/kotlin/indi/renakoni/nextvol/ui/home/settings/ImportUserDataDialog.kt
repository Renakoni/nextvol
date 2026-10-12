package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import kotlinx.coroutines.delay

@Composable
fun ImportUserDataDialog(
    isImporting: Boolean,
    onDismissRequest: () -> Unit,
    onClickMerge: () -> Unit,
    onClickOverwrite: () -> Unit,
) {

    var confirmingOverwrite by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }

    LaunchedEffect(confirmingOverwrite) {
        if (confirmingOverwrite) {
            countdown = 3
            repeat(3) {
                delay(1000)
                countdown--
            }
        }
    }

    AlertDialog(
        onDismissRequest = if (isImporting) ({}) else onDismissRequest,
        icon = {
            Icon(
                painter = painterResource(R.drawable.input_24px),
                contentDescription = null,
                tint = colorScheme.secondary
            )
        },
        title = {
            Text(
                text = stringResource(
                    if (isImporting) R.string.import_in_progress_title
                    else R.string.import_data_dialog_title
                ),
                style = typography.headlineSmall
            )
        },
        text = {
            if (isImporting) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.import_in_progress_desc),
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.import_data_dialog_description),
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(stringResource(R.string.local_file_backup_scope), style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant)
                    ImportOptionTile(
                        icon = R.drawable.alt_route_24px,
                        title = stringResource(R.string.import_merge),
                        desc = stringResource(R.string.import_merge_desc),
                        containerColor = colorScheme.secondaryContainer,
                        iconColor = colorScheme.secondary,
                        textColor = colorScheme.onSecondaryContainer,
                        onClick = onClickMerge
                    )
                    ImportOptionTile(
                        icon = R.drawable.delete_forever_24px,
                        title = stringResource(R.string.import_overwrite),
                        desc = stringResource(R.string.import_overwrite_desc),
                        containerColor = colorScheme.errorContainer,
                        iconColor = colorScheme.error,
                        textColor = colorScheme.onErrorContainer,
                        onClick = { confirmingOverwrite = true }
                    )
                    if (confirmingOverwrite) {
                        Text(
                            text = stringResource(R.string.import_overwrite_warning),
                            style = typography.bodyMedium,
                            color = colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isImporting) return@AlertDialog
            if (confirmingOverwrite) {
                Button(
                    onClick = onClickOverwrite,
                    enabled = countdown == 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error
                    )
                ) {
                    val confirmString = stringResource(R.string.import_overwrite_confirm)
                    Text(
                        if (countdown > 0) "$confirmString ($countdown)"
                        else confirmString
                    )
                }
            }
        },
        dismissButton = {
            if (isImporting) return@AlertDialog
            if (confirmingOverwrite) {
                TextButton(
                    onClick = { confirmingOverwrite = false }
                ) {
                    Text(stringResource(R.string.import_back))
                }
            } else {
                TextButton(
                    onClick = onDismissRequest
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun ImportOptionTile(
    icon: Int,
    title: String,
    desc: String,
    containerColor: Color,
    iconColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = iconColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = colorScheme.onPrimary
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = typography.titleMedium,
                    color = textColor
                )
                Text(
                    desc,
                    style = typography.bodySmall,
                    color = textColor
                )
            }
        }
    }
}
