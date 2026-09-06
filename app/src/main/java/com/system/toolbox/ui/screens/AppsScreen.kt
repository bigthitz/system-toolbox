package com.system.toolbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.AppEntry
import com.system.toolbox.core.OpResult
import com.system.toolbox.core.SystemPm
import kotlinx.coroutines.launch

/** 一键冻结预置包名（学习机内置服务类应用） */
private val BATCH_PKGS = listOf(
    "com.eebbk.bbkallowlisting",
    "com.eebbk.greensecuritymidware",
    "com.eebbk.ovumserver",
    "com.eebbk.bfc.app.bfcbehavior",
    "com.eebbk.parentsupport",
    "com.eebbk.padsecuritymanager"
)

@Composable
fun AppsScreen(
    onBack: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var busyPkg by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<AppEntry?>(null) }

    // 一键冻结
    var showBatchDialog by remember { mutableStateOf(false) }
    var batchSelection by remember { mutableStateOf(emptySet<String>()) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf("") }

    suspend fun load() {
        try {
            apps = SystemPm.loadApps(context)
        } catch (e: Exception) {
            toast("加载应用列表失败：${e.message}")
        }
    }

    LaunchedEffect(Unit) { load() }

    val all = apps ?: emptyList()
    val frozenCount = all.count { it.isFrozen }
    val shown = remember(all, query) {
        val filtered = if (query.isBlank()) all
        else all.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        // 已冻结的应用排在前面
        filtered.sortedWith(
            compareByDescending<AppEntry> { it.isFrozen }
                .thenBy { it.label.lowercase() }
        )
    }

    fun applyResult(pkg: String, frozen: Boolean, res: OpResult) {
        if (res.ok) {
            apps = all.map {
                if (it.packageName == pkg) it.copy(isFrozen = frozen) else it
            }
        }
        toast(res.message)
    }

    fun performToggle(app: AppEntry) {
        if (busyPkg != null) return
        val target = !app.isFrozen
        busyPkg = app.packageName
        scope.launch {
            val res = SystemPm.setFrozen(context, app.packageName, target)
            busyPkg = null
            applyResult(app.packageName, target, res)
        }
    }

    fun requestToggle(app: AppEntry) {
        if (busyPkg != null) return
        // 冻结系统应用前先确认，避免误操作
        if (app.isSystem && !app.isFrozen) {
            confirmTarget = app
        } else {
            performToggle(app)
        }
    }

    fun openBatchDialog() {
        val installedSet = (apps ?: emptyList()).map { it.packageName }.toSet()
        // 默认全选已安装的预置应用
        batchSelection = BATCH_PKGS.filter { it in installedSet }.toSet()
        showBatchDialog = true
    }

    fun runBatchFreeze() {
        val targets = batchSelection.toList()
        if (targets.isEmpty() || batchRunning) return
        batchRunning = true
        scope.launch {
            var ok = 0
            var fail = 0
            val succeeded = mutableSetOf<String>()
            targets.forEachIndexed { index, pkg ->
                batchProgress = "正在冻结 (${index + 1}/${targets.size})：$pkg"
                val res = SystemPm.setFrozen(context, pkg, true)
                if (res.ok) {
                    ok++
                    succeeded += pkg
                } else {
                    fail++
                }
            }
            apps = (apps ?: emptyList()).map { app ->
                if (app.packageName in succeeded) app.copy(isFrozen = true) else app
            }
            batchRunning = false
            batchProgress = ""
            showBatchDialog = false
            toast(
                if (fail == 0) "一键冻结完成：成功 $ok 个"
                else "一键冻结完成：成功 $ok 个，失败 $fail 个"
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "应用冻结",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { scope.launch { load() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新列表")
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (all.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = "共 ${all.size} 个应用 · 已冻结 $frozenCount 个",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { openBatchDialog() },
                enabled = apps != null && !batchRunning
            ) {
                Icon(Icons.Filled.AcUnit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("一键冻结")
            }
        }

        Spacer(Modifier.height(4.dp))

        if (apps == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (shown.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    text = if (query.isBlank()) "暂无应用" else "未找到匹配的应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 0.dp,
                    top = 12.dp,
                    end = 0.dp,
                    bottom = 24.dp
                )
            ) {
                items(shown, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        busy = busyPkg == app.packageName,
                        onToggle = { requestToggle(app) }
                    )
                }
            }
        }
    }

    confirmTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("确认冻结系统应用？") },
            text = {
                Text(
                    "「${app.label}」(${app.packageName}) 属于系统内置应用。\n" +
                        "冻结可能影响系统功能，确认继续吗？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTarget = null
                        performToggle(app)
                    }
                ) {
                    Text("冻结", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showBatchDialog) {
        val installedSet = all.map { it.packageName }.toSet()
        AlertDialog(
            onDismissRequest = { if (!batchRunning) showBatchDialog = false },
            icon = { Icon(Icons.Filled.AcUnit, contentDescription = null) },
            title = { Text("一键冻结") },
            text = {
                Column {
                    Text(
                        "默认勾选已安装的学习机内置应用，确认后将逐个冻结：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    BATCH_PKGS.forEach { pkg ->
                        val installed = pkg in installedSet
                        val frozenAlready =
                            all.firstOrNull { it.packageName == pkg }?.isFrozen == true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = pkg in batchSelection,
                                onCheckedChange = { checked ->
                                    batchSelection = if (checked) {
                                        batchSelection + pkg
                                    } else {
                                        batchSelection - pkg
                                    }
                                },
                                enabled = installed && !batchRunning
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                                Text(
                                    text = when {
                                        !installed -> "未安装"
                                        frozenAlready -> "已冻结"
                                        else -> "已安装"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (batchRunning) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                batchProgress,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !batchRunning && batchSelection.isNotEmpty(),
                    onClick = { runBatchFreeze() }
                ) {
                    Text("冻结所选", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !batchRunning,
                    onClick = { showBatchDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    busy: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (app.isFrozen) 0.6f else 1f)
            .clickable(enabled = !busy, onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (app.isFrozen) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.label.take(1).uppercase(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (app.isFrozen) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (app.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "系统",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (app.isFrozen) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "已冻结",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Switch(
                    checked = !app.isFrozen,
                    onCheckedChange = { onToggle() }
                )
            }
        }
    }
}

