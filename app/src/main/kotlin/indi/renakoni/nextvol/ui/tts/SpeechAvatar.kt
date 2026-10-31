package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import indi.renakoni.nextvol.R
import java.io.File

@Composable
internal fun SpeechAvatar(avatar: String, gender: Int, modifier: Modifier = Modifier, packageAvatar: String? = null,
    contentDescription: String? = null) {
    val fallback = painterResource(if (gender == 1) R.drawable.speech_avatar_male else R.drawable.speech_avatar_female)
    val model = if (avatar.startsWith("content://")) avatar else packageAvatar?.takeIf { avatar.isNotBlank() }?.let(::File)
    key(gender) {
        AsyncImage(model, contentDescription, modifier.clip(CircleShape), placeholder = fallback, error = fallback,
            fallback = fallback, contentScale = ContentScale.Crop)
    }
}
