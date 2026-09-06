package com.system.toolbox.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.snapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class ShellLineKind { CMD, OUT, INFO, ERR }

data class ShellLine(val kind: ShellLineKind, val text: String)

/**
 * 极简 Shell 执行器（进程级）。
 * 每行命令通过 /system/bin/sh 执行，stdout/stderr 合并输出。
 * 输出保存在内存列表，离开页面后仍可查看，最多保留 MAX_LINES 行。
 */
object ShellRunner {

    private const val MAX_LINES = 1500

    val lines = snapshotStateList<ShellLine>()

    var running by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var process: Process? = null
    private var killed = false

    fun run(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        if (running) {
            addLine(ShellLine(ShellLineKind.INFO, "上一条命令尚未结束，请稍候"))
            return
        }
        addLine(ShellLine(ShellLineKind.CMD, "$ $cmd"))

        val p = try {
            ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            addLine(ShellLine(ShellLineKind.ERR, e.message ?: "无法启动 shell"))
            return
        }
        process = p
        killed = false
        running = true

        scope.launch {
            var code = -1
            try {
                val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotEmpty()) addLine(ShellLine(ShellLineKind.OUT, line.trimEnd('\r')))
                    }
                } catch (_: Exception) {
                    // kill() 会中断读取
                }
                try {
                    code = p.waitFor()
                } catch (_: Exception) {
                }
            } finally {
                running = false
                try {
                    p.destroy()
                } catch (_: Exception) {
                }
                addLine(
                    ShellLine(
                        ShellLineKind.INFO,
                        if (killed) "[已终止]" else "执行完成 · 退出码 $code"
                    )
                )
            }
        }
    }

    /** 终止当前正在运行的命令。 */
    fun kill() {
        if (!running) return
        killed = true
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
    }

    fun clear() {
        lines.clear()
    }

    private fun addLine(line: ShellLine) {
        while (lines.size >= MAX_LINES) {
            lines.removeAt(0)
        }
        lines += line
    }
}
