package com.system.toolbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.Activation

/**
 * 设备激活页：未通过公钥校验前拦截进入主功能。
 *  - 展示本机序列号（/data/misc/bbksn），支持复制
 *  - 序列号不可读时允许手动输入
 *  - 粘贴服务器签发的激活码，本地公钥验签通过即永久激活（存本地）
 */
@Composable
fun ActivationScreen(
    onActivated: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Activation.PREFS, Context.MODE_PRIVATE) }

    val snRead = remember { Activation.readSn() }
    var snInput by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var activating by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun activate() {
        val sn = snRead ?: snInput.trim()
        if (sn.isEmpty()) {
            error = "无法获取序列号，请手动输入"
            return
        }
        if (code.isBlank()) {
            error = "请输入激活码"
            return
        }
        activating = true
        error = null
        val ok = Activation.verify(code, sn)
        activating = false
        if (ok) {
            prefs.edit().putString(Activation.KEY_CODE, code.trim()).apply()
            toast("激活成功")
            onActivated()
        } else {
            error = "激活码无效：请确认与本机序列号匹配"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "设备激活",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "本工具仅限授权设备使用，请将下方序列号发送给管理员获取激活码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // 序列号
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("设备序列号", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                if (snRead != null) {
                    Text(
                        text = snRead,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    OutlinedTextField(
                        value = snInput,
                        onValueChange = { snInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("自动读取失败，请手动输入序列号") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    enabled = snRead != null,
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("SN", snRead))
                            toast("序列号已复制")
                        }
                    }
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制序列号")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 激活码
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            placeholder = { Text("粘贴激活码（Base64URL 签名数据）") },
            isError = error != null,
            supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { activate() },
            enabled = !activating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (activating) "校验中…" else "激活")
        }
        TextButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                if (text.isNotBlank()) code = text.trim() else toast("剪贴板为空")
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("从剪贴板粘贴")
        }
    }
}
