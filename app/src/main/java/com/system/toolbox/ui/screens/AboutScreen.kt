package com.system.toolbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** QQ 交流群群号 */
private const val QQ_GROUP_NUMBER = "193438056"

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "未知"
        }
    }
    val appIcon = remember {
        rasterize(context.packageManager.getApplicationIcon(context.packageName))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = appIcon,
            contentDescription = "应用图标",
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(26.dp))
        )
        Spacer(Modifier.size(20.dp))
        Text(
            text = "版本 $versionName",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = "开发者：yyds",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(24.dp))
        Text(
            text = "加入QQ群：$QQ_GROUP_NUMBER",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { joinQQGroup(context) }
        )
    }
}

/**
 * 拉起手机 QQ 加入群聊：
 * - 优先通过 QQ 的加群 scheme 指定包名拉起；
 * - 未安装 QQ 或指定包名拉起失败时去掉包名再试（可能由其他应用处理）；
 * - 仍失败则复制群号到剪贴板并提示，方便手动加群。
 */
private fun joinQQGroup(context: Context) {
    val uri = Uri.parse(
        "mqqopensdkapi://card/show_pslcard" +
            "?src_type=internal&version=1&uin=$QQ_GROUP_NUMBER" +
            "&card_type=group&source=qrcode"
    )
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.tencent.mobileqq")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            copyQQGroupNumber(context)
        }
    }
}

/** 复制群号到剪贴板并提示。 */
private fun copyQQGroupNumber(context: Context) {
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("QQ群号", QQ_GROUP_NUMBER))
        Toast.makeText(context, "已复制群号 $QQ_GROUP_NUMBER，请在QQ中手动加群", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(context, "加入群失败，群号：$QQ_GROUP_NUMBER", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把任意应用图标（包括 AdaptiveIcon）栅格化为位图，
 * 避免 Compose 无法直接加载自适应图标 xml 导致的崩溃。
 */
private fun rasterize(drawable: android.graphics.drawable.Drawable): ImageBitmap {
    val sizePx = 256
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
