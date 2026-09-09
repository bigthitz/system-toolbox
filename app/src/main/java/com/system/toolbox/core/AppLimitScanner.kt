package com.system.toolbox.core

import android.content.Context
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 限时扫描器：刷新云端配置 → 判断当前时间 → 暂停/恢复受管应用。
 *
 * 由守护服务每 10 秒调用一次，也供 UI 手动触发：
 * - 受管列表每轮自动通过安装来源（getInstallSourceInfo）识别为本工具箱安装的应用，禁止手动增删；
 * - 限制时段（不在任何允许时段内）：通过 PackageManager#setPackagesSuspended 暂停全部受管应用；
 * - 允许时段 / 限时关闭 / 无配置：通过 setPackagesSuspended(…, false) 恢复，
 *   并自愈「受管但未被记录的暂停状态」，保证限制一定能解除。
 */
object AppLimitScanner {

    /** 一轮扫描的结果摘要（通知与 UI 展示用）。 */
    data class Outcome(
        val cached: AppLimit.Cached?,  // 生效配置（null=从未成功拉取）
        val restrictedNow: Boolean,    // 当前是否处于限制时段
        val managedCount: Int,         // 受管应用数
        val disabledCount: Int,        // 当前被服务暂停的应用数
        val summary: String
    )

    /**
     * 执行一轮扫描。
     * @param forceRefresh true 时先强制向云端刷新配置（失败回退缓存）
     */
    suspend fun scan(context: Context, forceRefresh: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext

            // 1. 已安装包集合：据此清理卸载残留
            val installed = try {
                appContext.packageManager.getInstalledPackages(0)
                    .map { it.packageName }.toSet()
            } catch (_: Exception) {
                emptySet<String>()
            }
            ManagedApps.prune(appContext, installed)

            // 2. 生效配置（24h 缓存策略，失败回退）
            val cached: AppLimit.Cached? = if (forceRefresh) {
                when (val r = AppLimit.refreshNow(appContext)) {
                    is AppLimit.RefreshResult.Success -> r.cached
                    is AppLimit.RefreshResult.Fallback -> r.cached
                }
            } else {
                AppLimit.ensureFresh(appContext)
            }
            val config = cached?.config

            // 3. 受管应用：按安装来源自动识别（本工具箱安装）
            val managed = ManagedApps.packagesByInstallSource(appContext)
                .filter { it in installed }
            val suspended = ManagedApps.suspendedByService(appContext)

            var suspendedCount = 0
            var summary: String
            var restrictedNow: Boolean

            when {
                // 从未成功拉取配置：不做任何限制，并解除历史暂停
                config == null -> {
                    restrictedNow = false
                    val remaining = restoreAll(appContext, suspended, managed, installed)
                    ManagedApps.setSuspendedByService(appContext, remaining)
                    summary = "尚未获取云端配置，暂不限制"
                }

                // 云端关闭了限时或未配置时段：全部恢复
                config.unrestricted -> {
                    restrictedNow = false
                    val remaining = restoreAll(appContext, suspended, managed, installed)
                    ManagedApps.setSuspendedByService(appContext, remaining)
                    summary = if (config.enabled) {
                        "未配置可用时段，应用不受限"
                    } else {
                        "限时已停用，应用已恢复"
                    }
                }

                // 允许时段：恢复被暂停的受管应用
                config.allowedNow() -> {
                    restrictedNow = false
                    val remaining = restoreAll(appContext, suspended, managed, installed)
                    ManagedApps.setSuspendedByService(appContext, remaining)
                    summary = "允许时段（${todayWindows(config)}），应用可用"
                }

                // 限制时段：暂停全部受管应用
                else -> {
                    restrictedNow = true
                    val newSuspended = HashSet<String>()
                    for (pkg in managed) {
                    // 已被本服务暂停且实际仍处于暂停状态：维持现状，避免重复调用 API
                        if (pkg in suspended && ManagedApps.isSuspended(appContext, pkg)) {
                            newSuspended += pkg
                            continue
                        }
                        val failed = SystemPm.setPackagesSuspendedCompat(
                            appContext, listOf(pkg), true
                        )
                        if (pkg !in failed) newSuspended += pkg
                    }
                    // 曾被服务暂停、但已不满足受管条件（卸载来源变更等）的应用 → 恢复
                    val toRestore = suspended.filter {
                        it !in newSuspended && it in installed && it !in managed
                    }
                    val restoreFailed = restore(appContext, toRestore)
                    ManagedApps.setSuspendedByService(appContext, newSuspended + restoreFailed)
                    suspendedCount = newSuspended.size
                    summary = "限制时段（可用：${todayWindows(config)}），已暂停 $suspendedCount 个应用"
                }
            }

            Outcome(cached, restrictedNow, managed.size, suspendedCount, summary)
        }

    /**
     * 恢复所有应恢复的应用：被服务暂停的 + 受管但意外处于暂停状态却未被记录的
     * （自愈历史遗留 / 卡死状态，保证允许时段一定能解除限制）。
     * 返回仍处于暂停状态（恢复失败，下轮重试）的包名集合。
     */
    private suspend fun restoreAll(
        context: Context,
        suspended: Set<String>,
        managed: Collection<String>,
        installed: Set<String>
    ): Set<String> {
        val targets = buildList {
            suspended.forEach { if (it in installed) add(it) }
            managed.forEach {
                if (it !in suspended && it in installed && ManagedApps.isSuspended(context, it)) {
                    add(it)
                }
            }
        }.distinct()
        return restore(context, targets)
    }

    /** 恢复一批被本服务暂停的应用。返回仍处于暂停状态（恢复失败）的包名集合。 */
    private suspend fun restore(context: Context, packages: List<String>): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val failed = SystemPm.setPackagesSuspendedCompat(context, packages, false)
        return packages.filterTo(HashSet()) { it in failed }
    }

    /** 当前日期适用的时段文本（周末且单独配置时为周末时段）。 */
    private fun todayWindows(config: AppLimit.Config): String =
        config.windowsFor(Calendar.getInstance()).joinToString(" / ")
}
