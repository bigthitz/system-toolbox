package com.system.toolbox.ui.screens

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.AppLimit
import com.system.toolbox.core.AppLimitScanner
import com.system.toolbox.core.ManagedApps
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

/** 展示用的时段说明：区分平日与周末 */
private fun windowsSummary(config: AppLimit.Config?): String {
    if (config == null) return ""
    val weekday = config.windows.joinToString(" / ")
    val weekend = config.weekendWindows ?: return weekday
    val weekendText = weekend.joinToString(" / ").ifEmpty { "不限" }
    return "平日 $weekday，周末 $weekendText"
}

/**
 * 应用限时管理页：
 * - 顶部展示当前限时状态；
 * - 刷新配置按钮；
 * - 限制应用列表（通过本工具箱安装的应用自动纳入，不可手动增删）。
 */
@Composable
fun AppLimitScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cached by remember { mutableStateOf<AppLimit.Cached?>(null) }
    var entries by remember { mutableStateOf<List<ManagedEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // 列表与状态自动刷新
    LaunchedEffect(Unit) {
        while (true) {
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
                is AppLimit.RefreshResult.Success -> toast("已更新")
                is AppLimit.RefreshResult.Fallback ->
                    if (r.cached != null) toast("暂时无法更新，已沿用当前设置")
                    else toast("暂时无法获取设置")
            }
            // 按最新设置立即生效
            AppLimitScanner.scan(context)
            cached = AppLimit.loadCached(context)
            entries = loadManagedEntries(context)
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
        StateCard(state, config)
        Spacer(Modifier.height(14.dp))

        // 刷新配置按钮
        OutlinedButton(
            onClick = { refreshNow() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (busy) "正在刷新…" else "刷新设置")
        }

        Spacer(Modifier.height(14.dp))

        // 限制应用列表
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    "限制应用（${entries.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "通过本工具箱安装的应用会自动加入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (entries.isEmpty()) {
                    Text(
                        "暂无限制应用。通过本工具箱安装的应用会自动加入。",
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
private fun StateCard(state: LimitState, config: AppLimit.Config?) {
    val summary = windowsSummary(config)
    val s = when (state) {
        LimitState.NoConfig -> StateStyle(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Schedule,
            "等待设置",
            "正在获取时间设置，期间应用可正常使用"
        )
        LimitState.Off -> StateStyle(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Schedule,
            "限时未开启",
            "应用不受限制"
        )
        LimitState.Allowed -> StateStyle(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.CheckCircle,
            "允许使用中",
            if (summary.isBlank()) "当前处于允许使用的时段" else "当前处于允许使用的时段（$summary）"
        )
        LimitState.Restricted -> StateStyle(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Block,
            "限制中",
            if (summary.isBlank()) "时段外限制应用暂停使用" else "允许使用的时段为 $summary，其余时间限制应用暂停使用"
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
        }
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
