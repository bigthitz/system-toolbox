package com.system.toolbox.core

import android.content.Context

/**
 * 受管应用存储（SharedPreferences）：
 * - [KEY_MANAGED]：通过本工具箱安装（或手动加入）的应用包名集合，限时功能的作用范围；
 * - [KEY_FROZEN]：当前被限时服务禁用的包名集合。时段恢复时只解禁这个集合里的应用，
 *   避免误恢复用户通过「应用冻结」手动冻结的应用。
 */
object ManagedApps {

    private const val PREFS = "app_limit_store"
    private const val KEY_MANAGED = "managed_packages"
    private const val KEY_FROZEN = "service_frozen_packages"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记录一个通过工具箱安装成功的应用（自动去重）。 */
    fun record(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        prefs(context).edit()
            .putStringSet(KEY_MANAGED, packages(context) + pkg)
            .apply()
    }

    /** 手动批量加入受管。 */
    fun addAll(context: Context, pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        prefs(context).edit()
            .putStringSet(KEY_MANAGED, packages(context) + pkgs)
            .apply()
    }

    /** 移出管理（下一轮扫描会解除其限时禁用）。 */
    fun remove(context: Context, pkg: String) {
        prefs(context).edit()
            .putStringSet(KEY_MANAGED, packages(context) - pkg)
            .apply()
    }

    /** 受管应用包名集合。 */
    fun packages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MANAGED, emptySet())?.toSet() ?: emptySet()

    /** 当前被限时服务禁用的包名集合。 */
    fun frozenByService(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FROZEN, emptySet())?.toSet() ?: emptySet()

    fun setFrozenByService(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_FROZEN, pkgs).apply()
    }

    /** 清理已卸载应用的残留记录。 */
    fun prune(context: Context, installed: Set<String>) {
        val managed = packages(context)
        val frozen = frozenByService(context)
        if (managed.all { it in installed } && frozen.all { it in installed }) return
        prefs(context).edit()
            .putStringSet(KEY_MANAGED, managed.filterTo(HashSet()) { it in installed })
            .putStringSet(KEY_FROZEN, frozen.filterTo(HashSet()) { it in installed })
            .apply()
    }
}
