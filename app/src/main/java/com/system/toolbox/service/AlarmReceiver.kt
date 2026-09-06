package com.system.toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 守护闹钟接收器：
 * - 服务存活时由其周期设置，用于 CPU 休眠期间准时唤醒触发扫描；
 * - 服务被杀后（onDestroy 设置的短延时闹钟）负责重新拉起服务。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLimitService.start(context)
    }
}
