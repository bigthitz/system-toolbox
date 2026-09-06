package com.system.toolbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * 接收 PackageInstaller 提交后的安装结果广播。
 * 由 [SilentInstaller] 的 PendingIntent 显式触发，仅本应用可用。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                SilentInstaller.deliverResult(
                    context,
                    sessionId,
                    SilentInstaller.InstallResult(
                        true,
                        packageName,
                        if (packageName.isNullOrBlank()) "安装成功" else "安装成功：$packageName"
                    )
                )
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            PackageInstaller.EXTRA_STATUS_INTENT,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(PackageInstaller.EXTRA_STATUS_INTENT)
                    }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
                SilentInstaller.deliverResult(
                    context,
                    sessionId,
                    SilentInstaller.InstallResult(
                        false,
                        packageName,
                        "系统要求确认，已弹出安装确认界面"
                    )
                )
            }

            else -> {
                val legacy = intent.getIntExtra(PackageInstaller.EXTRA_LEGACY_STATUS, 0)
                val fallback = if (legacy != 0) legacy else status
                SilentInstaller.deliverResult(
                    context,
                    sessionId,
                    SilentInstaller.InstallResult(
                        false,
                        packageName,
                        statusMessage?.takeIf { it.isNotBlank() } ?: "安装失败（错误码 $fallback）"
                    )
                )
            }
        }
    }
}
