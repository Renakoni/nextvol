package indi.renakoni.nextvol.ui.home.settings.textformatting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.format.FormattingRule
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SwitchChip
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
fun EditTextFormattingRuleDialog(
    rule: FormattingRule,
    matchTextFieldValue: TextFieldValue,
    onNameChange: (String) -> Unit,
    onMatchChange: (TextFieldValue) -> Unit,
    onReplacementChange: (String) -> Unit,
    onIsRegexChange: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    LaunchedEffect(rule.isRegex) {
        onMatchChange.invoke(matchTextFieldValue)
    }
    val regexError = remember(rule.match, rule.isRegex) {
        if (!rule.isRegex) return@remember null
        try {
            Regex(rule.match)
            null
        } catch (error: Exception) {
            error.localizedMessage ?: ""
        }
    }
    val isWrong = regexError != null

    val radius = tokens.sheetRadius
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 460.dp)
                .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                .squircleClip(radius)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.edit_rule),
                style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                color = colorScheme.onSurface,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(maxHeight = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = rule.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.rule_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.controlRadius),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = matchTextFieldValue,
                    onValueChange = onMatchChange,
                    label = { Text(stringResource(R.string.rule_match)) },
                    singleLine = false,
                    isError = isWrong,
                    supportingText = if (isWrong) {
                        {
                            Text(
                                text = regexError.ifBlank { stringResource(R.string.rule_is_regex) },
                                color = colorScheme.error,
                            )
                        }
                    } else {
                        null
                    },
                    shape = RoundedCornerShape(tokens.controlRadius),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = rule.replacement,
                    onValueChange = onReplacementChange,
                    label = { Text(stringResource(R.string.rule_replacement)) },
                    singleLine = false,
                    shape = RoundedCornerShape(tokens.controlRadius),
                )
                SwitchChip(
                    label = stringResource(R.string.rule_is_regex),
                    selected = rule.isRegex,
                    onClick = { onIsRegexChange(!rule.isRegex) },
                )
            }

            EditorialHairlineDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rule.id != -1) {
                    OutlinedButton(
                        onClick = onDelete,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = stringResource(R.string.delete_rule), color = colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismissRequest,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirmation,
                    enabled = !isWrong,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.save_rule), fontWeight = FontWeight.W600)
                }
            }
        }
    }
}
