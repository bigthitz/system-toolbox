package com.system.toolbox.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.system.toolbox.ui.screens.AboutScreen
import com.system.toolbox.ui.screens.AppsScreen
import com.system.toolbox.ui.screens.BrowserScreen
import com.system.toolbox.ui.screens.FunctionsScreen
import com.system.toolbox.ui.screens.InstallScreen
import com.system.toolbox.ui.screens.MORE_URL
import com.system.toolbox.ui.screens.MoreScreen
import com.system.toolbox.ui.screens.ShellScreen
import com.system.toolbox.ui.screens.StoreScreen
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Routes {
    const val Functions = "functions"
    const val Install = "install"
    const val Freeze = "freeze"
    const val Store = "store"
    const val Shell = "shell"
    const val Browser = "browser"
    const val More = "more"
    const val About = "about"
}

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Functions(Routes.Functions, "功能", Icons.Filled.Apps),
    More(Routes.More, "更多", Icons.Filled.MoreHoriz),
    About(Routes.About, "关于", Icons.Filled.Info),
}

/** 请求指定方法获取 HTTP 状态码；网络异常返回 null */
private fun httpStatus(url: String, method: String): Int? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "SystemToolbox/1.0")
        conn.requestMethod = method
        conn.responseCode
    } finally {
        conn.disconnect()
    }
} catch (_: Exception) {
    null
}

/** 「更多」远程页面是否可获取（HEAD 探测；服务器不支持 HEAD 时退回 GET 验证） */
private suspend fun isMorePageReachable(): Boolean = withContext(Dispatchers.IO) {
    when (val code = httpStatus(MORE_URL, "HEAD")) {
        null -> false
        in 200..399 -> true
        405 -> httpStatus(MORE_URL, "GET")?.let { it in 200..399 } ?: false
        else -> false
    }
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

    // 「更多」页为远程页面：探测获取不到时自动隐藏该入口
    var moreReachable by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        moreReachable = isMorePageReachable()
        if (!moreReachable && navController.currentDestination?.route == Routes.More) {
            navController.popBackStack()
        }
    }
    val destinations = remember(moreReachable) {
        if (moreReachable) Destination.entries
        else Destination.entries.filterNot { it == Destination.More }
    }
    val tabRoutes = destinations.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (currentRoute in tabRoutes) {
                NavigationBar {
                    destinations.forEach { destination ->
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
                    onOpenStore = { navController.navigate(Routes.Store) },
                    onOpenShell = { navController.navigate(Routes.Shell) },
                    onOpenBrowser = { navController.navigate(Routes.Browser) }
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
            composable(Routes.Shell) {
                ShellScreen(
                    onBack = { navController.popBackStack() },
                    toast = toast
                )
            }
            composable(Routes.Browser) {
                BrowserScreen(
                    onBack = { navController.popBackStack() },
                    toast = toast
                )
            }
            composable(Routes.More) {
                MoreScreen(
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
