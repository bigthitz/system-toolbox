package com.system.toolbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 一条已安装应用记录 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isFrozen: Boolean
)

/** 操作结果 */
data class OpResult(val ok: Boolean, val message: String)

private data class ShellResult(val ok: Boolean, val output: String)

/**
 * 系统层能力封装：
 *  - 依赖 platform/system 证书签名（manifest 中声明了 android.uid.system 时 myUid == 1000）；
 *  - 冻结语义 = pm disable-user（应用从桌面消失且不可运行，可从系统设置恢复）。
 */
object SystemPm {

    fun isSystemProcess(): Boolean = Process.myUid() == Process.SYSTEM_UID

    fun systemUid(): Int = Process.myUid()

    fun checkPermission(context: Context, permission: String): Boolean =
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    private fun runShell(vararg cmd: String): ShellResult = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(10, TimeUnit.SECONDS)
        ShellResult(finished && p.exitValue() == 0, output.trim())
    } catch (e: Exception) {
        ShellResult(false, e.message ?: "命令执行失败")
    }

    /** 加载全部应用（排除自身），并标注冻结状态。耗时操作，需在协程中调用。 */
    suspend fun loadApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val disabled = runShell("pm", "list", "packages", "-d").output
            .lineSequence()
            .mapNotNull { line ->
                val name = line.removePrefix("package:")
                name.takeIf { it.isNotBlank() }
            }
            .toHashSet()

        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { ai: ApplicationInfo ->
                AppEntry(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isFrozen = ai.packageName in disabled
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    /**
     * 暂停 / 恢复应用（Suspended 状态）。
     *
     * 语义：应用被暂停后图标置灰、点击弹出系统对话框、后台活动（如音乐）同步暂停；
     * 与 disable-user 冻结不同，应用仍显示在桌面且可被系统设置一键恢复。
     *
     * 实现方式：直接以 shell 执行 pm 命令（本应用运行于 system UID，子进程继承系统权限）：
     * - 暂停：pm suspend --user 0 <package>
     * - 恢复：pm unsuspend --user 0 <package>
     * 注：pm 命令不支持自定义暂停对话框文案，系统将展示默认提示。
     *
     * @return 传入包名中未能成功切换状态的包名集合（空集合=全部成功）
     */
    suspend fun setPackagesSuspendedCompat(
        context: Context,
        packages: Collection<String>,
        suspended: Boolean
    ): Set<String> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return@withContext packages.toSet() // Android 9 以下不支持 suspend
        }
        val failed = HashSet<String>()
        for (pkg in packages) {
            val cmd = if (suspended) {
                arrayOf("pm", "suspend", "--user", "0", pkg)
            } else {
                arrayOf("pm", "unsuspend", "--user", "0", pkg)
            }
            val r = runShell(*cmd)
            // pm 命令部分失败场景退出码仍为 0，需同时检查输出中的错误关键字
            if (!r.ok || r.output.contains("Failure", true) || r.output.contains("Error", true)) {
                failed += pkg
            }
        }
        failed
    }

    /** 暂停 / 解冻应用。优先使用系统 API，异常时回退到 pm 命令。 */
    suspend fun setFrozen(context: Context, packageName: String, frozen: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val state = if (frozen) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            val summary = if (frozen) "已冻结：$packageName" else "已解冻：$packageName"
            try {
                pm.setApplicationEnabledSetting(packageName, state, 0)
                OpResult(true, summary)
            } catch (e: SecurityException) {
                val shellCmd = if (frozen) {
                    arrayOf("pm", "disable-user", "--user", "0", packageName)
                } else {
                    arrayOf("pm", "enable", "--user", "0", packageName)
                }
                val r = runShell(*shellCmd)
                if (r.ok) {
                    OpResult(true, summary)
                } else {
                    OpResult(
                        false,
                        (e.message ?: r.output.ifBlank { "" }).ifBlank {
                            "操作失败，请确认 APK 已使用系统签名安装"
                        }
                    )
                }
            } catch (e: Exception) {
                OpResult(false, e.message ?: "操作失败")
            }
        }
}
