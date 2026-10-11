package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope

@Stable
class SettingState(
    userDataRepository: UserDataRepository,
    coroutineScope: CoroutineScope
) : AbstractSettingState(coroutineScope) {
    val checkUpdateUserData = userDataRepository.booleanUserData(UserDataPath.Settings.App.AutoCheckUpdate.path)
    val appLocaleKeyUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.AppLocale.path)
    val enableSimplifiedTraditionalTransformUserData = userDataRepository.booleanUserData(
        UserDataPath.Reader.EnableSimplifiedTraditionalTransform.path)
    val dateFormatUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DateStyle.path)
    val dateShowYearUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.DateShowYear.path)
    val dateOrderUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DateOrder.path)
    val useRelativeTimeUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.RelativeTimeStyle.path)

    val checkUpdate by checkUpdateUserData.asState(true)
    val appLocaleKey by appLocaleKeyUserData.asState("none")
    val enableSimplifiedTraditionalTransform by enableSimplifiedTraditionalTransformUserData.safeAsState(false)
    val dateFormat by dateFormatUserData.safeAsState("numeric")
    val dateShowYear by dateShowYearUserData.asState(true)
    val dateOrder by dateOrderUserData.safeAsState("auto")
    val useRelativeTime by useRelativeTimeUserData.asState(true)
}
