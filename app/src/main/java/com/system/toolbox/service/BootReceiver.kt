package com.system.toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：拉起应用限时守护服务。
 * BOOT_COMPLETED 为受保护系统广播，receiver 无需导出即可收到。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLimitService.start(context)
        }
    }
}
