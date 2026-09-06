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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.system.toolbox.core.ShellLine
import com.system.toolbox.core.ShellLineKind
import com.system.toolbox.core.ShellRunner

private val QuickCommands = listOf(
    "whoami",
    "id",
    "uptime",
    "df -h",
    "free -m",
    "getprop ro.build.version.release",
    "wm size",
    "pm list packages -3",
    "ps -A -o PID,NAME | head -20",
    "settings get global device_provisioned",
    "dumpsys battery | head -20",
    "ls -la /sdcard"
)

/**
 * Shell 命令行。
 * 以系统 shell 身份执行命令（本工具为系统级应用），stdout/stderr 实时滚动展示。
 */
@Composable
fun ShellScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun execute() {
        if (input.isBlank()) return
        ShellRunner.run(input)
        input = ""
    }

    // 新行追加时自动滚动到底部
    val lineCount = ShellRunner.lines.size
    LaunchedEffect(lineCount) {
        if (lineCount > 0) {
            listState.animateScrollToItem(lineCount - 1)
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
                "Shell 命令行",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (ShellRunner.running) {
                IconButton(onClick = { ShellRunner.kill() }) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            IconButton(onClick = { ShellRunner.clear() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "清屏")
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

        // 常用命令快捷输入
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(QuickCommands) { cmd ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        cmd,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { ShellRunner.run(cmd) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 运行状态条
        if (ShellRunner.running) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "命令执行中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 输出区
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFF0D1117),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (ShellRunner.lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "输入命令后按回车执行\n（以系统身份运行，注意命令安全性）",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    itemsIndexed(ShellRunner.lines, key = { index, _ -> index }) { _, line ->
                        ShellOutputLine(line)
                    }
                }
            }
        }

        // 输入行
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("例如：pm list packages -3") },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { execute() })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { execute() },
                enabled = !ShellRunner.running
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "执行",
                    tint = if (ShellRunner.running) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ShellOutputLine(line: ShellLine) {
    val color = when (line.kind) {
        ShellLineKind.CMD -> Color(0xFF7EE787)
        ShellLineKind.OUT -> Color(0xFFE6EDF3)
        ShellLineKind.INFO -> Color(0xFF8B949E)
        ShellLineKind.ERR -> Color(0xFFF85149)
    }
    Text(
        line.text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
