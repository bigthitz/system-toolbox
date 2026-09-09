package com.system.toolbox.service

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 看门狗任务：系统每 15 分钟回调一次（setPersisted，重启后依然有效），
 * 兜底拉起守护服务并重建全部保活链路（闹钟心跳 + 看门狗任务自身）。
 * 即使应用进程被整体杀死，JobScheduler 仍会回调本任务完成自恢复。
 */
class GuardWatchdogJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        GuardKeepAlive.ensureAll(applicationContext)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // 任务未执行完被系统中断：请求重新调度
        return true
    }
}
