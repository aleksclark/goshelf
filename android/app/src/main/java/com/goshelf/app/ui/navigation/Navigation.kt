package com.goshelf.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.goshelf.app.ui.auth.LoginScreen
import com.goshelf.app.ui.bookdetail.BookDetailScreen
import com.goshelf.app.ui.library.LibraryScreen
import com.goshelf.app.ui.library.LibraryViewModel
import com.goshelf.app.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Library : Screen("library")
    object BookDetail : Screen("book/{bookId}") {
        fun createRoute(bookId: Int) = "book/$bookId"
    }
    object Settings : Screen("settings")
}

@Composable
fun GoShelfNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Login.route
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Library.route) {
            val viewModel: LibraryViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            // Handle session expiration - navigate back to login
            LaunchedEffect(uiState.sessionExpired) {
                if (uiState.sessionExpired) {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }

            LibraryScreen(
                onBookClick = { bookId ->
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                viewModel = viewModel
            )
        }

        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.IntType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getInt("bookId") ?: return@composable
            BookDetailScreen(
                bookId = bookId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
