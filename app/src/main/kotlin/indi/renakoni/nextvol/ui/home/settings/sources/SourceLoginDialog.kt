package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** Renders validated source fields. Scripts and account lifetime remain outside Compose. */
@Composable
internal fun SourceLoginDialog(form: LoginForm, busy: Boolean,
    onSubmit: (Map<String, String>, String?, String, Boolean) -> Unit, onCancel: () -> Unit,
    title: String = stringResource(R.string.sources_login), message: String? = null,
    showLoginAction: Boolean = true) {
    val values = remember(form) { mutableStateMapOf<String, String>().apply { putAll(form.values) } }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            message?.let { Text(it) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (form.browserUrl != null) Text(stringResource(R.string.sources_browser_login))
            form.fields.forEach { field -> key(field.id) {
                fun change(value: String) {
                    values[field.name] = value
                    if (field.action != null) onSubmit(values.toMap(), field.id, form.id, false)
                }
                val onLongClick: (() -> Unit)? = if (field.action == null) null else {
                    { onSubmit(values.toMap(), field.id, form.id, true) }
                }
                when (field.type) {
                    "button" -> SourceLoginButton(field.label, !busy && field.enabled,
                        onClick = { onSubmit(values.toMap(), field.id, form.id, false) }, onLongClick = onLongClick)
                    "toggle" -> SourceLoginButton("${field.label}: ${values[field.name].orEmpty()}", !busy && field.enabled, onClick = {
                        change(field.choices[(field.choices.indexOf(values[field.name]) + 1) % field.choices.size])
                    }, onLongClick = onLongClick)
                    "select" -> {
                        var expanded by remember(field.name) { mutableStateOf(false) }
                        Box {
                            SourceLoginButton("${field.label}: ${values[field.name].orEmpty()}", !busy && field.enabled,
                                onClick = { expanded = true }, onLongClick = onLongClick)
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                field.choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, enabled = !busy && field.enabled, onClick = { expanded = false; change(choice) }) }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(values[field.name].orEmpty(), { values[field.name] = it.take(4096) },
                            label = { Text(field.label) }, enabled = !busy && field.enabled,
                            visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "password") KeyboardType.Password else KeyboardType.Text))
                        if (field.action != null) TextButton(onClick = { onSubmit(values.toMap(), field.id, form.id, false) }, enabled = !busy && field.enabled) {
                            Text(stringResource(R.string.sources_apply_field))
                        }
                    }
                }
            } }
            }
        } }, confirmButton = {
            if (showLoginAction) TextButton(onClick = { onSubmit(values.toMap(), null, form.id, false) }, enabled = !busy) { Text(stringResource(R.string.sources_login)) }
        }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(if (showLoginAction) android.R.string.cancel else android.R.string.ok)) } })
}

/** One gesture handler owns both actions, so releasing a long press cannot also submit a click. */
@Composable
private fun SourceLoginButton(label: String, enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = ButtonDefaults.outlinedButtonColors()
    Surface(shape = ButtonDefaults.shape, color = Color.Transparent,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = ButtonDefaults.outlinedButtonBorder(enabled),
        modifier = Modifier.minimumInteractiveComponentSize().clip(ButtonDefaults.shape)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight).padding(ButtonDefaults.ContentPadding),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
