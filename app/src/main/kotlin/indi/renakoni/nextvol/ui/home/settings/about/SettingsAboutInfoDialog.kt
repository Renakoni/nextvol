package indi.renakoni.nextvol.ui.home.settings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.ProjectLinks
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.AnnotatedText
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider

@Composable
fun SettingsAboutInfoDialog(
    onDismissRequest: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        color = colorResource(id = R.color.ic_launcher_background),
                        shape = CircleShape,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.icon_foreground),
                            contentDescription = null,
                            modifier = Modifier.scale(1.4f),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.app_name),
                            style = typography.displayMedium.copy(fontWeight = FontWeight.W700),
                            color = colorScheme.onSurface,
                        )
                        Text(
                            text = BuildConfig.APPLICATION_ID,
                            style = typography.labelMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                EditorialHairlineDivider()

                Text(
                    text = stringResource(R.string.settings_about_oss),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface,
                )
                AnnotatedText(
                    text = stringResource(
                        id = R.string.settings_about_source_code,
                        "<b><a href=\"${ProjectLinks.GITHUB}\">GitHub</a></b>",
                        "<b><a href=\"${ProjectLinks.ISSUES}\">GitHub Issues</a></b>",
                    ),
                    style = typography.labelLarge,
                )

                EditorialHairlineDivider()

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_about_version),
                        style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = "${BuildConfig.VERSION_NAME} [${BuildConfig.VERSION_CODE}]",
                        style = typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.translators),
                        style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.language_translators),
                        style = typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close), fontWeight = FontWeight.W600)
            }
        },
    )
}
