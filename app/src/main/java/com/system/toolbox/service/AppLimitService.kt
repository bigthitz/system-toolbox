package com.system.toolbox.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.system.toolbox.MainActivity
import com.system.toolbox.R
import com.system.toolbox.core.AppLimitScanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 应用限时守护服务（前台服务）：
 * - 开机自启（BootReceiver / persistent 进程启动时拉起）；
 * - 每 2 分钟扫描一轮受管应用，按云端时间配置自动禁用/解禁；
 * - 保活三重保障：START_STICKY + stopWithTask=false + 精确闹钟兜底重启。
 */
class AppLimitService : Service() {

    companion object {
        const val SCAN_INTERVAL_MS = 2 * 60 * 1000L
        private const val CHANNEL_ID = "app_limit_guard"
        private const val NOTIFICATION_ID = 1001
        private const val ALARM_REQUEST_CODE = 2001

        /** 供 UI 观察：服务是否存活 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 最近一轮扫描结果（UI 展示用） */
        @Volatile
        var lastOutcome: AppLimitScanner.Outcome? = null
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AppLimitService::class.java))
            } catch (_: Exception) {
                // 极端情况下系统拒绝（system uid 下几乎不会发生）
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val looping = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        promoteForeground()
        ensureScanLoop(0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        // 距上次扫描超过 1 分钟（闹钟/开机等拉起）时立即补扫一轮
        if (SystemClock.elapsedRealtime() - lastScanAt > 60_000L) {
            scope.launch { runScan() }
        }
        // 兜底：若循环链因异常中断则重建
        ensureScanLoop(5_000L)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleAlarm(3_000L)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        // 服务被杀后由闹钟在数秒后重新拉起
        scheduleAlarm(3_000L)
        super.onDestroy()
    }

    @Volatile
    private var lastScanAt = 0L

    /** 启动扫描循环（仅一条链）。 */
    private fun ensureScanLoop(initialDelayMs: Long) {
        if (!looping.compareAndSet(false, true)) return
        handler.postDelayed({ step() }, initialDelayMs)
    }

    private fun step() {
        scope.launch {
            runScan()
            handler.postDelayed({ step() }, SCAN_INTERVAL_MS)
        }
    }

    private suspend fun runScan() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            val outcome = AppLimitScanner.scan(this@AppLimitService)
            lastOutcome = outcome
            lastScanAt = SystemClock.elapsedRealtime()
            updateNotification(outcome.summary)
            scheduleAlarm(SCAN_INTERVAL_MS)
        } catch (_: Exception) {
            // 单轮失败不影响后续轮次
        } finally {
            scanning.set(false)
        }
    }

    // ---- 闹钟兜底：服务死亡后仍能被唤醒拉起；CPU 休眠时保证扫描准时 ----

    private fun scheduleAlarm(delayMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            Intent(this, AlarmReceiver::class.java),
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
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    // ---- 前台通知 ----

    private fun promoteForeground() {
        val text = lastOutcome?.summary ?: "守护运行中"
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("应用限时守护")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "应用限时守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持应用限时服务在后台运行" }
        )
    }
}
