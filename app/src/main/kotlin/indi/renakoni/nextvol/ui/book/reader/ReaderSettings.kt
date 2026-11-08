package indi.renakoni.nextvol.ui.book.reader

import android.net.Uri
import androidx.compose.ui.graphics.Color
import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import io.nightfish.lightnovelreader.api.userdata.ColorUserData
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import kotlinx.coroutines.flow.Flow

interface ReaderFontFamilySettings {
    fun getFlow(): Flow<Uri>

    suspend fun clear()
}

internal class UserDataReaderFontFamilySettings(
    private val userData: UriUserData,
) : ReaderFontFamilySettings {
    override fun getFlow(): Flow<Uri> = userData.getFlowWithDefault(Uri.EMPTY)

    override suspend fun clear() = userData.set(Uri.EMPTY)
}

interface ReaderSettings {
    val paperId: String get() = "default"
    val reduceMotion: Boolean get() = false
    val pageLayout: String get() = ReaderPageLayoutPreference.Auto
    val fontSize: Float
    val fontLineHeight: Float
    val paragraphSpacing: Float get() = 0f
    val fontWeigh: Float
    val keepScreenOn: Boolean
    val enableHideStatusBar: Boolean
    val enableBackgroundImage: Boolean
    val backgroundImageDisplayMode: String
    val isUsingFlipPage: Boolean
    val isUsingClickFlipPage: Boolean
    val isUsingContinuousScrolling: Boolean
    val isUsingVolumeKeyFlip: Boolean
    val volumeKeyContinuousFlipInterval: Float
    val volumeKeyScrollFraction: Float
    val flipAnime: String
    val fastChapterChange: Boolean
    val batteryIndicatorDisplayMode: String
    val enableTimeIndicator: Boolean
    val enableChapterTitleIndicator: Boolean
    val enableReadingChapterProgressIndicator: Boolean
    val autoPadding: Boolean
    val topPadding: Float
    val bottomPadding: Float
    val leftPadding: Float
    val rightPadding: Float
    val textColor: Color
    val textDarkColor: Color
    val fontFamilyUri: Uri
    val backgroundColor: Color
    val backgroundDarkColor: Color
    val backgroundImageUri: Uri
    val backgroundDarkImageUri: Uri
    val backBlockMode: String
}

interface ReaderSettingsEditor : ReaderSettings {
    val paperIdUserData: StringUserData
    val reduceMotionUserData: BooleanUserData
    val fontSizeUserData: FloatUserData
    val fontLineHeightUserData: FloatUserData
    val paragraphSpacingUserData: FloatUserData
    val fontWeighUserData: FloatUserData
    val keepScreenOnUserData: BooleanUserData
    val enableHideStatusBarUserData: BooleanUserData
    val enableBackgroundImageUserData: BooleanUserData
    val backgroundImageDisplayModeUserData: StringUserData
    val isUsingFlipPageUserData: BooleanUserData
    val isUsingClickFlipPageUserData: BooleanUserData
    val isUsingContinuousScrollingUserData: BooleanUserData
    val isUsingVolumeKeyFlipUserData: BooleanUserData
    val volumeKeyContinuousFlipIntervalUserData: FloatUserData
    val volumeKeyScrollFractionUserData: FloatUserData
    val flipAnimeUserData: StringUserData
    val pageLayoutUserData: StringUserData
    val fastChapterChangeUserData: BooleanUserData
    val batteryIndicatorDisplayModeUserData: StringUserData
    val enableTimeIndicatorUserData: BooleanUserData
    val enableChapterTitleIndicatorUserData: BooleanUserData
    val enableReadingChapterProgressIndicatorUserData: BooleanUserData
    val autoPaddingUserData: BooleanUserData
    val topPaddingUserData: FloatUserData
    val bottomPaddingUserData: FloatUserData
    val leftPaddingUserData: FloatUserData
    val rightPaddingUserData: FloatUserData
    val textColorUserData: ColorUserData
    val textDarkColorUserData: ColorUserData
    val fontFamilyUriUserData: UriUserData
    val backgroundColorUserData: ColorUserData
    val backgroundDarkColorUserData: ColorUserData
    val backgroundImageUriUserData: UriUserData
    val backgroundDarkImageUriUserData: UriUserData
    val backBlockModeUserData: StringUserData
}

interface ThemeSettings {
    val darkModeKey: String
    val dynamicColorsKey: Boolean
    val lightThemeName: String
    val darkThemeName: String
}

interface ThemeSettingsEditor : ThemeSettings {
    val darkModeKeyUserData: StringUserData
    val dynamicColorsKeyUserData: BooleanUserData
    val lightThemeNameUserData: StringUserData
    val darkThemeNameUserData: StringUserData
}
