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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.system.toolbox.core.Activation
import com.system.toolbox.core.VerifyResult

/**
 * 设备激活页（未通过公钥校验前拦截进入主功能）。
 *  - 展示本机序列号（/data/misc/bbksn），支持复制；不可读时可手动输入
 *  - 粘贴服务器签发的激活码，公钥验签 + 授权时间校验通过即激活（存本地，过期自动失效）
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

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun activate() {
        val sn = snRead ?: snInput.trim()
        if (sn.isEmpty()) {
            error = "无法获取设备序列号"
            return
        }
        if (code.isBlank()) {
            error = "请输入激活码"
            return
        }
        when (Activation.verify(code, sn)) {
            VerifyResult.SUCCESS -> {
                prefs.edit().putString(Activation.KEY_CODE, code.trim()).apply()
                toast("激活成功")
                onActivated()
            }
            VerifyResult.EXPIRED -> error = "激活码已过期，请重新获取"
            VerifyResult.INVALID -> error = "激活码无效"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "激活",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(20.dp))

        // 设备序列号
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

        // 激活码输入
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            singleLine = false,
            placeholder = { Text("请输入激活码") },
            isError = error != null,
            supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { activate() })
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { activate() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("确定")
        }

        TextButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    code = text.trim()
                    error = null
                } else {
                    toast("剪贴板为空")
                }
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("从剪贴板粘贴")
        }
    }
}
