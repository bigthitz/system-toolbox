package com.system.toolbox

import android.app.Application
import com.system.toolbox.core.WebViewHacker
import com.system.toolbox.service.GuardKeepAlive

/**
 * 全局 Application。
 * 在任何 Activity/WebView 之前执行特权进程 WebView 解禁，
 * 并拉起应用限时守护的全部保活链路（前台服务 + 心跳闹钟 + 看门狗任务，
 * 已运行时均为幂等操作）。
 */
class ToolboxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebViewHacker.hackWebView()
        GuardKeepAlive.ensureAll(this)
    }
}
