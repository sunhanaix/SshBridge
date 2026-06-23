package com.sunbeat.sshclient.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.sunbeat.sshclient.di.AppContainer
import com.sunbeat.sshclient.ui.home.HomeScreen
import com.sunbeat.sshclient.ui.home.HomeViewModel
import com.sunbeat.sshclient.ui.settings.SettingsScreen
import com.sunbeat.sshclient.ui.settings.SettingsViewModel
import com.sunbeat.sshclient.ui.terminal.TerminalScreen
import com.sunbeat.sshclient.ui.terminal.TerminalViewModel

object Routes {
    const val HOME = "home"
    const val TERMINAL = "terminal/{sessionId}"
    const val SETTINGS = "settings"
    fun terminal(sessionId: Long) = "terminal/$sessionId"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    container: AppContainer,
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(
                    container.sessionRepository,
                    container.connPool,
                    container.preferences,
                    container.appContext,
                )
            )
            HomeScreen(
                viewModel = vm,
                onSessionClick = { session ->
                    navController.navigate(Routes.terminal(session.id))
                },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onImportClick = { vm.importBundledJson() },
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: return@composable
            val vm: TerminalViewModel = viewModel(
                key = "terminal_$sessionId",
                factory = TerminalViewModel.Factory(sessionId, container.sessionRepository, container.connPool, container.appContext),
            )
            TerminalScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
