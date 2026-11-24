package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.theme.NextVolDesign
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
fun EmptyPage(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    description: String,
    content: @Composable (() -> Unit)? = null
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .squircleBackground(
                        color = tokens.cardSurface,
                        cornerRadius = 28.dp,
                    )
                    .squircleBorder(
                        width = 0.85.dp,
                        color = tokens.cardBorder,
                        cornerRadius = 28.dp,
                    )
                    .squircleClip(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .squircleBackground(
                            color = tokens.iconBadgeContainer,
                            cornerRadius = 20.dp,
                        )
                        .squircleClip(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modifier = Modifier.size(32.dp),
                        painter = icon,
                        tint = colors.primary,
                        contentDescription = null,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Balanced),
                ),
                color = colors.onSurfaceVariant,
            )
            content?.let {
                Spacer(Modifier.height(20.dp))
                it()
            }
        }
    }
}
