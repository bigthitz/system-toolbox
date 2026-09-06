package com.system.toolbox

import android.app.Application
import com.system.toolbox.core.WebViewHacker

/**
 * 全局 Application。
 * 在任何 Activity/WebView 之前执行特权进程 WebView 解禁。
 */
class ToolboxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        WebViewHacker.hackWebView()
    }
}
