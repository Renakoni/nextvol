package indi.renakoni.nextvol.ui.book.reader

import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

private const val KRAFT_PAPER_URL = "https://portal.curiousers.org/static/lnr/paper.webp"
private const val KRAFT_PAPER_CACHE_KEY = "default_kraft_paper"

internal fun loadReaderTypeface(file: File): Typeface = requireNotNull(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Typeface.Builder(file).build()
    else Typeface.createFromFile(file).takeUnless { it === Typeface.DEFAULT }
) { "Invalid reader font" }

fun loadReaderFontFamilySafe(uri: Uri): FontFamily? {
    return try {
        if (uri == Uri.EMPTY) return null
        ReaderFont.entries.firstOrNull { it.uri == uri }?.let { return it.family() }
        val fontFile = File(uri.path ?: return null)
        if (!fontFile.exists()) throw FileNotFoundException()
        FontFamily(loadReaderTypeface(fontFile))
    } catch (e: Exception) {
        Log.e("FontLoad", "Failed to load custom font", e)
        null
    }
}

@Composable
fun rememberReaderFontFamily(
    fontFamilyUriUserData: UriUserData,
): FontFamily {
    return rememberReaderFontFamily(
        fontFamilyUriFlow = fontFamilyUriUserData.getFlowWithDefault(Uri.EMPTY),
        clearInvalidFontFamily = { fontFamilyUriUserData.set(Uri.EMPTY) },
    )
}

@Composable
fun rememberReaderFontFamily(
    fontFamilySettings: ReaderFontFamilySettings,
): FontFamily {
    return rememberReaderFontFamily(
        fontFamilyUriFlow = fontFamilySettings.getFlow(),
        clearInvalidFontFamily = fontFamilySettings::clear,
    )
}

@Composable
private fun rememberReaderFontFamily(
    fontFamilyUriFlow: Flow<Uri>,
    clearInvalidFontFamily: suspend () -> Unit,
): FontFamily {
    val snackbarScope = rememberCoroutineScope()
    val uri by fontFamilyUriFlow.collectAsStateWithLifecycle(Uri.EMPTY)
    val fontFamily = remember(uri) { loadReaderFontFamilySafe(uri) }

    val snackbarHostState = LocalSnackbarHost.current
    val message = stringResource(R.string.reader_custom_font_load_failed)
    if (fontFamily == null && uri != Uri.EMPTY) {
        LaunchedEffect(uri) {
            withContext(Dispatchers.IO) { clearInvalidFontFamily() }
            snackbarScope.launch {
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    return fontFamily ?: FontFamily.Default
}

@Composable
private fun rememberPaperPainter(): Painter {
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    val fallback = remember(theme.isDark, theme.colorScheme.background) {
        ColorPainter(theme.colorScheme.background)
    }

    val request = remember {
        ImageRequest.Builder(context)
            .data(KRAFT_PAPER_URL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(KRAFT_PAPER_CACHE_KEY)
            .diskCacheKey(KRAFT_PAPER_CACHE_KEY)
            .interceptorCoroutineContext(Dispatchers.Default)
            .scale(Scale.FILL)
            .build()
    }

    // 内置牛皮纸是联网资源，加载失败时静默回退到背景色即可，不弹提示打扰用户。
    return rememberAsyncImagePainter(
        model = request,
        placeholder = fallback,
        error = fallback
    )
}

@Composable
private fun rememberCustomBackgroundPainter(
    uri: Uri,
    snackbarScope: CoroutineScope,
): Painter {
    val theme = LocalAppTheme.current
    val fallback = remember(theme.isDark, theme.colorScheme.background) {
        ColorPainter(theme.colorScheme.background)
    }
    val snackbarHostState = LocalSnackbarHost.current
    val message = stringResource(R.string.reader_custom_background_load_failed)

    val painter = rememberAsyncImagePainter(
        model = uri,
        error = fallback
    )

    var errorNotified by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(painter) {
        painter.state.collect { state ->
            when (state) {
                is AsyncImagePainter.State.Error -> {
                    if (!errorNotified) {
                        errorNotified = true
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
                is AsyncImagePainter.State.Success -> {
                    errorNotified = false
                }
                else -> Unit
            }
        }
    }

    return painter
}

@Composable
fun rememberReaderBackgroundPainter(
    settingState: ReaderSettings,
): Painter {
    val isDark = LocalAppTheme.current.isDark
    val snackbarScope = rememberCoroutineScope()

    val backgroundUri = remember(
        isDark,
        settingState.backgroundImageUri,
        settingState.backgroundDarkImageUri
    ) {
        if (isDark) settingState.backgroundDarkImageUri else settingState.backgroundImageUri
    }

    if (backgroundUri == Uri.EMPTY || backgroundUri.toString().isBlank()) {
        return rememberPaperPainter()
    }

    return rememberCustomBackgroundPainter(backgroundUri, snackbarScope)
}

@Composable
fun readerBackgroundColor(settingState: ReaderSettings): Color {
    ReaderPaper.fromId(settingState.paperId).colors?.let { return it.background }
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val background = localTheme.colorScheme.background

    val color = remember(isDark, settingState.backgroundColor, settingState.backgroundDarkColor, background) {
        when {
            isDark && settingState.backgroundDarkColor.isUnspecified -> background
            !isDark && settingState.backgroundColor.isUnspecified -> background
            isDark -> settingState.backgroundDarkColor
            else -> settingState.backgroundColor
        }
    }

    return color
}

@Composable
fun readerTextColor(settingState: ReaderSettings): Color {
    ReaderPaper.fromId(settingState.paperId).colors?.let { return it.text }
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val onSurface = localTheme.colorScheme.onSurface

    val color = remember(isDark, settingState.textColor, settingState.textDarkColor, onSurface) {
        when {
            isDark && settingState.textDarkColor.isUnspecified -> onSurface
            !isDark && settingState.textColor.isUnspecified -> onSurface
            isDark -> settingState.textDarkColor
            else -> settingState.textColor
        }
    }

    return color
}
