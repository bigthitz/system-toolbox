package com.system.toolbox.ui.screens

import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.SilentInstaller
import com.system.toolbox.core.SystemPm

@Composable
fun HomeScreen(
    onOpenApps: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    val isSystemProcess = remember { SystemPm.isSystemProcess() }
    var installing by remember { mutableStateOf(false) }

    fun startInstall(uri: Uri) {
        installing = true
        SilentInstaller.install(context, uri) { result ->
            installing = false
            if (result.ok) {
                toast("安装成功：${result.packageName ?: "完成"}")
            } else {
                toast(result.message)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            toast("已取消选择")
        } else {
            startInstall(uri)
        }
    }

    fun launchPicker() {
        if (installing) return
        picker.launch(
            arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "系统工具箱",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "静默安装 · 应用冻结 · 系统级工具",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (!isSystemProcess) {
            EnvironmentWarning()
            Spacer(Modifier.height(16.dp))
        }

        if (installing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在静默安装，请稍候…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = "核心功能",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        ModuleCard(
            title = "静默安装",
            description = "选择本地 APK，跳过安装界面直接安装；支持覆盖安装与降级替换。",
            icon = Icons.Filled.Build,
            actionLabel = "选择 APK 安装",
            actionIcon = Icons.Filled.Add,
            enabled = !installing,
            onAction = { launchPicker() }
        )
        Spacer(Modifier.height(14.dp))

        ModuleCard(
            title = "应用冻结",
            description = "冻结不常用的应用，冻结后不再启动、不留后台；需要时一键解冻。",
            icon = Icons.Filled.AcUnit,
            actionLabel = "管理已装应用",
            actionIcon = Icons.Filled.VisibilityOff,
            enabled = true,
            onAction = onOpenApps
        )

        Spacer(Modifier.height(28.dp))
        Text(
            text = "更多能力 · 规划中",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "以下模块已预留入口槽位，后续版本逐步开放",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        val upcoming = listOf(
            PlaceholderItem("卸载应用", Icons.Filled.Delete),
            PlaceholderItem("应用备份", Icons.Filled.Backup),
            PlaceholderItem("隐藏图标", Icons.Filled.VisibilityOff),
            PlaceholderItem("清理加速", Icons.Filled.FlashOn)
        )
        upcoming.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { item ->
                    Box(Modifier.weight(1f)) {
                        PlaceholderCell(item = item, toast = toast)
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun EnvironmentWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "当前 UID=${Process.myUid()}，并非 system 身份。\n请使用系统证书签名，或将 APK 放入 /system/priv-app。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private data class PlaceholderItem(
    val title: String,
    val icon: ImageVector
)

@Composable
private fun PlaceholderCell(item: PlaceholderItem, toast: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clickable { toast("「${item.title}」模块开发中，已预留扩展槽位") }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ModuleCard(
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String,
    actionIcon: ImageVector,
    enabled: Boolean,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, enabled = enabled) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}
