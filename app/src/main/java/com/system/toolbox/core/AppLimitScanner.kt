package com.system.toolbox.core

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 限时扫描器：刷新云端配置 → 判断当前时间 → 禁用/解禁受管应用。
 *
 * 由守护服务每 2 分钟调用一次，也供 UI 手动触发：
 * - 限制时段（不在任何允许时段内）：禁用全部受管应用的入口（pm disable-user 语义）；
 * - 允许时段 / 限时关闭 / 无配置：解禁「服务冻结集合」中的应用。
 */
object AppLimitScanner {

    /** 一轮扫描的结果摘要（通知与 UI 展示用）。 */
    data class Outcome(
        val cached: AppLimit.Cached?,  // 生效配置（null=从未成功拉取）
        val restrictedNow: Boolean,    // 当前是否处于限制时段
        val managedCount: Int,         // 受管应用数
        val disabledCount: Int,        // 当前被服务禁用的应用数
        val summary: String
    )

    /**
     * 执行一轮扫描。
     * @param forceRefresh true 时先强制向云端刷新配置（失败回退缓存）
     */
    suspend fun scan(context: Context, forceRefresh: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val pm = appContext.packageManager

            // 1. 已安装包集合：据此清理卸载残留
            val installed = try {
                pm.getInstalledPackages(0).map { it.packageName }.toSet()
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

            val managed = ManagedApps.packages(appContext).filter { it in installed }
            val frozen = ManagedApps.frozenByService(appContext)

            var disabledCount = 0
            var summary: String
            var restrictedNow: Boolean

            when {
                // 从未成功拉取配置：不做任何限制，并解除历史禁用
                config == null -> {
                    restrictedNow = false
                    for (pkg in frozen) {
                        if (pkg in installed) SystemPm.setFrozen(appContext, pkg, false)
                    }
                    ManagedApps.setFrozenByService(appContext, emptySet())
                    summary = "尚未获取云端配置，暂不限制"
                }

                // 云端关闭了限时或未配置时段：全部解禁
                config.unrestricted -> {
                    restrictedNow = false
                    var restored = 0
                    for (pkg in frozen) {
                        if (pkg in installed && SystemPm.setFrozen(appContext, pkg, false).ok) {
                            restored++
                        }
                    }
                    ManagedApps.setFrozenByService(appContext, emptySet())
                    summary = if (config.enabled) {
                        "未配置可用时段，应用不受限"
                    } else {
                        "限时已停用，已解禁 $restored 个应用"
                    }
                }

                // 允许时段：解禁服务冻结的应用
                config.allowedNow() -> {
                    restrictedNow = false
                    for (pkg in frozen) {
                        if (pkg in installed) SystemPm.setFrozen(appContext, pkg, false)
                    }
                    ManagedApps.setFrozenByService(appContext, emptySet())
                    summary = "允许时段（${config.windows.joinToString(" / ")}），应用可用"
                }

                // 限制时段：禁用全部受管应用
                else -> {
                    restrictedNow = true
                    val newFrozen = HashSet<String>()
                    for (pkg in managed) {
                        if (isDisabled(pm, pkg)) {
                            // 已处于禁用状态（本服务或此前禁用），纳入管理
                            newFrozen += pkg
                            continue
                        }
                        if (SystemPm.setFrozen(appContext, pkg, true).ok) {
                            newFrozen += pkg
                        }
                    }
                    // 曾被服务禁用、但已移出受管范围的应用 → 解除禁用
                    for (pkg in frozen) {
                        if (pkg !in newFrozen && pkg in installed && pkg !in managed) {
                            SystemPm.setFrozen(appContext, pkg, false)
                        }
                    }
                    ManagedApps.setFrozenByService(appContext, newFrozen)
                    disabledCount = newFrozen.size
                    summary = "限制时段（可用：${config.windows.joinToString(" / ")}），已禁用 $disabledCount 个应用"
                }
            }

            Outcome(cached, restrictedNow, managed.size, disabledCount, summary)
        }

    private fun isDisabled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getApplicationEnabledSetting(pkg) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (_: Exception) {
        false
    }
}
