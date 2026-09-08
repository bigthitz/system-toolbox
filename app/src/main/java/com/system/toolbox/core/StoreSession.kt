package com.system.toolbox.core

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.system.toolbox.core.StoreApi.StoreApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** 商店下载 / 安装任务阶段 */
enum class TaskPhase { QUEUED, DOWNLOADING, INSTALLING, DONE, FAILED, CANCELLED }

/** 一条商店下载 / 安装任务 */
data class StoreTask(
    val id: Long,
    val app: StoreApp,
    val phase: TaskPhase = TaskPhase.QUEUED,
    val progress: Float? = null,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val message: String = "等待中…"
) {
    val isActive: Boolean
        get() = phase == TaskPhase.QUEUED || phase == TaskPhase.DOWNLOADING || phase == TaskPhase.INSTALLING
    val isFinished: Boolean
        get() = phase == TaskPhase.DONE || phase == TaskPhase.FAILED || phase == TaskPhase.CANCELLED
}

/** 字节数格式化（任务列表进度展示用） */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var u = 0
    while (value >= 1024.0 && u < units.lastIndex) {
        value /= 1024.0
        u++
    }
    return (if (u == 0) "%.0f".format(value) else "%.1f".format(value)) + units[u]
}

/**
 * 应用商店全局会话（进程级单例）。
 *
 * 设计目的：商店页从导航栈移除后，原先的搜索关键词 / 结果 / 下载任务会随
 * composable 一起销毁。本会话把「搜索状态 + 下载/安装任务」提升到进程级保存，
 * 因此返回主页再进入商店时，页面仍停留在刚才的搜索页，进行中的任务继续在后台
 * 运行且进度可随时查看（同时作为「任务列表」的数据源）。
 *
 * 说明：状态存活于进程内；若进程被杀，未完成任务会消失（下载中断）。
 */
object StoreSession {

    private const val MAX_CONCURRENT = 2

    // ---- 搜索页状态（跨页面保留）----
    var keyword by mutableStateOf("")
    var searching by mutableStateOf(false)
    var results by mutableStateOf<List<StoreApp>?>(null)
    var searchNotice by mutableStateOf<String?>(null)

    /** 0 = 搜索页，1 = 任务列表 */
    var selectedTab by mutableIntStateOf(0)

    // ---- 任务列表（进程级）----
    val tasks = mutableStateListOf<StoreTask>()

    private var appContext: Context? = null
    private var nextId = 1L
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lastEmit = ConcurrentHashMap<Long, Long>()
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 在 MainActivity 启动时注入 applicationContext（仅需一次）。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 某应用的进行中任务（用于结果卡片显示进度），无则 null。 */
    fun taskOf(appId: String): StoreTask? = tasks.firstOrNull { it.app.appId == appId }

    fun activeCount(): Int = tasks.count { it.isActive }

    /** 执行搜索（清空旧结果则由 UI 触发时处理）。 */
    fun search() {
        val kw = keyword.trim()
        if (kw.isEmpty() || searching) return
        searching = true
        searchNotice = null
        scope.launch {
            val list = try {
                StoreApi.search(kw)
            } catch (_: Exception) {
                emptyList()
            }
            results = list
            searching = false
            if (list.isEmpty()) searchNotice = "没有找到相关应用，请更换关键词"
        }
    }

    /** 为某个应用建立任务：无任务则入队；已完成/失败的旧任务则重置后重跑；进行中忽略。 */
    fun install(app: StoreApp) {
        val existing = taskOf(app.appId)
        if (existing != null) {
            if (existing.isActive) return
            updateTask(existing.id) {
                it.copy(
                    app = app,
                    phase = TaskPhase.QUEUED,
                    progress = null,
                    downloaded = 0L,
                    total = 0L,
                    message = "等待中…"
                )
            }
            launchWorker(existing.id)
        } else {
            val id = nextId++
            tasks += StoreTask(id = id, app = app)
            launchWorker(id)
        }
    }

    /** 重试一条已结束（失败/已取消）的任务。 */
    fun retry(taskId: Long) {
        val t = tasks.firstOrNull { it.id == taskId } ?: return
        if (t.isActive) return
        updateTask(taskId) {
            it.copy(
                phase = TaskPhase.QUEUED,
                progress = null,
                downloaded = 0L,
                total = 0L,
                message = "等待中…"
            )
        }
        launchWorker(taskId)
    }

    /** 取消排队中/下载中的任务（安装中不允许取消，避免安装状态不可控）。 */
    fun cancel(taskId: Long) {
        val t = tasks.firstOrNull { it.id == taskId } ?: return
        if (t.phase != TaskPhase.QUEUED && t.phase != TaskPhase.DOWNLOADING) return
        updateTask(taskId) { it.copy(phase = TaskPhase.CANCELLED, message = "正在取消…") }
        jobs.remove(taskId)?.cancel()
    }

    /** 移除一条已结束任务。 */
    fun dismiss(taskId: Long) {
        val t = tasks.firstOrNull { it.id == taskId } ?: return
        if (t.isActive) return
        tasks.removeAll { it.id == taskId }
        jobs.remove(taskId)
        lastEmit.remove(taskId)
    }

    /** 清空全部已完成/失败/已取消的任务。 */
    fun clearFinished() {
        tasks.removeAll { it.isFinished }
    }

    private fun updateTask(id: Long, transform: (StoreTask) -> StoreTask) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks[idx] = transform(tasks[idx])
        }
    }

    private fun launchWorker(taskId: Long) {
        val job = scope.launch {
            var acquired = false
            var file: File? = null
            try {
                val task = tasks.firstOrNull { it.id == taskId } ?: return@launch
                if (task.phase == TaskPhase.CANCELLED) return@launch
                val ctx = appContext ?: return@launch
                val app = task.app

                semaphore.acquire()
                acquired = true

                // 排队等待期间被取消则直接退出
                if (tasks.firstOrNull { it.id == taskId }?.phase == TaskPhase.CANCELLED) return@launch

                updateTask(taskId) {
                    it.copy(phase = TaskPhase.DOWNLOADING, progress = null, downloaded = 0L, total = 0L, message = "正在获取下载地址…")
                }
                val url = StoreApi.resolveDownloadUrl(app.appId)
                if (url.isNullOrBlank()) {
                    updateTask(taskId) {
                        it.copy(phase = TaskPhase.FAILED, progress = null, message = "获取下载地址失败")
                    }
                    return@launch
                }

                updateTask(taskId) { it.copy(message = "下载中…") }
                file = StoreApi.downloadApk(ctx, app.appId, url) { done, total ->
                    onDownloadProgress(taskId, done, total)
                }
                if (file == null) {
                    updateTask(taskId) {
                        it.copy(phase = TaskPhase.FAILED, progress = null, message = "下载失败")
                    }
                    return@launch
                }

                updateTask(taskId) {
                    it.copy(phase = TaskPhase.INSTALLING, progress = null, message = "正在静默安装…")
                }
                val result = SilentInstaller.installFile(ctx, file)
                if (result.ok) {
                    updateTask(taskId) { it.copy(phase = TaskPhase.DONE, message = "安装成功") }
                } else {
                    updateTask(taskId) { it.copy(phase = TaskPhase.FAILED, message = result.message) }
                }
            } catch (e: CancellationException) {
                if (!scope.isActive) throw e
                updateTask(taskId) {
                    it.copy(phase = TaskPhase.CANCELLED, progress = null, message = "已取消")
                }
            } catch (e: Exception) {
                updateTask(taskId) {
                    it.copy(phase = TaskPhase.FAILED, progress = null, message = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                // 下载产生的缓存 APK：安装完成后统一清理
                try {
                    file?.delete()
                } catch (_: Exception) {
                }
                if (acquired) semaphore.release()
            }
        }
        jobs[taskId] = job
    }

    private fun onDownloadProgress(taskId: Long, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        val last = lastEmit[taskId] ?: 0L
        val complete = total > 0 && done >= total
        if (!complete && now - last < 200L) return
        lastEmit[taskId] = now
        updateTask(taskId) { t ->
            val p = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
            t.copy(
                progress = if (complete) 1f else p,
                downloaded = done,
                total = if (total > 0) total else done,
                message = "下载中…"
            )
        }
    }
}
