package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import indi.renakoni.nextvol.data.setting.AbstractSettingState
import indi.renakoni.nextvol.data.update.UpdateChannel
import indi.renakoni.nextvol.data.update.UpdatePlatform
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.utils.DateFormat
import indi.renakoni.nextvol.utils.DateOrder
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope

@Stable
class SettingState(
    userDataRepository: UserDataRepository,
    coroutineScope: CoroutineScope
) : AbstractSettingState(coroutineScope) {
    val checkUpdateUserData = userDataRepository.booleanUserData(UserDataPath.Settings.App.AutoCheckUpdate.path)
    val appLocaleKeyUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.AppLocale.path)
    val statisticsUserData = userDataRepository.booleanUserData(UserDataPath.Settings.App.Statistics.path)
    val updateChannelKeyUserData = userDataRepository.stringUserData(UserDataPath.Settings.App.UpdateChannel.path)
    val distributionPlatformKeyUserData = userDataRepository.stringUserData(UserDataPath.Settings.App.DistributionPlatform.path)
    val enableSimplifiedTraditionalTransformUserData = userDataRepository.booleanUserData(
        UserDataPath.Reader.EnableSimplifiedTraditionalTransform.path)
    val dateFormatUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DateStyle.path)
    val dateShowYearUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.DateShowYear.path)
    val dateOrderUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.DateOrder.path)
    val useRelativeTimeUserData = userDataRepository.booleanUserData(UserDataPath.Settings.Display.RelativeTimeStyle.path)

    val checkUpdate by checkUpdateUserData.asState(true)
    val appLocaleKey by appLocaleKeyUserData.asState("none")
    val statistics by statisticsUserData.asState(true)
    val updateChannelKey by updateChannelKeyUserData.asState(UpdateChannel.default.key)
    val distributionPlatformKey by distributionPlatformKeyUserData.asState(UpdatePlatform.default.key)
    val enableSimplifiedTraditionalTransform by enableSimplifiedTraditionalTransformUserData.safeAsState(false)
    val dateFormat by dateFormatUserData.safeAsState(DateFormat.NUMERIC.key)
    val dateShowYear by dateShowYearUserData.asState(true)
    val dateOrder by dateOrderUserData.safeAsState(DateOrder.AUTO.key)
    val useRelativeTime by useRelativeTimeUserData.asState(true)
}
