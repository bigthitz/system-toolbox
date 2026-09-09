package com.system.toolbox.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/**
 * 远程自毁开关 + 联网门禁。
 *
 * 云端 use.php 返回纯文本：
 * - true  → 自毁：先尝试 pm uninstall 卸载自己，卸载失败则立即闪退（杀进程）；
 * - false → 正常使用；
 * - 拉取失败 → 视为无网络（MainActivity 据此禁止使用）。
 *
 * 一旦触发过自毁（本地置位待自毁标记），之后即使断网也会继续执行自毁，
 * 避免通过断网绕过。
 */
object SelfDestruct {

    /** 云端开关地址：https 优先，失败回退 http（与公告/限时配置策略一致） */
    private val USE_URLS = arrayOf(
        "https://eebbk.bbroot.com/use.php",
        "http://eebbk.bbroot.com/use.php"
    )

    private const val PREFS = "self_destruct"
    private const val KEY_PENDING = "pending_destroy"

    /** 启动门禁结果 */
    sealed class Gate {
        /** 云端明确允许使用 */
        object Proceed : Gate()

        /** 无法联网（禁止使用） */
        object NoNetwork : Gate()

        /** 云端要求自毁（或本地待自毁标记已置位） */
        object Destroy : Gate()
    }

    /**
     * 拉取云端自毁开关。
     * @return true=自毁；false=正常；null=无法访问（离线）
     */
    suspend fun fetchFlag(): Boolean? = withContext(Dispatchers.IO) {
        for (url in USE_URLS) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "SystemToolbox/1.0")
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return@withContext parseFlag(body)
                }
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

    /**
     * 解析云端返回。兼容多种格式：
     * 纯文本 true/false（use.php 默认）、JSON 布尔、JSON 对象 {"self_destruct":true} 等。
     */
    private fun parseFlag(body: String): Boolean? {
        val text = body.trim()
        if (text.isEmpty()) return null
        if (text.equals("true", true) || text.equals("yes", true) || text == "1") return true
        if (text.equals("false", true) || text.equals("no", true) || text == "0") return false
        return try {
            val json = JSONObject(text)
            for (key in listOf("self_destruct", "selfDestruct", "destroy")) {
                if (json.has(key)) return json.optBoolean(key)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** 本地是否已置位「待自毁」标记（触发过一次自毁后永久生效）。 */
    private fun pendingDestroy(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)

    /**
     * 启动门禁检查（MainActivity 调用）：
     * - 待自毁标记已置位 → 直接 Destroy（断网无法绕过）；
     * - 云端 true → Destroy；false → Proceed；不可达 → NoNetwork。
     */
    suspend fun gate(context: Context): Gate {
        if (pendingDestroy(context)) return Gate.Destroy
        return when (fetchFlag()) {
            true -> Gate.Destroy
            false -> Gate.Proceed
            null -> Gate.NoNetwork
        }
    }

    /**
     * 执行自毁（阻塞方法，需在 IO/Default 协程中调用）：
     * 依次尝试多种卸载方式；卸载成功时本进程会被系统直接杀死；
     * 若卸载失败则闪退（杀进程 + exit 兜底）。
     */
    fun destroy(context: Context) {
        val appContext = context.applicationContext
        markPending(appContext)
        try {
            uninstall("pm", "uninstall", "--user", "0", appContext.packageName)
            uninstall("pm", "uninstall", appContext.packageName)
        } catch (_: Exception) {
        }
        // 卸载成功时进程会被杀，走不到这里；稍等系统完成处理
        try {
            Thread.sleep(1500)
        } catch (_: InterruptedException) {
        }
        if (!isInstalled(appContext)) {
            // 包已被移除但进程仍在：直接退出
            Runtime.getRuntime().exit(0)
        }
        // 卸载失败 → 闪退
        crash()
    }

    /** 置位待自毁标记（同步写，确保杀进程前落盘）。 */
    private fun markPending(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, true)
                .commit()
        } catch (_: Exception) {
        }
    }

    private fun uninstall(vararg cmd: String) {
        try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
        } catch (_: Exception) {
        }
    }

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        true // 无法判断时按仍安装处理（走闪退分支）
    }

    /** 闪退：先杀自身进程，若未死再 exit 兜底。 */
    fun crash() {
        try {
            Thread.sleep(300) // 给可能的卸载处理一点时间
        } catch (_: InterruptedException) {
        }
        Process.killProcess(Process.myPid())
        exitProcess(1)
    }
}
