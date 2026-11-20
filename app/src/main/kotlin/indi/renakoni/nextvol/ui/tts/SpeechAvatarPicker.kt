package indi.renakoni.nextvol.ui.tts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

internal const val JPEG_AVATAR_PREFIX = "data:image/jpeg;base64,"

// Keep only the thumbnail in the voice draft. Legacy gallery apps grant temporary access to the original.
private suspend fun readAvatar(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context).data(uri).size(512, 512).scale(Scale.FIT).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
    val bitmap = (result?.image as? BitmapImage)?.bitmap ?: error("Cannot read avatar")
    val thumbnail = ThumbnailUtils.extractThumbnail(bitmap, 224, 224)
    val bytes = ByteArrayOutputStream()
    check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 76, bytes))
    val value = JPEG_AVATAR_PREFIX + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    check(value.length <= 64 * 1024)
    value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSpeechAvatarPicker(onAvatar: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onAvatar)
    var choosing by rememberSaveable { mutableStateOf(false) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<Uri?>(null) }
    fun failed() {
        choosing = false
        Toast.makeText(context, R.string.tts_voice_avatar_failed, Toast.LENGTH_SHORT).show()
    }
    fun received(uri: Uri?) {
        selected = uri
        if (uri == null) choosing = false
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::received)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        received(it.data?.data.takeIf { _ -> it.resultCode == Activity.RESULT_OK })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = capturePath?.let(::File)
        if (saved && file != null) received(Uri.fromFile(file))
        else { file?.delete(); capturePath = null; received(null) }
    }
    LaunchedEffect(selected) {
        val uri = selected ?: return@LaunchedEffect
        try { deliver(readAvatar(context, uri)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed() }
        capturePath?.let { File(it).delete() }
        capturePath = null
        selected = null
        choosing = false
    }
    if (choosing) ModalBottomSheet(
        onDismissRequest = { if (selected == null) choosing = false },
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (selected != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
        else {
            val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(colors = rowColors, modifier = Modifier.clickable {
                try {
                    val file = File.createTempFile("voice-avatar-", ".jpg", context.cacheDir)
                    capturePath = file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
                } catch (_: Exception) { capturePath?.let { File(it).delete() }; capturePath = null; failed() }
            }) { Text(stringResource(R.string.tts_avatar_camera)) }
            ListItem(colors = rowColors, modifier = Modifier.clickable {
                try {
                    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context))
                        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    else gallery.launch(Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"))
                } catch (_: Exception) { failed() }
            }) { Text(stringResource(R.string.tts_avatar_gallery)) }
        }
    }
    return { choosing = true }
}
