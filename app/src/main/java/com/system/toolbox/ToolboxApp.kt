package com.system.toolbox

import android.app.Application
import com.system.toolbox.core.WebViewHacker
import com.system.toolbox.service.AppLimitService

/**
 * 全局 Application。
 * 在任何 Activity/WebView 之前执行特权进程 WebView 解禁，
 * 并拉起应用限时守护服务（已运行时为幂等操作）。
 */
class ToolboxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebViewHacker.hackWebView()
        AppLimitService.start(this)
    }
}
