package indi.renakoni.nextvol.ui.home.settings.formats

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.SettingState
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.data.SettingsMenuOptions
import indi.renakoni.nextvol.utils.DateFormat
import indi.renakoni.nextvol.utils.DateOrder
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.formTime
import indi.renakoni.nextvol.utils.navigationBarSpacer
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatsScreen(
    settingState: SettingState,
    onClickBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.settings_formats),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SettingsList(settingState) }
            navigationBarSpacer()
        }
    }
}

@Composable
fun SettingsList(settingState: SettingState) {
    val exampleDate = LocalDate.of(2026, 3, 10)
    val exampleRelativeTime = remember { LocalDateTime.now().minusMinutes(90) }
    val dateFormat = DateFormat.fromString(settingState.dateFormat)
    val dateOrder = DateOrder.fromString(settingState.dateOrder)

    val dateDescription = exampleDate.format(dateFormatter(dateFormat, settingState.dateShowYear, dateOrder))
    val relativeDescription = formTime(
        exampleRelativeTime,
        dateFormat,
        settingState.useRelativeTime,
    )

    SettingsCategory(
        title = stringResource(R.string.date_time_formats_settings),
    ) {
        SettingsMenuEntry(
            title = stringResource(R.string.settings_formats_date_format),
            description = dateDescription,
            options = SettingsMenuOptions.DateFormatOptions,
            selectedOptionKey = settingState.dateFormat,
            stringUserData = settingState.dateFormatUserData,
        )
        SettingsSwitchEntry(
            title = stringResource(R.string.settings_formats_date_show_year),
            description = stringResource(R.string.settings_formats_date_show_year_desc),
            checked = settingState.dateShowYear,
            booleanUserData = settingState.dateShowYearUserData,
        )
        SettingsMenuEntry(
            title = stringResource(R.string.settings_formats_date_order),
            description = stringResource(R.string.settings_formats_date_order_desc),
            options = SettingsMenuOptions.DateOrderOptions,
            selectedOptionKey = settingState.dateOrder,
            stringUserData = settingState.dateOrderUserData,
            disabled = dateFormat == DateFormat.WRITTEN,
        )
        SettingsSwitchEntry(
            title = stringResource(R.string.settings_formats_relative_time_format),
            description = relativeDescription,
            checked = settingState.useRelativeTime,
            booleanUserData = settingState.useRelativeTimeUserData,
        )
    }
}
