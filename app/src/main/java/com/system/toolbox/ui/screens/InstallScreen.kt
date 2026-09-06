package com.system.toolbox.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.SilentInstaller
import kotlinx.coroutines.launch

private enum class TaskState { WAITING, INSTALLING, SUCCESS, FAILED }

private data class InstallTask(
    val uri: Uri,
    val name: String,
    val state: TaskState = TaskState.WAITING,
    val note: String? = null
)

@Composable
fun InstallScreen(
    onBack: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tasks by remember { mutableStateOf<List<InstallTask>>(emptyList()) }
    var processing by remember { mutableStateOf(false) }

    val doneCount = tasks.count { it.state == TaskState.SUCCESS }
    val failCount = tasks.count { it.state == TaskState.FAILED }
    val installingIndex = tasks.indexOfFirst { it.state == TaskState.INSTALLING }
    val total = tasks.size

    fun taskDisplayName(uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "已选文件"
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) {
            toast("已取消选择")
            return
        }
        val fresh = uris.map { InstallTask(uri = it, name = taskDisplayName(it)) }
        tasks = tasks + fresh
        if (!processing) {
            processing = true
            scope.launch {
                var index = 0
                while (index < tasks.size) {
                    val current = tasks.getOrNull(index) ?: break
                    if (current.state == TaskState.WAITING) {
                        tasks = tasks.mapIndexed { i, t ->
                            if (i == index) t.copy(state = TaskState.INSTALLING) else t
                        }
                        val result = SilentInstaller.installAndWait(context, current.uri, index)
                        tasks = tasks.mapIndexed { i, t ->
                            if (i == index) {
                                t.copy(
                                    state = if (result.ok) TaskState.SUCCESS else TaskState.FAILED,
                                    note = result.message
                                )
                            } else {
                                t
                            }
                        }
                    }
                    index++
                }
                processing = false
            }
        }
    }

    fun clearFinished() {
        tasks = tasks.filter {
            it.state == TaskState.WAITING || it.state == TaskState.INSTALLING
        }
    }

    fun clearAll() {
        if (!processing) tasks = emptyList()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addFiles(uris) }

    fun launchPicker() {
        if (processing) return
        picker.launch(arrayOf("*/*"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
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
                text = "应用安装",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        // 主操作卡片
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { launchPicker() },
                    enabled = !processing
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (tasks.isEmpty()) "选择文件（可多选）" else "继续添加文件")
                }
                if (tasks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            if (tasks.all { it.state == TaskState.SUCCESS || it.state == TaskState.FAILED }) {
                                clearAll()
                            } else {
                                clearFinished()
                            }
                        },
                        enabled = !processing || doneCount + failCount > 0
                    ) {
                        Text(if (processing) "移除已完成项" else "清空列表")
                    }
                }
            }
        }

        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = if (processing && installingIndex >= 0) {
                        "正在安装 ${installingIndex + 1}/$total …"
                    } else {
                        "共 $total 个 · 成功 $doneCount · 失败 $failCount"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            if (processing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }

            Spacer(Modifier.height(10.dp))

            tasks.forEachIndexed { index, task ->
                TaskRow(task = task)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TaskRow(task: InstallTask) {
    val (bg, tint) = when (task.state) {
        TaskState.WAITING -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
        TaskState.INSTALLING -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.primary
        TaskState.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        TaskState.FAILED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                when (task.state) {
                    TaskState.WAITING -> Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    TaskState.INSTALLING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = tint
                    )
                    TaskState.SUCCESS -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    TaskState.FAILED -> Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                if (task.note != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = task.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
