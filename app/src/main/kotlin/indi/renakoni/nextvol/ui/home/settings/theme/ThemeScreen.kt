package indi.renakoni.nextvol.ui.home.settings.theme

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.theme.darkThemePalette
import indi.renakoni.nextvol.theme.lightThemePalette
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.LocalDarkColorScheme
import indi.renakoni.nextvol.ui.LocalLightColorScheme
import indi.renakoni.nextvol.ui.book.reader.ReaderBgImageDisplayModeOptions
import indi.renakoni.nextvol.ui.book.reader.ReaderFontEntry
import indi.renakoni.nextvol.ui.book.reader.ReaderLayoutDraft
import indi.renakoni.nextvol.ui.book.reader.ReaderLayoutPreview
import indi.renakoni.nextvol.ui.book.reader.ReaderMotionSwitch
import indi.renakoni.nextvol.ui.book.reader.ReaderPaper
import indi.renakoni.nextvol.ui.book.reader.ReaderPaperEntry
import indi.renakoni.nextvol.ui.book.reader.ReaderPaperPage
import indi.renakoni.nextvol.ui.book.reader.ReaderSettingsEditor
import indi.renakoni.nextvol.ui.book.reader.ReaderTypographyControls
import indi.renakoni.nextvol.ui.book.reader.ThemeSettingsEditor
import indi.renakoni.nextvol.ui.book.reader.readerBackgroundColor
import indi.renakoni.nextvol.ui.book.reader.readerTextColor
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.data.SettingsMenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.navigationBarSpacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    themeSettingState: ThemeSettingsEditor,
    readerSettingState: ReaderSettingsEditor,
    onClickBack: () -> Unit,
    onClickChangeTextColor: () -> Unit,
    onClickChangeBackgroundColor: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showPaper by rememberSaveable { mutableStateOf(false) }
    BackHandler(showPaper) { showPaper = false }
    if (showPaper) {
        ReaderPaperPage(readerSettingState, onBack = { showPaper = false })
        return
    }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.settings_theme),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onClickBack,
                        painter = painterResource(id = R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("theme-settings-list"),
        ) {
            item { DarkModeSettings(themeSettingState) }
            item { ThemeSettingsList(themeSettingState) }
            item {
                ReaderThemeSettingsList(
                    settingState = readerSettingState,
                    onClickChangeBackgroundColor = onClickChangeBackgroundColor,
                    onClickPaper = { showPaper = true },
                )
            }
            item { BackgroundSettings(readerSettingState, context) }
            item { ReaderTextSettings(readerSettingState, onClickChangeTextColor) }
            navigationBarSpacer()
        }
    }
}

@Composable
fun DarkModeSettings(
    settingState: ThemeSettingsEditor,
) {
    SectionHeader(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        text = stringResource(R.string.theme_settings),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DarkModeOptionCard(
            label = stringResource(R.string.key_dark_mode_disabled),
            selected = settingState.darkModeKey == "Disabled",
            onClick = { settingState.darkModeKeyUserData.asynchronousSet("Disabled") },
            modifier = Modifier.weight(1f),
        ) {
            LightThemeSettingsItem(Modifier.fillMaxSize())
        }

        DarkModeOptionCard(
            label = stringResource(R.string.key_dark_mode_enabled),
            selected = settingState.darkModeKey == "Enabled",
            onClick = { settingState.darkModeKeyUserData.asynchronousSet("Enabled") },
            modifier = Modifier.weight(1f),
        ) {
            DarkThemeSettingsItem(Modifier.fillMaxSize())
        }

        DarkModeOptionCard(
            label = stringResource(R.string.key_dark_mode_follow_system),
            selected = settingState.darkModeKey == "FollowSystem",
            onClick = { settingState.darkModeKeyUserData.asynchronousSet("FollowSystem") },
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.fillMaxSize()) {
                val shapeTop = GenericShape { size: Size, _ ->
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(0f, size.height / 2)
                    close()
                }
                val shapeBottom = GenericShape { size: Size, _ ->
                    moveTo(0f, size.height / 2)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                LightThemeSettingsItem(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { clip = true; shape = shapeTop },
                )
                DarkThemeSettingsItem(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { clip = true; shape = shapeBottom },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DarkModeOptionCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val radius = 18.dp
    Column(
        modifier = modifier
            .squircleBackground(
                color = if (selected) tokens.activePillFill else tokens.cardSurface,
                cornerRadius = radius,
            )
            .squircleBorder(
                width = if (selected) 1.5.dp else 0.75.dp,
                color = if (selected) colorScheme.primary else tokens.cardBorder,
                cornerRadius = radius,
            )
            .squircleClip(radius)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        ) {
            preview()
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.check_24px),
                        contentDescription = null,
                        tint = colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
            ),
            color = if (selected) colorScheme.primary else colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ThemeSettingsList(
    settingState: ThemeSettingsEditor,
) {
    val tokens = NextVolDesign.tokens
    val supportsDynamicColors = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColors = supportsDynamicColors && settingState.dynamicColorsKey
    SettingsCategory {
        SettingsSwitchEntry(
            painter = painterResource(R.drawable.format_color_fill_24px),
            title = stringResource(R.string.settings_theme_dynamic_colors),
            description = if (!supportsDynamicColors) {
                stringResource(R.string.settings_theme_dynamic_colors_desc_unavailable)
            } else {
                stringResource(R.string.settings_theme_dynamic_colors_desc)
            },
            checked = dynamicColors,
            booleanUserData = settingState.dynamicColorsKeyUserData,
            disabled = !supportsDynamicColors,
        )
        if (!dynamicColors) {
            val isDark = LocalAppTheme.current.isDark
            val options = if (isDark) SettingsMenuOptions.DarkThemeNameOptions else SettingsMenuOptions.LightThemeNameOptions
            val selected = if (isDark) settingState.darkThemeName else settingState.lightThemeName
            val preference = if (isDark) settingState.darkThemeNameUserData else settingState.lightThemeNameUserData
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.cardSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_palette_settings),
                    style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    options.optionList.forEach { option ->
                        PaletteOptionCard(
                            label = stringResource(option.nameId),
                            scheme = if (isDark) darkThemePalette(option.key) else lightThemePalette(option.key),
                            selected = selected == option.key,
                            onClick = { preference.asynchronousSet(option.key) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteOptionCard(
    label: String,
    scheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val radius = 14.dp
    Column(
        modifier = modifier
            .squircleBackground(
                color = if (selected) tokens.activePillFill else tokens.cardElevatedSurface,
                cornerRadius = radius,
            )
            .squircleBorder(
                width = if (selected) 1.5.dp else 0.75.dp,
                color = if (selected) colorScheme.primary else tokens.cardBorder,
                cornerRadius = radius,
            )
            .squircleClip(radius)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(scheme.background)
                .border(0.75.dp, scheme.outlineVariant, RoundedCornerShape(9.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(scheme.primary))
                Box(Modifier.size(14.dp).clip(CircleShape).background(scheme.secondaryContainer))
                Box(Modifier.size(12.dp).clip(CircleShape).background(scheme.tertiary))
            }
        }
        Text(
            text = label,
            style = typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
            ),
            color = if (selected) colorScheme.primary else colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalCoilApi::class)
@Composable
fun ReaderThemeSettingsList(
    settingState: ReaderSettingsEditor,
    onClickChangeBackgroundColor: () -> Unit,
    onClickPaper: () -> Unit,
) {
    SettingsCategory(
        title = stringResource(R.string.paper_settings),
    ) {
        ReaderPaperEntry(settingState, onClickPaper)
        ReaderMotionSwitch(settingState)
        if (ReaderPaper.fromId(settingState.paperId) != ReaderPaper.Default) return@SettingsCategory
        val context = LocalContext.current
        val snackbarHostState = LocalSnackbarHost.current
        val downloadingText by rememberUpdatedState(stringResource(R.string.paper_background_downloading))

        var lastEnabled by remember { mutableStateOf(settingState.enableBackgroundImage) }

        LaunchedEffect(settingState.enableBackgroundImage) {
            val now = settingState.enableBackgroundImage
            if (!lastEnabled && now) {
                val loader = context.imageLoader
                val key = "default_kraft_paper"
                val memHit = loader.memoryCache?.get(MemoryCache.Key(key)) != null
                val diskHit = loader.diskCache?.openSnapshot(key)?.use { true } ?: false

                if (!memHit && !diskHit) {
                    snackbarHostState.showSnackbar(downloadingText)
                    loader.enqueue(
                        ImageRequest.Builder(context)
                            .data(key)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(CachePolicy.ENABLED)
                            .memoryCacheKey(key)
                            .build(),
                    )
                }
            }
            lastEnabled = now
        }

        SettingsSwitchEntry(
            painter = painterResource(R.drawable.imagesearch_roller_24px),
            title = stringResource(R.string.settings_theme_bg_image),
            description = stringResource(R.string.settings_theme_bg_image_desc),
            checked = settingState.enableBackgroundImage,
            booleanUserData = settingState.enableBackgroundImageUserData,
        )
        if (settingState.enableBackgroundImage) {
            SettingsMenuEntry(
                title = stringResource(R.string.settings_theme_bg_display_mode),
                painter = painterResource(R.drawable.insert_page_break_24px),
                description = stringResource(R.string.settings_theme_bg_display_mode_desc),
                options = ReaderBgImageDisplayModeOptions,
                selectedOptionKey = settingState.backgroundImageDisplayMode,
                stringUserData = settingState.backgroundImageDisplayModeUserData,
            )
        } else {
            val onSecondaryContainer = colorScheme.onSecondaryContainer
            val background = colorScheme.background
            val currentBgColor = readerBackgroundColor(settingState)
            SettingsClickableEntry(
                painter = painterResource(R.drawable.colorize_24px),
                title = stringResource(R.string.settings_theme_bg_color),
                description = stringResource(R.string.settings_theme_bg_color_desc),
                onClick = onClickChangeBackgroundColor,
                trailingContent = {
                    Canvas(modifier = Modifier.size(38.dp)) {
                        drawCircle(color = onSecondaryContainer, radius = 17.dp.toPx())
                        drawCircle(color = background, radius = 15.dp.toPx())
                        drawCircle(color = currentBgColor, radius = 14.5.dp.toPx())
                    }
                },
            )
        }
    }
}

@Composable
fun ReaderTextSettings(settingState: ReaderSettingsEditor, onClickChangeTextColor: () -> Unit) {
    val onSecondaryContainer = colorScheme.onSecondaryContainer
    val background = colorScheme.background
    val currentColor = readerTextColor(settingState)

    SettingsCategory(
        title = stringResource(R.string.text_settings),
    ) {
        if (ReaderPaper.fromId(settingState.paperId) == ReaderPaper.Default) {
            SettingsClickableEntry(
                painter = painterResource(R.drawable.palette_24px),
                title = stringResource(R.string.settings_theme_text_color),
                description = stringResource(R.string.settings_theme_text_color_desc),
                onClick = onClickChangeTextColor,
                trailingContent = {
                    Canvas(modifier = Modifier.size(38.dp)) {
                        drawCircle(color = onSecondaryContainer, radius = 17.dp.toPx())
                        drawCircle(color = background, radius = 15.dp.toPx())
                        drawCircle(color = currentColor, radius = 14.5.dp.toPx())
                    }
                },
            )
        }

        ReaderFontEntry(settingState)
    }
    val draft = ReaderLayoutDraft.rememberDraft(settingState)
    ReaderLayoutPreview(
        settings = settingState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .height(160.dp)
            .border(0.75.dp, currentColor.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
        preview = draft.layout,
    )
    SettingsCategory {
        ReaderTypographyControls(settingState, draft)
    }
}

@Composable
fun BackgroundSettings(settingState: ReaderSettingsEditor, context: Context) {
    val scope = rememberCoroutineScope()

    val isCustomSelected = settingState.backgroundImageUri.toString().isNotBlank() ||
        settingState.backgroundDarkImageUri.toString().isNotBlank()
    var isDarkSelection by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val target = if (isDarkSelection) settingState.backgroundDarkImageUriUserData else settingState.backgroundImageUriUserData
        val fileName = if (isDarkSelection) "readerDarkBackgroundImage" else "readerBackgroundImage"
        scope.launch(Dispatchers.IO) {
            val file = context.filesDir.resolve(fileName).apply {
                if (exists()) delete()
                createNewFile()
            }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                FileInputStream(fd.fileDescriptor).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val fileUri = file.toUri()
            // Force reload if overwriting the same path by clearing then setting only after copy succeeds.
            target.setAndAwait(Uri.EMPTY)
            target.setAndAwait(fileUri)
        }
    }

    if (!settingState.enableBackgroundImage || ReaderPaper.fromId(settingState.paperId) != ReaderPaper.Default) return

    SettingsCategory(title = stringResource(R.string.paper_custom)) {
        BackgroundCard(
            title = stringResource(R.string.settings_theme_bg_image_built_in),
            desc = stringResource(R.string.settings_theme_bg_image_built_in_desc),
            selected = !isCustomSelected,
            onClick = {
                settingState.enableBackgroundImageUserData.asynchronousSet(true)
                settingState.backgroundImageUriUserData.asynchronousSet(Uri.EMPTY)
                settingState.backgroundDarkImageUriUserData.asynchronousSet(Uri.EMPTY)
            },
        )

        BackgroundCard(
            title = stringResource(R.string.settings_theme_bg_image_custom),
            selected = isCustomSelected,
            enabled = isCustomSelected,
            onClick = { },
            contentBelow = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(4.dp))
                    BackgroundSelectRow(
                        label = stringResource(R.string.choose_light_bg),
                        uri = settingState.backgroundImageUri,
                        previewSize = 48.dp,
                        onClick = {
                            isDarkSelection = false
                            launcher.launch("image/*")
                        },
                    )
                    BackgroundSelectRow(
                        label = stringResource(R.string.choose_dark_bg),
                        uri = settingState.backgroundDarkImageUri,
                        previewSize = 48.dp,
                        onClick = {
                            isDarkSelection = true
                            launcher.launch("image/*")
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun BackgroundSelectRow(
    label: String,
    uri: Uri,
    previewSize: Dp = 40.dp,
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.cardElevatedSurface)
            .border(0.75.dp, tokens.cardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = typography.bodyLarge, color = colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(previewSize)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (uri.toString().isNotBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(uri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward_24px),
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackgroundCard(
    title: String,
    desc: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    contentBelow: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tokens = NextVolDesign.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.cardSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = typography.headlineSmall, color = colorScheme.onSurface)
                desc?.let { Text(it, style = typography.bodyMedium, color = colorScheme.onSurfaceVariant) }
            }
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
        contentBelow?.let { it() }
    }
}

@Composable
private fun LightThemeSettingsItem(modifier: Modifier = Modifier) {
    MaterialTheme(LocalLightColorScheme.current) {
        DarkModeSettingItem(modifier)
    }
}

@Composable
private fun DarkThemeSettingsItem(modifier: Modifier = Modifier) {
    MaterialTheme(LocalDarkColorScheme.current) {
        DarkModeSettingItem(modifier)
    }
}

@Composable
private fun BasePageItem(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(color = colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color = colorScheme.background, shape = RoundedCornerShape(9.dp)),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DarkModeSettingItem(modifier: Modifier) {
    BasePageItem(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(7.dp)
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .width(28.dp)
                        .background(color = colorScheme.primary, shape = RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(6.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.9f).height(7.dp).background(colorScheme.primaryContainer, CircleShape))
                    Box(Modifier.fillMaxWidth(0.7f).height(7.dp).background(colorScheme.secondaryContainer, CircleShape))
                    Box(Modifier.fillMaxWidth(0.5f).height(7.dp).background(colorScheme.secondaryContainer, CircleShape))
                }
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.4f).height(6.dp).background(colorScheme.secondaryContainer, CircleShape))
                Box(Modifier.fillMaxWidth().height(6.dp).background(colorScheme.secondaryContainer, CircleShape))
                Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(colorScheme.inversePrimary, CircleShape))
                Box(Modifier.fillMaxWidth(0.8f).height(6.dp).background(colorScheme.tertiaryContainer, CircleShape))
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 5.dp, vertical = 6.dp)
                .height(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(30.dp).height(14.dp).background(colorScheme.primaryContainer, CircleShape))
        }
    }
}
