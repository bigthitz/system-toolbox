package com.system.toolbox

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.Activation
import com.system.toolbox.core.SelfDestruct
import com.system.toolbox.core.StoreSession
import com.system.toolbox.core.VerifyResult
import com.system.toolbox.ui.AppRoot
import com.system.toolbox.ui.screens.ActivationScreen
import com.system.toolbox.ui.theme.SystemToolboxTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 启动门禁的界面状态 */
private enum class GateUi { CHECKING, BLOCKED, DESTROYING, READY }

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
                var gateUi by remember { mutableStateOf(GateUi.CHECKING) }
                var activated by remember { mutableStateOf(isActivated()) }
                var notice by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                // 启动门禁：必须联网且云端未下发自毁指令，否则禁止使用
                fun runGate() {
                    scope.launch {
                        gateUi = GateUi.CHECKING
                        when (withContext(Dispatchers.IO) { SelfDestruct.gate(applicationContext) }) {
                            SelfDestruct.Gate.Proceed -> gateUi = GateUi.READY
                            SelfDestruct.Gate.NoNetwork -> gateUi = GateUi.BLOCKED
                            SelfDestruct.Gate.Destroy -> {
                                gateUi = GateUi.DESTROYING
                                withContext(Dispatchers.IO) {
                                    SelfDestruct.destroy(applicationContext)
                                }
                                // destroy 正常不会返回（进程被杀/卸载）；万一返回继续阻断
                                gateUi = GateUi.BLOCKED
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) { runGate() }

                // 被网络阻断时每 10 秒自动重试
                LaunchedEffect(gateUi) {
                    if (gateUi == GateUi.BLOCKED) {
                        delay(10_000)
                        runGate()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (gateUi) {
                        GateUi.CHECKING -> GateSpinner("正在验证使用许可…")
                        GateUi.BLOCKED -> NoNetworkScreen { runGate() }
                        GateUi.DESTROYING -> GateSpinner("应用已被停用")
                        GateUi.READY -> {
                            if (activated) {
                                AppRoot()
                            } else {
                                ActivationScreen(onActivated = { activated = true })
                            }

                            // 启动公告：未激活（激活页）与已激活（主界面）都显示
                            LaunchedEffect(Unit) {
                                val text = fetchNotice()
                                if (!text.isNullOrBlank()) notice = text
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
    }
}

/** 门禁检查中 / 自毁执行中的过渡页 */
@Composable
private fun GateSpinner(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

/** 无网络阻断页：禁止使用，提供手动重试（同时每 10 秒自动重试） */
@Composable
private fun NoNetworkScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("无法连接网络", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "本应用需联网验证后方可使用，\n请检查网络连接后重试。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("重试") }
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
