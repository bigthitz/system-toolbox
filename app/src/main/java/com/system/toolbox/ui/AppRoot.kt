package com.system.toolbox.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    // 启动公告（纯文本）
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val text = fetchNotice()
        if (!text.isNullOrBlank()) notice = text
    }

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
            composable(Routes.About) {
                AboutScreen()
            }
        }
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text("公告") },
            text = {
                Text(
                    text = text,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

/** 拉取纯文本公告；https 失败自动尝试 http，均失败返回 null（不打扰用户） */
private suspend fun fetchNotice(): String? = withContext(Dispatchers.IO) {
    for (url in listOf("https://eebbk.de5.net/gonggao.php", "http://eebbk.de5.net/gonggao.php")) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SystemToolbox/1.0")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) return@withContext trimmed
        } catch (_: Exception) {
            // 换下一个地址重试
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
    null
}
