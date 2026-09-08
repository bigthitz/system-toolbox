package com.system.toolbox.ui.screens

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.AppLimit
import com.system.toolbox.core.AppLimitScanner
import com.system.toolbox.core.ManagedApps
import com.system.toolbox.service.AppLimitService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 受管应用条目（列表展示用） */
private data class ManagedEntry(val pkg: String, val label: String, val suspended: Boolean)

private suspend fun loadManagedEntries(context: Context): List<ManagedEntry> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        ManagedApps.packagesByInstallSource(context)
            .map { pkg ->
                val label = try {
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkg
                }
                ManagedEntry(pkg, label, ManagedApps.isSuspended(context, pkg))
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * 应用限时管理页：
 * - 展示守护服务运行状态、云端时间配置与当前生效状态；
 * - 受管应用列表由安装来源自动识别（通过本工具箱安装的应用），不可手动增删；
 * - 手动刷新配置、立即执行一轮扫描。
 */
@Composable
fun AppLimitScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(AppLimitService.isRunning) }
    var cached by remember { mutableStateOf<AppLimit.Cached?>(null) }
    var outcome by remember { mutableStateOf(AppLimitScanner.Outcome(null, false, 0, 0, "")) }
    var entries by remember { mutableStateOf<List<ManagedEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // 状态自动刷新（每 2 秒）
    LaunchedEffect(Unit) {
        while (true) {
            running = AppLimitService.isRunning
            AppLimitService.lastOutcome?.let { outcome = it }
            cached = AppLimit.loadCached(context)
            entries = loadManagedEntries(context)
            delay(2000)
        }
    }

    fun refreshNow() {
        if (busy) return
        busy = true
        scope.launch {
            when (val r = AppLimit.refreshNow(context)) {
                is AppLimit.RefreshResult.Success ->
                    toast("配置已更新（${r.cached.config.windows.joinToString(" / ")}）")
                is AppLimit.RefreshResult.Fallback ->
                    if (r.cached != null) toast("云端不可达，已回退本地缓存") else toast("云端不可达，且本地暂无缓存")
            }
            cached = AppLimit.loadCached(context)
            busy = false
        }
    }

    fun scanNow() {
        if (busy) return
        busy = true
        scope.launch {
            val result = AppLimitScanner.scan(context)
            outcome = result
            cached = AppLimit.loadCached(context)
            entries = loadManagedEntries(context)
            toast(result.summary)
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "应用限时",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        val config = cached?.config
        val state: LimitState = when {
            config == null -> LimitState.NoConfig
            config.unrestricted -> LimitState.Off
            config.allowedNow() -> LimitState.Allowed
            else -> LimitState.Restricted
        }

        // 当前状态卡
        StateCard(state, config, running, outcome.summary)
        Spacer(Modifier.height(14.dp))

        // 服务与配置卡
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(running)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running) "守护服务运行中" else "守护服务未运行",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    if (!running) {
                        Button(onClick = { AppLimitService.start(context) }) { Text("启动") }
                    } else {
                        OutlinedButton(onClick = { scanNow() }, enabled = !busy) {
                            Text(if (busy) "执行中…" else "立即扫描")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "每 10 秒自动扫描一次；服务开机自启并保持后台存活",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))
                InfoRow("云端开关", if (config == null) "—" else if (config.enabled) "已启用" else "已关闭")
                InfoRow(
                    "允许时段",
                    if (config == null) "—"
                    else if (config.windows.isEmpty()) "未配置（不限制）"
                    else config.windows.joinToString(" / ")
                )
                val cachedSnapshot = cached
                InfoRow(
                    "配置更新",
                    if (cachedSnapshot == null) "尚未获取（联网后自动拉取）"
                    else "云端获取于 ${formatTime(cachedSnapshot.fetchedAt)}（24h 自动更新，失败回退缓存）"
                )

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { refreshNow() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "正在刷新…" else "立即刷新云端配置")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 受管应用卡
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "受管应用（${entries.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "通过本工具箱安装的应用按安装来源自动纳入管理，不可手动增删；限制时段内将暂停这些应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (entries.isEmpty()) {
                    Text(
                        "暂无受管应用。通过本工具箱安装（含应用商店）的应用会自动加入。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    entries.forEach { entry ->
                        ManagedRow(entry)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

private enum class LimitState { NoConfig, Off, Allowed, Restricted }

/** 状态卡的配色与文案 */
private data class StateStyle(
    val container: Color,
    val onContainer: Color,
    val icon: ImageVector,
    val title: String,
    val desc: String
)

@Composable
private fun StateCard(
    state: LimitState,
    config: AppLimit.Config?,
    running: Boolean,
    summary: String
) {
    val s = when (state) {
        LimitState.NoConfig -> StateStyle(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Schedule,
            "等待配置",
            "尚未获取云端时间配置，联网后自动拉取；期间不限制应用使用"
        )
        LimitState.Off -> StateStyle(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Schedule,
            "限时未启用",
            "云端开关已关闭或未配置时段，应用不受限制"
        )
        LimitState.Allowed -> StateStyle(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.CheckCircle,
            "允许使用中",
            config?.windows?.joinToString(" / ")?.let { "当前在允许时段（$it），应用可正常使用" } ?: ""
        )
        LimitState.Restricted -> StateStyle(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Block,
            "限制中",
            config?.windows?.joinToString(" / ")?.let { "可用时段为 $it，时段外受管应用已暂停" } ?: ""
        )
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = s.container,
        contentColor = s.onContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(s.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(s.desc, style = MaterialTheme.typography.bodyMedium)
            if (running && summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = s.onContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ManagedRow(entry: ManagedEntry) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    if (entry.suspended) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已暂停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    entry.pkg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

