package indi.renakoni.nextvol.ui.home.settings.textformatting

import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import indi.renakoni.nextvol.ui.home.settings.textformatting.rules.navigateToSettingsTextFormattingRulesDestination
import indi.renakoni.nextvol.ui.home.settings.textformatting.rules.settingsTextFormattingRulesDestination
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.editTextFormattingRuleDialog() {
    dialog<Route.Main.EditTextFormattingRuleDialog> {
        val navController = LocalNavController.current
        val route = it.toRoute<Route.Main.EditTextFormattingRuleDialog>()
        val viewModel = hiltViewModel<EditTextFormattingRuleDialogViewModel>()
        LaunchedEffect(route.bookId, route.ruleId) {
            viewModel.load(route.bookId, route.ruleId)
        }
        viewModel.formattingRule?.let { rule ->
            EditTextFormattingRuleDialog(
                rule = rule,
                matchTextFieldValue = viewModel.matchTextFieldValue,
                onDismissRequest = navController::popBackStack,
                onConfirmation = {
                    viewModel.onConfirmation()
                    navController.popBackStack()
                },
                onDelete = {
                    viewModel.onDelete()
                    navController.popBackStack()
                },
                onNameChange = viewModel::updateName,
                onMatchChange = viewModel::updateMatch,
                onReplacementChange = viewModel::updateReplacement,
                onIsRegexChange = viewModel::updateIsRegex
            )
        }
    }
}

fun NavController.navigateToEditTextFormattingRuleDialog(bookId: String, ruleId: Int) {
    if (!this.isResumed()) return
    navigate(Route.Main.EditTextFormattingRuleDialog(bookId, ruleId))
}

fun NavGraphBuilder.settingsTextFormattingNavigation() {
    navigation<Route.Main.Settings.TextFormatting>(
        startDestination = Route.Main.Settings.TextFormatting.Manager
    ) {
        settingsTextFormattingManagerDestination()
        settingsTextFormattingRulesDestination()
    }
}

fun NavGraphBuilder.settingsTextFormattingManagerDestination() {
    composable<Route.Main.Settings.TextFormatting.Manager> {
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<FormattingViewModel>()
        TextFormattingScreen(
            onClickBack = navController::popBackStackIfResumed,
            onClickGroup = navController::navigateToSettingsTextFormattingRulesDestination,
            groups = viewModel.formattingGroups
        )
    }
}

fun NavController.navigateToSettingsTextFormattingManagerDestination() {
    if (!this.isResumed()) return
    navigate(Route.Main.Settings.TextFormatting.Manager)
}
