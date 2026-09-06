package com.system.toolbox.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.system.toolbox.ui.screens.AboutScreen
import com.system.toolbox.ui.screens.AppsScreen
import com.system.toolbox.ui.screens.FunctionsScreen
import com.system.toolbox.ui.screens.InstallScreen
import com.system.toolbox.ui.screens.StoreScreen
import kotlinx.coroutines.launch

private object Routes {
    const val Functions = "functions"
    const val Install = "install"
    const val Freeze = "freeze"
    const val Store = "store"
    const val About = "about"
}

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Functions(Routes.Functions, "功能", Icons.Filled.Apps),
    About(Routes.About, "关于", Icons.Filled.Info),
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    fun goTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val tabRoutes = Destination.entries.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute in tabRoutes) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { goTab(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Functions,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(260)) },
            exitTransition = { fadeOut(animationSpec = tween(180)) }
        ) {
            composable(Routes.Functions) {
                FunctionsScreen(
                    onOpenInstall = { navController.navigate(Routes.Install) },
                    onOpenFreeze = { navController.navigate(Routes.Freeze) },
                    onOpenStore = { navController.navigate(Routes.Store) }
                )
            }
            composable(Routes.Install) {
                InstallScreen(
                    onBack = { navController.popBackStack() },
                    toast = toast
                )
            }
            composable(Routes.Freeze) {
                AppsScreen(
                    onBack = { navController.popBackStack() },
                    toast = toast
                )
            }
            composable(Routes.Store) {
                StoreScreen(
                    onBack = { navController.popBackStack() },
                    toast = toast
                )
            }
            composable(Routes.About) {
                AboutScreen()
            }
        }
    }
}
