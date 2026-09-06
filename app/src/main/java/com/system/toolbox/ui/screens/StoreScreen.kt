package com.system.toolbox.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.SilentInstaller
import com.system.toolbox.core.StoreApi
import com.system.toolbox.core.StoreApi.StoreApp
import com.system.toolbox.core.StoreIconLoader
import kotlinx.coroutines.launch

@Composable
fun StoreScreen(
    onBack: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<StoreApp>?>(null) }
    var busyAppId by remember { mutableStateOf<String?>(null) }
    var busyText by remember { mutableStateOf("") }
    val progressMap = remember { mutableStateMapOf<String, Float?>() }

    fun doSearch() {
        val kw = keyword.trim()
        if (kw.isEmpty() || searching) return
        searching = true
        scope.launch {
            val apps = try {
                StoreApi.search(kw)
            } catch (_: Exception) {
                emptyList()
            }
            results = apps
            searching = false
            if (apps.isEmpty()) toast("没有找到相关应用，请更换关键词")
        }
    }

    fun install(app: StoreApp) {
        if (busyAppId != null) return
        busyAppId = app.appId
        busyText = "获取下载地址…"
        var file: java.io.File? = null
        scope.launch {
            try {
                val url = StoreApi.resolveDownloadUrl(app.appId)
                if (url == null) {
                    toast("获取下载地址失败")
                    return@launch
                }
                busyText = "下载中…"
                file = StoreApi.downloadApk(context, app.appId, url) { done, total ->
                    progressMap[app.appId] =
                        if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
                }
                if (file == null) {
                    toast("下载失败")
                    return@launch
                }
                busyText = "正在静默安装…"
                val result = SilentInstaller.installFile(context, file!!)
                toast(if (result.ok) "已安装：${app.name}" else result.message)
            } catch (e: Exception) {
                toast("操作失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                try {
                    file?.delete()
                } catch (_: Exception) {
                }
                busyAppId = null
                busyText = ""
                progressMap.remove(app.appId)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "应用商店",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索豌豆荚应用") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            shape = RoundedCornerShape(18.dp)
        )

        Spacer(Modifier.height(6.dp))

        Button(
            onClick = { doSearch() },
            enabled = !searching,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("搜索")
        }

        Spacer(Modifier.height(16.dp))

        val list = results ?: emptyList()
        if (list.isEmpty()) {
            Text(
                text = if (searching) "正在搜索…" else "输入关键词搜索豌豆荚上的应用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.appId }) { app ->
                    StoreResultCard(
                        app = app,
                        isBusy = busyAppId == app.appId,
                        busyText = busyText,
                        progress = progressMap[app.appId],
                        enabled = busyAppId == null || busyAppId == app.appId,
                        onInstall = { install(app) }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun StoreResultCard(
    app: StoreApp,
    isBusy: Boolean,
    busyText: String,
    progress: Float?,
    enabled: Boolean,
    onInstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StoreIcon(app = app)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val meta = listOfNotNull(
                    app.versionName.takeIf { it.isNotBlank() },
                    app.installCount.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (app.description.isNotBlank()) {
                    Text(
                        text = app.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isBusy) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = busyText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    val p = progress
                    if (p != null) {
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .width(72.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("安装")
                }
            }
        }
    }
}

@Composable
private fun StoreIcon(app: StoreApp) {
    var bitmap by remember(app.appId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(app.iconUrl) {
        bitmap = StoreIconLoader.load(app.iconUrl)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = app.name.trim().take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
