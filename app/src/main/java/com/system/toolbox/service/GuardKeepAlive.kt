package com.system.toolbox.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * 应用限时守护的保活总控：
 * - 前台服务（AppLimitService，START_STICKY + stopWithTask=false）；
 * - 服务扫描闹钟链（服务存活时每轮扫描续约）；
 * - 独立心跳闹钟链（AlarmReceiver 自续约，服务被杀也能周期性拉起）；
 * - JobScheduler 看门狗（每 15 分钟兜底，进程被杀后由系统回调拉起）；
 * - 开机 / 时间·时区变化 / 应用更新等系统广播拉起（BootReceiver）。
 */
object GuardKeepAlive {

    private const val HEARTBEAT_REQUEST_CODE = 2002
    private const val HEARTBEAT_INTERVAL_MS = 60_000L

    private const val WATCHDOG_JOB_ID = 3001

    /** 确保全部保活机制就位：前台服务 + 心跳闹钟 + 看门狗任务。 */
    fun ensureAll(context: Context) {
        AppLimitService.start(context)
        scheduleHeartbeat(context)
        scheduleWatchdogJob(context)
    }

    /**
     * 独立心跳闹钟：由 AlarmReceiver 每次触发时自行续约，
     * 不依赖守护服务存活（服务死亡后仍能周期性被唤醒拉起）。
     */
    fun scheduleHeartbeat(context: Context, delayMs: Long = HEARTBEAT_INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    /** JobScheduler 看门狗：每 15 分钟由系统回调，进程被杀后也能恢复全部保活链路。 */
    fun scheduleWatchdogJob(context: Context) {
        try {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(WATCHDOG_JOB_ID) != null) return
            val info = JobInfo.Builder(
                WATCHDOG_JOB_ID,
                ComponentName(context, GuardWatchdogJob::class.java)
            )
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            js.schedule(info)
        } catch (_: Exception) {
            // 调度失败不影响其他保活手段
        }
    }
}
