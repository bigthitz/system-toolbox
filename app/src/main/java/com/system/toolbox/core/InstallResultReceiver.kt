package com.system.toolbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * 接收 PackageInstaller 提交后的安装结果广播。
 * 由 [SilentInstaller] 的 PendingIntent 显式触发，仅本应用可见。
 *
 * 说明：需要用户确认的场景极少发生（system 身份安装不会触发），
 * 若出现则通过 Intent.EXTRA_INTENT 取出系统下发的确认界面并拉起。
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
                val confirmIntent = readParcelable(intent)
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
                SilentInstaller.deliverResult(
                    context,
                    sessionId,
                    SilentInstaller.InstallResult(
                        false,
                        packageName,
                        statusMessage?.takeIf { it.isNotBlank() }
                            ?: "安装失败（状态码 $status）"
                    )
                )
            }
        }
    }

    private fun readParcelable(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
