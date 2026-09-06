package com.system.toolbox.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * 静默安装器。
 * 基于 PackageInstaller Session 提交，配合 [InstallResultReceiver] 接收系统回执。
 * 无需弹窗的条件：应用持有 INSTALL_PACKAGES 权限（system/privileged 签名）。
 */
object SilentInstaller {

    data class InstallResult(
        val ok: Boolean,
        val packageName: String?,
        val message: String
    )

    private val callbacks = HashMap<Int, (InstallResult) -> Unit>()

    private fun register(sessionId: Int, callback: (InstallResult) -> Unit) {
        synchronized(callbacks) { callbacks[sessionId] = callback }
    }

    /** 供 [InstallResultReceiver] 在收到系统广播后回调 UI（主线程）。 */
    fun deliverResult(context: Context, sessionId: Int, result: InstallResult) {
        val callback = synchronized(callbacks) { callbacks.remove(sessionId) } ?: return
        Handler(Looper.getMainLooper()).post { callback(result) }
    }

    /** 静默安装指定 APK。结果通过 onResult 在 UI 线程回调。 */
    fun install(context: Context, apkUri: Uri, onResult: (InstallResult) -> Unit) {
        Thread {
            var sessionId = -1
            try {
                @Suppress("DEPRECATION")
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

                val installer = context.packageManager.packageInstaller
                val createdId = installer.createSession(params)
                sessionId = createdId
                register(createdId, onResult)

                val sender = PendingIntent.getBroadcast(
                    context,
                    createdId,
                    Intent(context, InstallResultReceiver::class),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ).intentSender

                val session = installer.openSession(createdId)
                try {
                    val input = context.contentResolver.openInputStream(apkUri)
                        ?: throw IOException("无法读取所选 APK 文件")
                    input.use { source ->
                        val out = session.openWrite("base.apk", 0, -1)
                        out.use { destination -> source.copyTo(destination) }
                    }
                    session.commit(sender)
                } finally {
                    try {
                        session.close()
                    } catch (_: Exception) {
                        // 关闭失败不阻塞结果回执
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: "安装失败"
                if (sessionId >= 0) {
                    deliverResult(context, sessionId, InstallResult(false, null, message))
                } else {
                    Handler(Looper.getMainLooper()).post {
                        onResult(InstallResult(false, null, message))
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
