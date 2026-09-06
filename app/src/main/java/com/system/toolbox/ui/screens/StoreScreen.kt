package com.system.toolbox.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.StoreApi
import com.system.toolbox.core.StoreApi.StoreApp
import com.system.toolbox.core.StoreIconLoader
import com.system.toolbox.core.StoreSession
import com.system.toolbox.core.StoreTask
import com.system.toolbox.core.TaskPhase
import com.system.toolbox.core.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 应用商店：
 *  - 搜索 / 安装入口（状态与进度由进程级 StoreSession 保存，返回再进入不丢失）
 *  - 已安装的应用显示「打开」
 *  - 底部可切换到「任务列表」查看下载 / 安装队列
 */
@Composable
fun StoreScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }

    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) {
            runCatching { pm.getInstalledPackages(0).map { it.packageName }.toSet() }
                .getOrDefault(emptySet())
        }
    }

    fun openApp(pkg: String) {
        if (pkg.isEmpty()) {
            toast("该应用没有安装包名信息")
            return
        }
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("无法获取启动入口")
            return
        }
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("打开失败：${it.message}") }
    }

    // 进入页面刷新一次已装列表；任何任务阶段变化（如安装完成）后自动刷新
    LaunchedEffect(Unit) {
        refreshInstalled()
        snapshotFlow { StoreSession.tasks.map { it.phase } }
            .distinctUntilChanged()
            .collect { refreshInstalled() }
    }

    // 会话级提示（搜索无结果等）
    LaunchedEffect(StoreSession.searchNotice) {
        StoreSession.searchNotice?.let {
            toast(it)
            StoreSession.searchNotice = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "应用商店",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (StoreSession.tasks.any { it.isActive }) {
                Text(
                    "后台下载进行中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

        // 页签（搜索 / 任务）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(4.dp)
        ) {
            StoreTab(
                label = "搜索",
                selected = StoreSession.selectedTab == 0,
                modifier = Modifier.weight(1f)
            ) { StoreSession.selectedTab = 0 }
            val taskCount = StoreSession.tasks.size
            StoreTab(
                label = if (taskCount > 0) "任务($taskCount)" else "任务",
                selected = StoreSession.selectedTab == 1,
                modifier = Modifier.weight(1f)
            ) { StoreSession.selectedTab = 1 }
        }

        when (StoreSession.selectedTab) {
            0 -> SearchPage(
                toast = toast,
                installed = installed,
                onOpen = ::openApp
            )
            else -> TaskPage(
                installed = installed,
                onOpen = ::openApp,
                toast = toast
            )
        }
    }
}

@Composable
private fun StoreTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** 搜索页：搜索框 + 结果列表 */
@Composable
private fun SearchPage(
    toast: (String) -> Unit,
    installed: Set<String>,
    onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = StoreSession.keyword,
                onValueChange = { StoreSession.keyword = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索应用，如 微信、哔哩哔哩…") },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { StoreSession.search() })
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { StoreSession.search() },
                enabled = !StoreSession.searching
            ) {
                Text("搜索")
            }
        }

        val results = StoreSession.results
        when {
            StoreSession.searching && results == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在搜索…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            results == null -> {
                // 尚未搜索：给出引导，并显示仍在进行的下载任务（若有）
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("输入关键词搜索豌豆荚应用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "下载任务在后台持续进行，返回后再进入进度不会丢失",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            results.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "没有找到相关应用，请更换关键词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Text(
                    "共 ${results.size} 个结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(results, key = { it.appId }) { app ->
                        val task = StoreSession.taskOf(app.appId)
                        StoreResultCard(
                            app = app,
                            installed = installed,
                            task = task,
                            onOpen = { onOpen(app.packageName) },
                            onInstall = { StoreSession.install(app) },
                            onCancel = { task?.let { StoreSession.cancel(it.id) } },
                            onRetry = { task?.let { StoreSession.retry(it.id) } }
                        )
                    }
                }
            }
        }
    }
}

/** 结果卡片 */
@Composable
private fun StoreResultCard(
    app: StoreApp,
    installed: Set<String>,
    task: StoreTask?,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StoreIcon(app.iconUrl, app.name, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOf(
                    app.versionName.takeIf { it.isNotBlank() },
                    app.installCount.takeIf { it.isNotBlank() }
                ).filterNotNull().joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (app.description.isNotEmpty()) {
                    Text(
                        app.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                task?.message?.takeIf { task.phase == TaskPhase.FAILED }?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CardAction(
                app = app,
                installed = installed,
                task = task,
                onOpen = onOpen,
                onInstall = onInstall,
                onCancel = onCancel,
                onRetry = onRetry
            )
        }
    }
}

/** 卡片右侧动作区 */
@Composable
private fun CardAction(
    app: StoreApp,
    installed: Set<String>,
    task: StoreTask?,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val installedFlag = app.packageName.isNotEmpty() && app.packageName in installed
    Box(Modifier.width(104.dp), contentAlignment = Alignment.CenterEnd) {
        when {
            // 已安装且没有进行中的更新任务 -> 打开
            installedFlag && (task == null || task.isFinished) -> {
                Button(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("打开")
                }
            }
            task?.isActive == true -> TaskActiveMini(task, onCancel)
            task?.phase == TaskPhase.FAILED -> {
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("重试")
                    }
                }
            }
            else -> {
                Button(
                    onClick = onInstall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("安装")
                }
            }
        }
    }
}

/** 卡片右侧：排队/下载/安装中的迷你进度 */
@Composable
private fun TaskActiveMini(task: StoreTask, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (task.phase) {
            TaskPhase.QUEUED -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
                Text("排队中", style = MaterialTheme.typography.labelSmall)
            }
            TaskPhase.DOWNLOADING -> {
                val p = task.progress
                if (p != null && p > 0f) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.height(4.dp))
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(downloadDetail(task), style = MaterialTheme.typography.labelSmall)
            }
            TaskPhase.INSTALLING -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
                Text("安装中…", style = MaterialTheme.typography.labelSmall)
            }
            else -> {}
        }
        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text("取消")
        }
    }
}

/** 任务列表页 */
@Composable
private fun TaskPage(
    installed: Set<String>,
    onOpen: (String) -> Unit,
    toast: (String) -> Unit
) {
    val tasks = StoreSession.tasks
    Column(Modifier.fillMaxSize()) {
        // 概览 + 清空
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val active = tasks.count { it.isActive }
            val done = tasks.count { it.isDone }
            Text(
                if (tasks.isEmpty()) "暂无下载任务"
                else "${tasks.size} 个任务 · $active 进行中 · $done 已完成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (tasks.any { it.isFinished }) {
                TextButton(onClick = { StoreSession.clearFinished() }) {
                    Text("清空已完成")
                }
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有下载任务", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "到「搜索」页点击安装，即可在此查看进度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(tasks.asReversed(), key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        installed = installed,
                        onOpen = onOpen,
                        toast = toast
                    )
                }
            }
        }
    }
}

/** 任务行 */
@Composable
private fun TaskRow(
    task: StoreTask,
    installed: Set<String>,
    onOpen: (String) -> Unit,
    toast: (String) -> Unit
) {
    val app = task.app
    val installedFlag = app.packageName.isNotEmpty() && app.packageName in installed
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StoreIcon(app.iconUrl, app.name, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    TaskStatusBadge(task)
                }
                Text(
                    task.statusLine(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.phase == TaskPhase.DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    val p = task.progress
                    if (p != null && p > 0f) {
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            TaskActions(task, installedFlag, onOpen)
        }
    }
}

@Composable
private fun TaskStatusBadge(task: StoreTask) {
    val (text, color) = when (task.phase) {
        TaskPhase.QUEUED -> "排队中" to MaterialTheme.colorScheme.tertiary
        TaskPhase.DOWNLOADING -> "下载中" to MaterialTheme.colorScheme.primary
        TaskPhase.INSTALLING -> "安装中" to MaterialTheme.colorScheme.primary
        TaskPhase.DONE -> "已完成" to MaterialTheme.colorScheme.primary
        TaskPhase.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TaskPhase.CANCELLED -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun TaskActions(task: StoreTask, installedFlag: Boolean, onOpen: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        when (task.phase) {
            TaskPhase.QUEUED, TaskPhase.DOWNLOADING -> {
                TextButton(onClick = { StoreSession.cancel(task.id) }) {
                    Text("取消")
                }
            }
            TaskPhase.FAILED -> {
                TextButton(onClick = { StoreSession.retry(task.id) }) {
                    Text("重试")
                }
            }
            TaskPhase.DONE -> {
                if (installedFlag) {
                    Button(
                        onClick = { onOpen(task.app.packageName) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("打开")
                    }
                }
            }
            else -> {}
        }
        if (task.isFinished) {
            TextButton(onClick = { StoreSession.dismiss(task.id) }) {
                Text("移除")
            }
        }
    }
}

/** 应用图标（带缓存） */
@Composable
private fun StoreIcon(url: String?, name: String, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = StoreIconLoader.load(url)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = name,
            modifier = modifier.clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                name.take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun StoreTask.statusLine(): String = when (phase) {
    TaskPhase.QUEUED -> message
    TaskPhase.DOWNLOADING -> {
        val detail = if (total > 0L) {
            "${(progress ?: 0f).let { (it * 100).toInt().coerceIn(0, 100) }}% · " +
                "${formatBytes(downloaded)} / ${formatBytes(total)}"
        } else formatBytes(downloaded)
        "$detail · ${message}"
    }
    TaskPhase.INSTALLING -> message
    TaskPhase.DONE -> message
    TaskPhase.FAILED -> message
    TaskPhase.CANCELLED -> message
}

private fun downloadDetail(task: StoreTask): String = when {
    task.total > 0L ->
        "${(task.progress ?: 0f).let { (it * 100).toInt().coerceIn(0, 100) }}% · " +
            "${formatBytes(task.downloaded)}/${formatBytes(task.total)}"
    task.downloaded > 0L -> formatBytes(task.downloaded)
    else -> "下载中…"
}
