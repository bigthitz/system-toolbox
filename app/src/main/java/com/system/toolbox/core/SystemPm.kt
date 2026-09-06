package com.system.toolbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条已安装应用记录 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isFrozen: Boolean
)

/** 操作结果 */
data class OpResult(val ok: Boolean, val message: String)

/**
 * 系统层能力封装：
 *  - 依赖 platform/system 证书签名（manifest 中声明了 android.uid.system 时 myUid == 1000）；
 *  - 冻结语义 = pm disable-user（应用从桌面消失且不可运行，可从系统设置恢复）；
 *  - 全部使用 PackageManager 公开 API 实现，不执行任何 shell 命令，
 *    避免 SELinux enforcing 下 platform_app 直接 exec pm 被拒。
 */
object SystemPm {

    fun isSystemProcess(): Boolean = Process.myUid() == Process.SYSTEM_UID

    fun systemUid(): Int = Process.myUid()

    fun checkPermission(context: Context, permission: String): Boolean =
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    /** 通过公开 API 判断应用当前是否处于“冻结/禁用”状态（等价 pm list packages -d）。 */
    private fun isDisabled(pm: PackageManager, packageName: String): Boolean = try {
        when (pm.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** 加载全部应用（排除自身），并标注冻结状态。耗时操作，需在协程中调用。 */
    suspend fun loadApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { ai: ApplicationInfo ->
                AppEntry(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isFrozen = isDisabled(pm, ai.packageName)
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    /** 冻结 / 解冻应用。仅使用系统 API，不经过 shell（规避 SELinux）。 */
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
                OpResult(
                    false,
                    (e.message?.trimEnd('.')?.takeIf { it.isNotBlank() }
                        ?: "无权限") +
                        "；请确认 APK 已使用平台证书签名并以 system 身份安装"
                )
            } catch (e: Exception) {
                OpResult(false, e.message ?: "操作失败")
            }
        }
}
