package com.system.toolbox.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val MORE_URL = "https://eebbk.bbroot.com/new.html"

/**
 * 注入网页的桥接助手（onPageFinished 时执行一次）：
 *  - SystemToolbox.execAsync(cmd)  → 返回 Promise，resolve(输出)
 *  - SystemToolbox.run(cmd)        → 同步返回输出（适合短命令）
 */
private const val BRIDGE_HELPER_JS = "(function(){" +
    "if(window.SystemToolbox&&!window.SystemToolbox.__ready){" +
    "window.SystemToolbox.__ready=true;" +
    "var pending={};" +
    "window.SystemToolbox.execAsync=function(cmd){" +
    "return new Promise(function(resolve){" +
    "var id='cb'+(new Date().getTime())+Math.floor(Math.random()*100000);" +
    "pending[id]=resolve;" +
    "window.SystemToolbox.exec(cmd,id);" +
    "});};" +
    "window.SystemToolbox.__cb=function(id,result){" +
    "var r=pending[id];" +
    "if(r){delete pending[id];r(result);}" +
    "};" +
    "}})();"

/** 提供给网页的 Shell 桥（WebView 注入名为 SystemToolbox） */
class ShellBridge(private val webView: WebView) {

    /** 同步执行并返回完整输出（适合短命令） */
    @JavascriptInterface
    fun run(command: String): String = runShell(command)

    /** 异步执行：完成后回调 window.SystemToolbox.__cb(callbackId, 输出) */
    @JavascriptInterface
    fun exec(command: String, callbackId: String) {
        Thread {
            val result = runShell(command)
            webView.post {
                try {
                    webView.evaluateJavascript(
                        "window.SystemToolbox && window.SystemToolbox.__cb(" +
                            JSONObject.quote(callbackId) + "," + JSONObject.quote(result) + ");",
                        null
                    )
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun runShell(command: String): String = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        p.waitFor()
        out
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }
}

/**
 * 更多功能：内置 WebView 加载 eebbk.bbroot.com/new.html，
 * 网页通过注入的 SystemToolbox 对象执行 shell 命令并与原生通信。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MoreScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    var hasBooted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webViewHolder.value?.goBack()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "更多",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { webViewHolder.value?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        if (loading) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(5, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    try {
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                            // 注入 Shell 桥：网页 JS 通过 window.SystemToolbox 调用
                            addJavascriptInterface(ShellBridge(this), "SystemToolbox")

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val u = request.url.toString()
                                    return if (u.startsWith("http://") || u.startsWith("https://")) {
                                        false
                                    } else {
                                        toast("暂不支持跳转外部协议")
                                        true
                                    }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    // 注入 execAsync/Promise 助手（幂等）
                                    view.evaluateJavascript(BRIDGE_HELPER_JS, null)
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                    loading = newProgress < 100
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        toast("WebView 初始化失败：${t.message}")
                        android.widget.TextView(ctx).apply {
                            text = "此设备 ROM 无法加载 WebView（${t.javaClass.simpleName}）"
                            setPadding(48, 48, 48, 48)
                        }
                    }
                },
                update = { v ->
                    val wv = v as? WebView
                    if (wv != null) {
                        webViewHolder.value = wv
                        if (!hasBooted) {
                            hasBooted = true
                            wv.loadUrl(MORE_URL)
                        }
                        canGoBack = wv.canGoBack()
                    }
                }
            )
        }
    }
}
