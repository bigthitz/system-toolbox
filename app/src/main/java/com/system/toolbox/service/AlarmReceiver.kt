package com.system.toolbox.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 守护闹钟接收器：
 * - 服务存活时由其周期设置，用于 CPU 休眠期间准时唤醒触发扫描；
 * - 服务被杀后（onDestroy 设置的短延时闹钟）负责重新拉起服务；
 * - 独立心跳链：服务未运行时自行续约下一轮心跳闹钟，
 *   即使服务持续拉起失败，保活链路也不会中断。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 拉起守护服务
        AppLimitService.start(context)
        // 独立心跳：服务不在运行时续约闹钟（60 秒后再试）
        if (!AppLimitService.isRunning) {
            GuardKeepAlive.scheduleHeartbeat(context)
        }
    }
}
