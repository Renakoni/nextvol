package indi.renakoni.nextvol.ui.home.settings.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.AnnotatedText

@Composable
fun SettingsAboutInfoDialog(
    onDismissRequest: () -> Unit,
) {
    AlertDialog (
        onDismissRequest = onDismissRequest,
        text = {
            Column {
                Row {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        color = colorResource(id = R.color.ic_launcher_background),
                        shape = CircleShape
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.icon_foreground),
                            contentDescription = null,
                            modifier = Modifier.scale(1.4f)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {

                        Text(
                            stringResource(id = R.string.app_name),
                            style = typography.displayMedium
                        )
                        Text(
                            BuildConfig.APPLICATION_ID,
                            style = typography.labelMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = stringResource(R.string.settings_about_oss),
                    style = typography.labelLarge
                )
                Spacer(Modifier.height(10.dp))
                AnnotatedText(
                    text = stringResource(
                        id = R.string.settings_about_source_code,
                        "<b><a href=\"https://github.com/dmzz-yyhyy/LightNovelReader\">GitHub</a></b>",
                        "<b><a href=\"https://github.com/dmzz-yyhyy/LightNovelReader/issues\">GitHub Issues</a></b>"
                    ),
                    style = typography.labelLarge
                )

                Spacer(modifier = Modifier.height(18.dp))

                val titleColor = colorScheme.onSurface
                val contentColor = colorScheme.onSurfaceVariant
                Column {
                    Text(
                        stringResource(R.string.dialog_about_version), color = titleColor
                    )
                    Text(
                        "${BuildConfig.VERSION_NAME} [${BuildConfig.VERSION_CODE}]", color = contentColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.translators), color = titleColor
                    )
                    Text(
                        stringResource(R.string.language_translators), color = contentColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {},
    )
}
