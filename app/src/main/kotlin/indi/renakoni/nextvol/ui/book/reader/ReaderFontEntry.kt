package indi.renakoni.nextvol.ui.book.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderFontEntry(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                importReaderFont(context, uri, settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                textToast(context, R.string.font_file_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val imported by produceState(emptyList<File>(), settings.fontFamilyUri) {
        value = withContext(Dispatchers.IO) { importedReaderFonts(context, settings.fontFamilyUri) }
    }
    val selectedUri = settings.fontFamilyUri
    val listState = rememberLazyListState()
    LaunchedEffect(selectedUri, imported) {
        val builtin = ReaderFont.entries.indexOfFirst { it.uri == selectedUri }
        val custom = imported.indexOfFirst { it.toUri() == selectedUri }
        val index = if (builtin >= 0) builtin else if (custom >= 0) ReaderFont.entries.size + custom else -1
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none {
                it.index == index && it.offset >= listState.layoutInfo.viewportStartOffset &&
                    it.offset + it.size <= listState.layoutInfo.viewportEndOffset
            }) {
            // Apply the new font collection and its selected position in the same remeasure.
            listState.requestScrollToItem(index)
        }
    }
    val select: (Uri) -> Unit = { settings.fontFamilyUriUserData.asynchronousSet(it) }
    Column(modifier.padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.settings_theme_text_font),
            )
            TextButton(onClick = { picker.launch("*/*") }) {
                Text(stringResource(R.string.reader_font_import))
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().selectableGroup().testTag("reader-font-list"),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ReaderFont.entries, key = { it.name }) { font ->
                ReaderFontCard(stringResource(font.title), font.uri, selectedUri, select)
            }
            items(imported, key = { it.path }) { file ->
                val title = if (file.parentFile?.name == "reader-fonts") file.name.substringAfter("--")
                    else stringResource(R.string.reader_font_imported)
                ReaderFontCard(title, file.toUri(), selectedUri, select)
            }
        }
    }
}

@Composable
private fun ReaderFontCard(title: String, uri: Uri, selected: Uri, onSelect: (Uri) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = selected == uri
    val shape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current
    val family by produceState<FontFamily>(FontFamily.Default, uri) {
        value = withContext(Dispatchers.IO) { loadReaderFontFamilySafe(uri) ?: FontFamily.Default }
    }
    Column(
        Modifier.width(112.dp * density.fontScale.coerceAtLeast(1f)).testTag("reader-font-$uri").clip(shape)
            .background(if (active) colors.secondaryContainer else colors.surfaceContainerHigh)
            .border(1.dp, if (active) colors.primary else colors.outlineVariant, shape)
            .selectable(active, role = Role.RadioButton) { onSelect(uri) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.reader_font_sample),
            modifier = Modifier.paddingFromBaseline(top = with(density) { 28.sp.toDp() }, bottom = with(density) { 10.sp.toDp() }),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = family, fontSize = 24.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)),
            maxLines = 1, color = if (active) colors.onSecondaryContainer else colors.onSurface)
        Text(title, style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Validate before publishing the file in the reusable font collection. */
internal suspend fun importReaderFont(context: Context, uri: Uri, settings: ReaderSettingsEditor) = withContext(Dispatchers.IO) {
    val temporary = File.createTempFile("reader-font-", ".tmp", context.cacheDir)
    var published: File? = null
    var committed = false
    try {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "font.ttf"
        val safeName = name.replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_").take(80)
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            temporary.outputStream().use { input.copyTo(it) }
        }
        loadReaderTypeface(temporary)
        currentCoroutineContext().ensureActive()
        val directory = File(context.filesDir, "reader-fonts").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}--$safeName")
        // Commit the validated file and selection together across coroutine cancellation.
        withContext(NonCancellable) {
            check(temporary.renameTo(file))
            published = file
            settings.fontFamilyUriUserData.set(file.toUri())
            committed = true
        }
    } finally {
        temporary.delete()
        if (!committed) published?.delete()
    }
}

/** Keep imported fonts selectable across switches and process restarts, including the old single-file location. */
internal fun importedReaderFonts(context: Context, selected: Uri): List<File> {
    val fonts = File(context.filesDir, "reader-fonts").listFiles().orEmpty().filter { it.isFile }
    val legacy = context.filesDir.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("reader-font-") }
    val selectedFile = selected.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.isFile }
    return (fonts + legacy + listOfNotNull(selectedFile)).distinctBy { it.path }.sortedBy { it.name.substringAfter("--") }
}

@Composable
internal fun ReaderFontLicensesEntry() {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    SettingsClickableEntry(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.text_fields_24px),
        title = stringResource(R.string.reader_font_licenses),
        onClick = { showLicenses = true },
    )
    if (showLicenses) {
        val license = remember {
            listOf("source-han-serif.txt", "lxgw-wenkai.txt").joinToString("\n\n") { name ->
                context.assets.open("font-licenses/$name").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.reader_font_licenses)) },
            text = { Text(license, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.confirm)) } },
        )
    }
}
