package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hnovel.content.LoginForm
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem

/** Renders validated source fields. Scripts and account lifetime remain outside Compose. */
@Composable
internal fun SourceLoginDialog(
    form: LoginForm,
    busy: Boolean,
    onSubmit: (Map<String, String>, String?, String, Boolean) -> Unit,
    onCancel: () -> Unit,
    message: String? = null,
) {
    val tokens = NextVolDesign.tokens
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form.values) } }
    AlertDialog(
        onDismissRequest = onCancel,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = { Text(stringResource(R.string.sources_login)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (form.browserUrl != null) {
                        Text(
                            text = stringResource(R.string.sources_browser_login),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    form.fields.forEach { field ->
                        key(field.id) {
                            fun change(value: String) {
                                values[field.name] = value
                                if (field.action != null) onSubmit(values.toMap(), field.id, form.id, false)
                            }
                            val onLongClick: (() -> Unit)? = if (field.action == null) null else {
                                { onSubmit(values.toMap(), field.id, form.id, true) }
                            }
                            when (field.type) {
                                "button" -> SourceLoginButton(
                                    label = field.label,
                                    enabled = !busy && field.enabled,
                                    onClick = { onSubmit(values.toMap(), field.id, form.id, false) },
                                    onLongClick = onLongClick,
                                )
                                "toggle" -> SourceLoginButton(
                                    label = "${field.label}: ${values[field.name].orEmpty()}",
                                    enabled = !busy && field.enabled,
                                    onClick = {
                                        change(field.choices[(field.choices.indexOf(values[field.name]) + 1) % field.choices.size])
                                    },
                                    onLongClick = onLongClick,
                                )
                                "select" -> {
                                    var expanded by remember(field.name) { mutableStateOf(false) }
                                    Box {
                                        SourceLoginButton(
                                            label = "${field.label}: ${values[field.name].orEmpty()}",
                                            enabled = !busy && field.enabled,
                                            onClick = { expanded = true },
                                            onLongClick = onLongClick,
                                        )
                                        EditorialPopupMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                        ) {
                                            EditorialPopupMenuHeader(text = field.label)
                                            field.choices.forEach { choice ->
                                                EditorialPopupMenuItem(
                                                    text = choice,
                                                    selected = choice == values[field.name],
                                                    enabled = !busy && field.enabled,
                                                    onClick = {
                                                        expanded = false
                                                        change(choice)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    OutlinedTextField(
                                        value = values[field.name].orEmpty(),
                                        onValueChange = { values[field.name] = it.take(4096) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        label = { Text(field.label) },
                                        enabled = !busy && field.enabled,
                                        visualTransformation = if (field.type == "password") {
                                            PasswordVisualTransformation()
                                        } else {
                                            VisualTransformation.None
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text,
                                        ),
                                    )
                                    if (field.action != null) {
                                        TextButton(
                                            onClick = { onSubmit(values.toMap(), field.id, form.id, false) },
                                            enabled = !busy && field.enabled,
                                        ) {
                                            Text(stringResource(R.string.sources_apply_field))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(values.toMap(), null, form.id, false) },
                enabled = !busy,
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.sources_login))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/** One gesture handler owns both actions, so releasing a long press cannot also submit a click. */
@Composable
private fun SourceLoginButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val colors = ButtonDefaults.outlinedButtonColors()
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = ButtonDefaults.outlinedButtonBorder(enabled),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight)
                .padding(ButtonDefaults.ContentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
