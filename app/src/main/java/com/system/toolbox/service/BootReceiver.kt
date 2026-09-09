package com.system.toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 守护服务自启接收器：
 * - BOOT_COMPLETED：开机拉起（受保护系统广播，receiver 无需导出即可收到）；
 * - TIME_SET / TIMEZONE_CHANGED / LOCALE_CHANGED：时间被改动（可能影响限时判断）等
 *   仍在隐式广播白名单内的系统事件，顺带校验保活链路；
 * - MY_PACKAGE_REPLACED：应用自升级后服务与闹钟会丢失，立即重建。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> GuardKeepAlive.ensureAll(context)
        }
    }
}
