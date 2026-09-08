package com.system.toolbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.concurrent.atomic.AtomicLong

/**
 * 受管应用存储（SharedPreferences）：
 * - 受管列表不再持久化、也不允许手动增删，每轮扫描自动通过
 *   PackageManager#getInstallSourceInfo(String) 识别「安装来源为本工具箱」的应用；
 * - [KEY_SUSPENDED]：当前被限时服务暂停（Suspended）的包名集合。时段恢复时只恢复这个集合里的应用，
 *   避免误恢复被其他系统组件（如数字健康）暂停的应用。
 */
object ManagedApps {

    private const val PREFS = "app_limit_store"
    private const val KEY_SUSPENDED = "service_suspended_packages"

    /** 安装来源识别结果的内存缓存有效期：避免 10 秒一轮扫描反复发起大量 binder 调用 */
    private const val SOURCE_CACHE_TTL_MS = 30_000L

    private val lastScanAt = AtomicLong(0L)

    @Volatile
    private var cachedManaged: Set<String> = emptySet()

    @Volatile
    private var cacheValid = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 自动识别安装来源为本工具箱的应用集合。
     * - API 30+：getInstallSourceInfo(packageName).installingPackageName；
     * - 低版本回退 getInstallerPackageName(packageName)（@Deprecated 但功能一致）；
     * 结果带 30 秒内存缓存（安装动作本身耗时远超该窗口，不影响及时性）。
     */
    fun packagesByInstallSource(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val last = lastScanAt.get()
        if (cacheValid && now - last < SOURCE_CACHE_TTL_MS) return cachedManaged

        val appContext = context.applicationContext
        val me = appContext.packageName
        val pm = appContext.packageManager
        val result = try {
            pm.getInstalledPackages(0)
                .asSequence()
                .map { it.packageName }
                .filter { it != me }
                .filter { pkg ->
                    val installer = installerOf(pm, pkg)
                    installer != null && installer == me
                }
                .toHashSet()
        } catch (_: Exception) {
            emptySet<String>()
        }
        cachedManaged = result
        cacheValid = true
        lastScanAt.set(now)
        return result
    }

    private fun installerOf(
        pm: android.content.pm.PackageManager,
        pkg: String
    ): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (_: Exception) {
        null
    }

    /** 当前被限时服务暂停的包名集合。 */
    fun suspendedByService(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SUSPENDED, emptySet())?.toSet() ?: emptySet()

    fun setSuspendedByService(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SUSPENDED, pkgs).apply()
    }

    /** 清理已卸载应用的残留记录（仅清理暂停状态集合）。 */
    fun prune(context: Context, installed: Set<String>) {
        val suspended = suspendedByService(context)
        if (suspended.all { it in installed }) return
        prefs(context).edit()
            .putStringSet(KEY_SUSPENDED, suspended.filterTo(HashSet()) { it in installed })
            .apply()
    }

    /** 判断应用当前是否处于 Suspended 状态（含被其他组件暂停的情况）。 */
    fun isSuspended(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).flags and
            ApplicationInfo.FLAG_SUSPENDED != 0
    } catch (_: Exception) {
        false
    }
}
