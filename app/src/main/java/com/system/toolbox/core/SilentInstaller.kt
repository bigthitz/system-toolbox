package com.system.toolbox.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * 静默安装器。
 * 基于 PackageInstaller Session 提交，配合 [InstallResultReceiver] 接收系统回执。
 * 无需弹窗的条件：应用持有 INSTALL_PACKAGES 权限（system/privileged 签名）。
 *
 * 结果判定：仅靠系统广播在高版本上可能丢失（表现为安装成功但界面一直等待），
 * 因此 [installAndWait] 会同时轮询 PackageManager 中目标包的版本/安装时间变化来兜底。
 */
object SilentInstaller {

    data class InstallResult(
        val ok: Boolean,
        val packageName: String?,
        val message: String
    )

    /** 目标 APK 解析出的信息 */
    data class ApkMeta(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?
    )

    /** 安装前/轮询时某个已装包的快照 */
    private data class InstalledState(
        val versionCode: Long,
        val firstInstallTime: Long,
        val lastUpdateTime: Long
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

    private fun versionOf(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()

    /** 读取已安装包的状态；未安装返回 null。 */
    private fun installedState(context: Context, packageName: String): InstalledState? {
        val pm = context.packageManager
        return try {
            val pi = pm.getPackageInfo(packageName, 0)
            InstalledState(
                versionCode = versionOf(pi),
                firstInstallTime = pi.firstInstallTime,
                lastUpdateTime = pi.lastUpdateTime
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 解析本地 APK 文件的包信息；非有效 APK 返回 null。 */
    private fun parseApk(context: Context, file: File): ApkMeta? {
        val pm = context.packageManager
        val pi: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        }
        val pkg = pi?.packageName?.takeIf { it.isNotBlank() } ?: return null
        return ApkMeta(packageName = pkg, versionCode = versionOf(pi), versionName = pi.versionName)
    }

    private fun isUserConfirmPending(msg: String?): Boolean =
        msg?.contains("要求确认") == true

    /**
     * 提交一次安装，并等待确认完成。
     * - 先复制到缓存并解析包名；
     * - 记录安装前快照；
     * - 提交 Session 后同时监听系统广播与轮询目标包状态，直到明确成功/失败/超时。
     */
    suspend fun installAndWait(context: Context, uri: Uri, index: Int): InstallResult {
        val cached = File(
            context.cacheDir,
            "install_pending_${System.currentTimeMillis()}_$index.apk"
        )
        try {
            // 1. 复制到本地缓存（doc picker 的读取授权只在本次进程内稳定）
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法读取所选文件")
                input.use { source ->
                    cached.outputStream().use { dest -> source.copyTo(dest) }
                }
            }
            if (!cached.exists() || cached.length() <= 0L) {
                return InstallResult(false, null, "文件为空或读取失败")
            }

            // 2. 解析 APK（任意扩展名都可选，这里做真实校验）
            val meta = parseApk(context, cached)
            if (meta == null) {
                return InstallResult(false, null, "所选文件不是有效的 APK")
            }

            // 3. 安装前快照
            val before = installedState(context, meta.packageName)

            // 4. 提交安装
            val lastBroadcast = AtomicReference<InstallResult?>()
            install(context, Uri.fromFile(cached)) { result ->
                lastBroadcast.set(result)
            }

            // 5. 轮询目标包状态（辅助判定）+ 系统广播（快速失败反馈）
            val timeoutMs = 120_000L
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                delay(700)

                val bcast = lastBroadcast.get()
                if (bcast != null) {
                    when {
                        bcast.ok -> {
                            // 广播明确成功，立即结束
                            return InstallResult(
                                true,
                                meta.packageName,
                                "安装成功：${meta.packageName}"
                            )
                        }

                        !isUserConfirmPending(bcast.message) -> {
                            // 广播为硬性失败（如签名/兼容性错误）
                            return bcast.copy(packageName = meta.packageName)
                        }
                        // else: 系统要求人工确认，继续轮询等待最终结果
                    }
                }

                // 轮询：目标包出现或版本/安装时间发生变化
                val now = installedState(context, meta.packageName)
                val changed = if (now == null) {
                    false
                } else if (before == null) {
                    true
                } else {
                    now.versionCode != before.versionCode ||
                        now.firstInstallTime != before.firstInstallTime ||
                        now.lastUpdateTime != before.lastUpdateTime
                }
                if (changed) {
                    return InstallResult(true, meta.packageName, "安装成功：${meta.packageName}")
                }
            }

            // 6. 超时兜底：若广播给了失败信息则带回，否则提示无法确认
            val bcast = lastBroadcast.get()
            return if (bcast != null && !bcast.ok) {
                bcast.copy(packageName = meta.packageName)
            } else {
                InstallResult(
                    false,
                    meta.packageName,
                    "安装等待超时，请在系统应用管理中确认 ${meta.packageName} 的实际状态"
                )
            }
        } finally {
            runCatching { cached.delete() }
        }
    }

    /** 提交安装会话（异步）。结果经系统广播回调，见 [InstallResultReceiver]。 */
    private fun install(context: Context, apkFile: File, onResult: (InstallResult) -> Unit) {
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
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ).intentSender

                val session = installer.openSession(createdId)
                try {
                    val input = apkFile.inputStream()
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
