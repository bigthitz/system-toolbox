package com.system.toolbox.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * 应用限时：云端时间配置的模型、本地缓存与拉取策略。
 *
 * 云端 JSON 格式：
 * {
 *   "enabled": true,
 *   "windows": [ {"start":"07:00","end":"20:00"}, ... ],        // 周一~周五允许使用的时段
 *   "weekendWindows": [ {"start":"09:00","end":"21:00"}, ... ]  // 周六/周日允许使用的时段
 * }
 *
 * 规则：
 * - enabled=false 或所有时段为空 → 不限制（解禁全部受管应用）；
 * - weekendWindows 缺失 → 周末沿用 windows；存在但为空 → 周末不限制；
 * - start > end 表示跨午夜时段（如 20:00-06:00）；
 * - 缓存每 24 小时向云端更新一次，更新失败回退原始缓存；
 * - 从未成功拉取且无缓存 → 视为不限制（避免锁死设备）。
 */
object AppLimit {

    /** 云端配置地址：https 优先，失败回退 http（与公告拉取策略一致） */
    private val CONFIG_URLS = arrayOf(
        "https://eebbk.bbroot.com/app_limit.php",
        "http://eebbk.bbroot.com/app_limit.php"
    )

    private const val CACHE_FILE = "app_limit_config.json"

    /** 正常更新周期：24 小时 */
    private const val FETCH_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 有缓存时更新失败的重试间隔（期间继续用旧缓存） */
    private const val RETRY_WITH_CACHE_MS = 60L * 60 * 1000

    /** 无缓存时拉取失败的重试间隔 */
    private const val RETRY_NO_CACHE_MS = 5L * 60 * 1000

    /** 一个允许使用的时段（start/end 为当日分钟数 [0,1440)）。start > end 表示跨午夜。 */
    data class TimeWindow(val startMinutes: Int, val endMinutes: Int) {
        val crossesMidnight: Boolean get() = startMinutes > endMinutes

        fun contains(minuteOfDay: Int): Boolean = if (crossesMidnight) {
            minuteOfDay >= startMinutes || minuteOfDay < endMinutes
        } else {
            minuteOfDay >= startMinutes && minuteOfDay < endMinutes
        }

        override fun toString(): String = "${hm(startMinutes)}-${hm(endMinutes)}"
    }

    /**
     * 云端下发的时间配置。
     * @param weekendWindows 周六/周日单独时段；null=周末沿用 windows；空=周末不限制
     */
    data class Config(
        val enabled: Boolean,
        val windows: List<TimeWindow>,
        val weekendWindows: List<TimeWindow>? = null
    ) {

        /** 是否完全不限制（开关关闭或平日与周末时段均为空） */
        val unrestricted: Boolean
            get() = !enabled || (windows.isEmpty() && weekendWindows.isNullOrEmpty())

        /** 指定日期适用的时段：周末且已单独配置周末时段时用 weekendWindows，否则用 windows。 */
        fun windowsFor(cal: Calendar): List<TimeWindow> {
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val weekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
            return if (weekend && weekendWindows != null) weekendWindows else windows
        }

        fun allowedAt(cal: Calendar): Boolean {
            val wins = windowsFor(cal)
            if (wins.isEmpty()) return true // 当日未配置时段 = 当日不限制
            val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            return wins.any { it.contains(minute) }
        }

        fun allowedNow(): Boolean = allowedAt(Calendar.getInstance())
    }

    /** 本地缓存的配置（含成功拉取时间） */
    data class Cached(val config: Config, val fetchedAt: Long)

    /** 手动强制刷新的结果 */
    sealed class RefreshResult {
        data class Success(val cached: Cached) : RefreshResult()
        data class Fallback(val cached: Cached?) : RefreshResult()
    }

    private val lock = Any()
    private var lastAttempt = 0L
    private var memConfig: Cached? = null
    private var memLoaded = false

    /** 读取当前生效的本地缓存（UI 展示用）。 */
    suspend fun loadCached(context: Context): Cached? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!memLoaded) {
                memConfig = readCacheFile(context)
                memLoaded = true
            }
            memConfig
        }
    }

    /**
     * 获取当前生效配置（服务扫描周期调用）：
     * - 缓存超过 24 小时（或无缓存）时尝试云端更新；
     * - 更新失败回退原始缓存；无缓存且失败返回 null（视为不限制）。
     */
    suspend fun ensureFresh(context: Context): Cached? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!memLoaded) {
                memConfig = readCacheFile(context)
                memLoaded = true
            }
            val now = System.currentTimeMillis()
            val stale = (memConfig?.fetchedAt ?: 0L) + FETCH_INTERVAL_MS <= now
            val retryGap = if (memConfig != null) RETRY_WITH_CACHE_MS else RETRY_NO_CACHE_MS
            if (stale && now - lastAttempt >= retryGap) {
                lastAttempt = now
                val remote = fetchRemote()
                if (remote != null) {
                    val fresh = Cached(remote, now)
                    writeCacheFile(context, fresh)
                    memConfig = fresh
                }
                // 更新失败：保留原缓存（回退）
            }
            memConfig
        }
    }

    /** 强制立即向云端刷新（手动触发）。失败时回退缓存。 */
    suspend fun refreshNow(context: Context): RefreshResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!memLoaded) {
                memConfig = readCacheFile(context)
                memLoaded = true
            }
            lastAttempt = System.currentTimeMillis()
            val remote = fetchRemote()
            if (remote != null) {
                val fresh = Cached(remote, System.currentTimeMillis())
                writeCacheFile(context, fresh)
                memConfig = fresh
                RefreshResult.Success(fresh)
            } else {
                RefreshResult.Fallback(memConfig)
            }
        }
    }

    // ---- 缓存文件读写 ----

    private fun readCacheFile(context: Context): Cached? {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val config = parse(json.optJSONObject("config")?.toString() ?: return null)
            if (config == null) null else Cached(config, json.optLong("fetchedAt", 0L))
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCacheFile(context: Context, cached: Cached) {
        try {
            val windows = JSONArray()
            for (w in cached.config.windows) {
                windows.put(
                    JSONObject()
                        .put("start", hm(w.startMinutes))
                        .put("end", hm(w.endMinutes))
                )
            }
            val config = JSONObject()
                .put("enabled", cached.config.enabled)
                .put("windows", windows)
            cached.config.weekendWindows?.let { weekend ->
                val weekendArr = JSONArray()
                for (w in weekend) {
                    weekendArr.put(
                        JSONObject()
                            .put("start", hm(w.startMinutes))
                            .put("end", hm(w.endMinutes))
                    )
                }
                config.put("weekendWindows", weekendArr)
            }
            val json = JSONObject()
                .put("fetchedAt", cached.fetchedAt)
                .put("config", config)
            File(context.filesDir, CACHE_FILE).writeText(json.toString())
        } catch (_: Exception) {
            // 缓存写入失败不影响运行（内存中仍持有本次结果）
        }
    }

    // ---- 云端拉取与解析 ----

    private fun fetchRemote(): Config? {
        for (url in CONFIG_URLS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SystemToolbox/1.0")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parse(body)?.let { return it }
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
        return null
    }

    /** 解析云端 JSON；格式非法返回 null（调用方回退缓存）。 */
    fun parse(body: String): Config? = try {
        val json = JSONObject(body.trim())
        val enabled = json.optBoolean("enabled", true)
        val wins = parseWindows(json.optJSONArray("windows"))
        // weekendWindows 缺失或为 JSON null → null（周末沿用 windows）；空数组 → 空列表（周末不限制）
        val weekendArr = json.optJSONArray("weekendWindows")
        val weekendWins = if (weekendArr == null) null else parseWindows(weekendArr)
        Config(enabled, wins, weekendWins)
    } catch (_: Exception) {
        null
    }

    private fun parseWindows(arr: JSONArray?): List<TimeWindow> {
        val wins = ArrayList<TimeWindow>()
        if (arr == null) return wins
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = parseHm(o.optString("start"))
            val e = parseHm(o.optString("end"))
            if (s != null && e != null && s != e) wins += TimeWindow(s, e)
        }
        return wins
    }

    /** "HH:mm" → 当日分钟数；非法返回 null。 */
    private fun parseHm(text: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return h * 60 + min
    }

    private fun hm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
}
