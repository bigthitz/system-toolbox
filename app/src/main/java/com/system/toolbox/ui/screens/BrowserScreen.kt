package com.system.toolbox.ui.screens

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.net.URLEncoder

private const val HOME_URL = "https://www.baidu.com"

/** 待开始的下载参数（等待授权确认后真正入队） */
private data class PendingDownload(
    val url: String,
    val userAgent: String,
    val contentDisposition: String,
    val mimeType: String
)

/**
 * 内置浏览器。
 *  - 基于 WebView，地址栏 / 前进 / 后退 / 刷新 / 主页
 *  - 页面内下载（APK、文件、图片等）自动交给系统 DownloadManager，可在通知栏/下载中心查看
 */
@Composable
fun BrowserScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    var urlInput by rememberSaveable { mutableStateOf(HOME_URL) }
    var currentUrl by remember { mutableStateOf(HOME_URL) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var hasBooted by remember { mutableStateOf(false) }

    // ---- 下载 ----
    val writeGranted = Build.VERSION.SDK_INT >= 29 || context.checkSelfPermissionCompat()
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

    fun enqueueDownload(p: PendingDownload) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            toast("当前系统不支持下载")
            return
        }
        val guess = DownloadManager.Request.guessFileName(p.url, p.contentDisposition, p.mimeType)
        val req = DownloadManager.Request(Uri.parse(p.url))
            .setTitle(guess)
            .setDescription(p.url)
            .setMimeType(p.mimeType.ifBlank { "application/octet-stream" })
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader(
                "User-Agent",
                p.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36" }
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        // Android 10+ 无需权限即可写入公共下载目录；低版本优先系统下载目录，
        // 无权限时退回应用私有外部目录
        if (Build.VERSION.SDK_INT >= 29 || writeGranted) {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guess)
            toast("开始下载：$guess")
        } else {
            req.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, guess)
            toast("无存储权限，已改存应用下载目录：$guess")
        }
        dm.enqueue(req)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val p = pendingDownload
        pendingDownload = null
        if (p != null) {
            if (granted) enqueueDownload(p) else {
                // 拒绝也仍然下载，只是保存位置退回应用目录
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (dm != null) {
                    val guess = DownloadManager.Request.guessFileName(p.url, p.contentDisposition, p.mimeType)
                    val req = DownloadManager.Request(Uri.parse(p.url))
                        .setTitle(guess)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, guess)
                    dm.enqueue(req)
                    toast("未授权存储，已保存到应用下载目录：$guess")
                }
            }
        }
    }

    fun requestDownload(p: PendingDownload) {
        if (Build.VERSION.SDK_INT >= 29 || writeGranted) {
            enqueueDownload(p)
        } else {
            pendingDownload = p
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // ---- 页面跳转 ----
    fun navigate(raw: String) {
        val t = raw.trim()
        val target = when {
            t.isEmpty() -> HOME_URL
            t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.baidu.com/s?wd=${URLEncoder.encode(t, "UTF-8")}"
        }
        urlInput = target
        webViewHolder.value?.loadUrl(target)
    }

    fun refreshState() {
        val wv = webViewHolder.value ?: return
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
    }

    fun refresh() {
        val wv = webViewHolder.value ?: return
        val url = wv.url
        if (url.isNullOrBlank()) {
            val cur = currentUrl
            wv.loadUrl(cur)
        } else {
            wv.reload()
        }
    }

    fun goHome() {
        val wv = webViewHolder.value
        if (wv == null) {
            currentUrl = HOME_URL
            urlInput = HOME_URL
        } else {
            wv.loadUrl(HOME_URL)
        }
    }

    // 系统返回键：优先网页后退
    BackHandler {
        val wv = webViewHolder.value
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：标题 + 浏览器工具按钮
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回工具箱")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    pageTitle.ifBlank { if (currentUrl.isEmpty()) "内置浏览器" else currentUrl },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (currentUrl.isNotEmpty() && pageTitle != currentUrl) {
                    Text(
                        currentUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (loading) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        // 地址栏 + 导航按钮
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { webViewHolder.value?.goBack() },
                enabled = canGoBack
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "后退",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outlineVariant
                )
            }
            IconButton(
                onClick = { webViewHolder.value?.goForward() },
                enabled = canGoForward
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "前进",
                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outlineVariant
                )
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                placeholder = { Text("输入网址或搜索内容") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(urlInput) })
            )
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { goHome() }) {
                Icon(Icons.Filled.Home, contentDescription = "主页")
            }
        }

        if (loading) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(5, 100)) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

        // WebView 主体
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = true

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

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                                url?.let { currentUrl = it }
                                view.url?.let { urlInput = it }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                progress = 100
                                view.url?.let { currentUrl = it }
                                urlInput = view.url ?: urlInput
                                refreshState()
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView,
                                errorCode: Int,
                                description: String,
                                failingUrl: String
                            ) {
                                if (failingUrl == view.url) {
                                    loading = false
                                }
                            }

                            override fun doUpdateVisitedHistory(
                                view: WebView,
                                url: String?,
                                isReload: Boolean
                            ) {
                                url?.let { urlInput = it }
                                refreshState()
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                                loading = newProgress < 100
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title ?: ""
                            }
                        }

                        // 页面触发下载（APK/文件/图片…）
                        downloadListener = DownloadListener { url, userAgent, contentDisposition, mime, _ ->
                            requestDownload(
                                PendingDownload(
                                    url = url,
                                    userAgent = userAgent ?: "",
                                    contentDisposition = contentDisposition ?: "",
                                    mimeType = mime ?: ""
                                )
                            )
                        }
                    }
                },
                update = { wv ->
                    webViewHolder.value = wv
                    if (!hasBooted) {
                        hasBooted = true
                        wv.loadUrl(currentUrl.ifBlank { HOME_URL })
                    }
                    refreshState()
                }
            )
        }
    }
}

private fun Context.checkSelfPermissionCompat(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
