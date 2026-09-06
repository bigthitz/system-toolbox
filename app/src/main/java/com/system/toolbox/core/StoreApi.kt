package com.system.toolbox.core

import android.content.Context
import android.util.LruCache
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * 豌豆荚应用商店数据层。
 * 搜索走 wandoujia 的 JSON+HTML 接口，下载链接由 server-m.pp.cn 返回后流式下载。
 * 解析规则与 tools/wandou.py 保持一致。
 */
object StoreApi {

    data class StoreApp(
        val appId: String,
        val name: String,
        val packageName: String,
        val versionName: String,
        val versionCode: String,
        val installCount: String,
        val description: String,
        val iconUrl: String,
        val detailUrl: String
    )

    private const val SEARCH_API = "https://www.wandoujia.com/wdjweb/api/search/more"
    private const val DOWNLOAD_URL_API = "https://server-m.pp.cn/download/url"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val TIMEOUT = 20_000

    /** 搜索应用，page 从 1 开始。 */
    suspend fun search(keyword: String, page: Int = 1): List<StoreApp> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_API?key=${URLEncoder.encode(keyword, "UTF-8")}&page=$page"
            val body = httpGet(url, referer = "https://www.wandoujia.com/")
            val json = JSONObject(body)
            val state = json.optJSONObject("state")
            if (state == null || state.optInt("code") != 2000000) {
                return@withContext emptyList()
            }
            val html = json.optJSONObject("data")?.optString("content") ?: return@withContext emptyList()
            parseSearchResults(html)
        }

    private fun parseSearchResults(html: String): List<StoreApp> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val out = ArrayList<StoreApp>()
        for (li in doc.select("li.search-item")) {
            val btn = li.selectFirst("a.detail-check-btn")
            val appId = btn?.attr("data-app-id")?.trim().orEmpty()
            if (appId.isEmpty()) continue

            val nameTag = li.selectFirst("a.name")
            val name = nameTag?.text()?.trim().orEmpty().ifEmpty { "未知应用" }
            val iconTag = li.selectFirst("img.icon")
            val icon = btn?.attr("data-app-icon")?.trim().orEmpty()
                .ifEmpty { iconTag?.attr("src")?.trim().orEmpty() }

            var installCount = ""
            val countTag = li.selectFirst("span.install-count")
                ?: li.selectFirst("div.meta span")
            if (countTag != null) installCount = countTag.text().trim()

            val desc = li.selectFirst("div.comment")?.text()?.trim().orEmpty()
            out += StoreApp(
                appId = appId,
                name = name,
                packageName = btn?.attr("data-app-pname")?.trim().orEmpty(),
                versionName = btn?.attr("data-app-vname")?.trim().orEmpty(),
                versionCode = btn?.attr("data-app-vcode")?.trim().orEmpty(),
                installCount = installCount,
                description = desc,
                iconUrl = icon,
                detailUrl = nameTag?.attr("href")?.trim().orEmpty()
            )
        }
        return out
    }

    /** 获取 APK 直链；失败返回 null。 */
    suspend fun resolveDownloadUrl(appId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = "$DOWNLOAD_URL_API?appId=$appId"
                val body = httpGet(
                    url,
                    referer = "https://www.wandoujia.com/",
                    accept = "application/json, text/plain, */*"
                )
                val json = JSONObject(body)
                val state = json.optJSONObject("state")
                if (state != null && state.optInt("code") == 2000000) {
                    json.optString("data").takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 下载 APK 到 cache 目录。
     * @param onProgress 下载进度（已下载字节, 总字节；总字节可能为 -1 表示未知）
     * @return 下载完成的文件；失败返回 null
     */
    suspend fun downloadApk(
        context: Context,
        appId: String,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = open(url, referer = "https://www.wandoujia.com/", accept = "*/*")
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null

            val dir = File(context.cacheDir, "store").apply { mkdirs() }
            val file = File(dir, "${sanitize(appId)}.apk")
            file.outputStream().use { out ->
                val input = conn.inputStream
                var downloaded = 0L
                val total = conn.contentLengthLong.coerceAtLeast(0L)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        // 被取消时（后台任务停止/用户点取消）尽快中断，避免继续写磁盘
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        coroutineContext.ensureActive()
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, if (total > 0) total else -1L)
                    }
                } finally {
                    try {
                        input.close()
                    } catch (_: Exception) {
                    }
                }
            }
            if (file.length() <= 0L) {
                file.delete()
                null
            } else {
                file
            }
        } catch (e: CancellationException) {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
            try {
                // 清理下载到一半的残留文件
                File(File(context.cacheDir, "store"), "${sanitize(appId)}.apk").delete()
            } catch (_: Exception) {
            }
            throw e
        } catch (_: Exception) {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(60)

    private fun httpGet(
        url: String,
        referer: String? = null,
        accept: String = "application/json, text/html, */*"
    ): String {
        val conn = open(url, referer = referer, accept = accept)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, referer: String?, accept: String): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Accept", accept)
            if (referer != null) setRequestProperty("Referer", referer)
        }
        return conn
    }
}

/** 极简远程图片缓存，供商店列表加载应用图标。 */
object StoreIconLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
                val stream = conn.inputStream
                try {
                    BitmapFactory.decodeStream(stream)
                } finally {
                    stream.close()
                    conn.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (bitmap != null) cache.put(url, bitmap)
        return bitmap
    }
}
