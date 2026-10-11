package indi.renakoni.nextvol

import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.update.UpdateCheckRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.work.CheckUpdateWork
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.ui.NextVolApp
import indi.renakoni.nextvol.ui.ExternalFileViewModel
import indi.renakoni.nextvol.utils.FormattingSettings
import indi.renakoni.nextvol.utils.LogUtils
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var loggerRepository: LoggerRepository
    @Inject lateinit var bookshelfRepository: BookshelfRepository

    private val externalFiles: ExternalFileViewModel by viewModels()

    @Inject lateinit var userDataRepository: UserDataRepository
    @Inject lateinit var updateCheckRepository: UpdateCheckRepository
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var sourceVerification: indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private var appLocale by mutableStateOf(
        Resources.getSystem().configuration.locales[0].let { "${it.language}-${it.country}" }
    )
    private var darkMode by mutableStateOf("FollowSystem")
    private var dynamicColor by mutableStateOf(false)
    private var lightThemeName by mutableStateOf("light_default")
    private var darkThemeName by mutableStateOf("dark_default")

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(LogUtils(applicationContext, loggerRepository))

        workManager.enqueueUniquePeriodicWork(
            "checkUpdate",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckUpdateWork>(12, TimeUnit.HOURS)
                .build()
        )
        initDefaultBookshelf()
        observeDisplaySettings()
        observeFormattingSettings()
        externalFiles.accept(intent, initial = true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { /* Android 13 + */
            if (ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        val fontSizeUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontSize.path)
        val fontLineHeightUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontLineHeight.path)
        val fontWeightUserData = userDataRepository.floatUserData(UserDataPath.Reader.FontWeigh.path)
        val textColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.TextColor.path)
        val textDarkColorUserData = userDataRepository.colorUserData(UserDataPath.Reader.TextDarkColor.path)
        setContent {
            val readerStyle by remember {
                combine(
                    fontSizeUserData.getFlowWithDefault(15f),
                    fontLineHeightUserData.getFlowWithDefault(7f),
                    fontWeightUserData.getFlowWithDefault(500f),
                    textColorUserData.getFlowWithDefault(Color.Unspecified),
                    textDarkColorUserData.getFlowWithDefault(Color.Unspecified)
                ) { fontSize, lineHeight, weight, textColor, textDarkColor ->
                    ReaderStyle(
                        fontSize = fontSize,
                        fontLineHeight = lineHeight,
                        fontWeight = weight,
                        textColor = textColor,
                        textDarkColor = textDarkColor,
                    )
                }
            }.collectAsStateWithLifecycle(initialValue = ReaderStyle(
                fontSize = 15f,
                fontLineHeight = 7f,
                fontWeight = 500f,
                textColor = Color.Unspecified,
                textDarkColor = Color.Unspecified,
            ))
            NextVolTheme(
                darkMode = darkMode,
                appLocale = appLocale,
                isDynamicColor = dynamicColor,
                lightThemeName = lightThemeName,
                darkThemeName = darkThemeName
            ) {
                NextVolApp(
                    readerStyle = readerStyle,
                    intentFlow = externalFiles.intentFlow,
                    onReaderActiveChanged = ::setReaderActive
                )
                indi.renakoni.nextvol.ui.SourceVerificationHost(sourceVerification)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalFiles.accept(intent)
    }

    private fun initDefaultBookshelf() {
        coroutineScope.launch(Dispatchers.IO) {
            if (bookshelfRepository.getAllBookshelfIds().isEmpty())
                bookshelfRepository.addBookshelf(
                    Bookshelf(
                        id = 1145140721,
                        name = getString(R.string.activity_collections),
                        sortType = BookshelfSortType.Default,
                        sortReversed = false,
                        autoCache = false,
                        systemUpdateReminder = false
                    )
                )
        }
    }

    private fun observeDisplaySettings() {
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.AppLocale.path)
                .getFlow()
                .collect { value ->
                    val locale = Resources.getSystem().configuration.locales[0]
                    val systemLocale = "${locale.language}-${locale.country}"
                    appLocale = if (value.isNullOrBlank() || value == "none") systemLocale
                    else value
                }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.DarkMode.path).getFlow().collect {
                it?.let { darkMode = it }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.LightThemeName.path).getFlow().collect {
                it?.let { lightThemeName = it }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.DarkThemeName.path).getFlow().collect {
                it?.let { darkThemeName = it }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            coroutineScope.launch(Dispatchers.IO) {
                userDataRepository.booleanUserData(UserDataPath.Settings.Display.DynamicColors.path).getFlow().collect {
                    dynamicColor = it == true
                }
            }
        }
    }

    private fun observeFormattingSettings() {
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.DateStyle.path).getFlow().collect {
                it?.let { FormattingSettings.dateFormat = it }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.booleanUserData(UserDataPath.Settings.Display.DateShowYear.path).getFlow().collect {
                it?.let { FormattingSettings.dateShowYear = it }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.stringUserData(UserDataPath.Settings.Display.DateOrder.path).getFlow().collect {
                it?.let { FormattingSettings.dateOrder = it }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            userDataRepository.booleanUserData(UserDataPath.Settings.Display.RelativeTimeStyle.path).getFlow().collect {
                it?.let { FormattingSettings.useRelativeTime = it }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun setReaderActive(active: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val mode = if (active) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (window.attributes.layoutInDisplayCutoutMode == mode) return

        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = mode
        }
    }
}
