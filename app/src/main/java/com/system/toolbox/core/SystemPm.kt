package com.system.toolbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
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

    /** 暂停应用时系统对话框展示的提示文案（五参 SystemApi 版本生效时可见） */
    private const val SUSPEND_DIALOG_MESSAGE = "该应用处于限时暂停状态，请在允许使用的时段内打开"

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
     * 语义：应用被暂停后进入 Suspended 状态，无法点击打开，系统会弹出
     * 「应用已被暂停」对话框，后台活动（如音乐播放）也会同步暂停；
     * 与 disable-user 冻结不同，应用仍显示在桌面且可被系统设置一键恢复。
     *
     * 实现方式：直接调用 PackageManager#setPackagesSuspended（与 Android
     * 数字健康 com.google.android.apps.wellbeing 限制应用使用的方式一致），
     * 不再依赖 shell 执行 pm 命令。该方法（含两参与五参版本）为 @hide 的
     * SystemApi，公开 SDK 中不存在，需通过反射调用：
     * - API 31+：优先反射五参版本，暂停弹出的系统对话框可展示自定义提示文案；
     * - API 28~30：回退反射两参版本（默认系统对话框）；
     * - API 28 以下不支持 suspend，全部视为失败。
     *
     * 需在 manifest 声明 SUSPEND_APPS 权限并以 platform/system 证书签名
     * （本应用声明 android.uid.system 运行于 system UID，权限已授予）。
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
        val pm = context.packageManager
        val names = packages.toTypedArray()
        val failed = HashSet<String>()

        // setPackagesSuspended（两参与五参版本）均为 @hide 的 SystemApi，
        // 公开 SDK 的 android.jar 不包含该方法，只能通过反射调用。
        // 本应用以 platform/system 证书签名并运行于 system UID，SUSPEND_APPS 权限已授予。

        // 1. 五参版本（API 31+）：可携带自定义对话框文案
        var result = try {
            PackageManager::class.java.getMethod(
                "setPackagesSuspended",
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
                PersistableBundle::class.java,
                PersistableBundle::class.java,
                String::class.java
            ).invoke(pm, names, suspended, null, null, SUSPEND_DIALOG_MESSAGE)
        } catch (_: Exception) {
            null
        }

        // 2. 两参版本（API 28+）：五参不可用（低版本/被裁剪）时回退
        if (result == null) {
            result = try {
                PackageManager::class.java.getMethod(
                    "setPackagesSuspended",
                    Array<String>::class.java,
                    Boolean::class.javaPrimitiveType
                ).invoke(pm, names, suspended)
            } catch (_: Exception) {
                null
            }
        }

        if (result is Array<*> && result.isNotEmpty()) {
            // API 返回未成功切换状态的包名数组
            failed.addAll(result.filterIsInstance<String>())
        } else if (result == null) {
            // 两个版本均调用失败（权限丢失或系统拒绝）：全部视为失败，下轮扫描重试
            failed.addAll(packages)
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
