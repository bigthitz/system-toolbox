package com.system.toolbox

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.system.toolbox.core.Activation
import com.system.toolbox.core.StoreSession
import com.system.toolbox.core.VerifyResult
import com.system.toolbox.ui.AppRoot
import com.system.toolbox.ui.screens.ActivationScreen
import com.system.toolbox.ui.theme.SystemToolboxTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 读取已保存的激活码并重新校验（含授权时间，过期自动失效） */
    private fun isActivated(): Boolean {
        val code = getSharedPreferences(Activation.PREFS, Context.MODE_PRIVATE)
            .getString(Activation.KEY_CODE, null) ?: return false
        val sn = Activation.readSn() ?: return false
        return Activation.verify(code, sn) == VerifyResult.SUCCESS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StoreSession.attach(applicationContext)
        enableEdgeToEdge()
        setContent {
            SystemToolboxTheme {
                var activated by remember { mutableStateOf(isActivated()) }
                var notice by remember { mutableStateOf<String?>(null) }

                // 启动公告：未激活（激活页）与已激活（主界面）都显示
                LaunchedEffect(Unit) {
                    val text = fetchNotice()
                    if (!text.isNullOrBlank()) notice = text
                }

                Box {
                    if (activated) {
                        AppRoot()
                    } else {
                        ActivationScreen(onActivated = { activated = true })
                    }

                    notice?.let { text ->
                        AlertDialog(
                            onDismissRequest = { notice = null },
                            title = { Text("公告") },
                            text = {
                                Text(
                                    text = text,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { notice = null }) {
                                    Text("知道了")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 拉取纯文本公告；https 失败自动尝试 http，均失败返回 null（不打扰用户） */
private suspend fun fetchNotice(): String? = withContext(Dispatchers.IO) {
    for (url in listOf("https://eebbk.bbroot.com/gonggao.php", "http://eebbk.bbroot.com/gonggao.php")) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SystemToolbox/1.0")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) return@withContext trimmed
        } catch (_: Exception) {
            // 换下一个地址重试
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
    null
}
