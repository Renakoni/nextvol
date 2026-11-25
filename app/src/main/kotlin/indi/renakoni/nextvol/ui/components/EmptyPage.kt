package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EmptyPage(
    modifier: Modifier = Modifier,
    icon: Painter,
    title: String,
    description: String,
    content: @Composable (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    modifier = Modifier.size(30.dp),
                    painter = icon,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.78f),
                    contentDescription = null,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = description,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineBreak = LineBreak.Paragraph.copy(strategy = LineBreak.Strategy.Balanced),
                    ),
                    color = colors.onSurfaceVariant,
                )
            }
            content?.let {
                Spacer(Modifier.height(18.dp))
                it()
            }
        }
    }
}
