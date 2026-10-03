package com.xiqueer.android

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import androidx.work.Configuration
import com.xiqueer.android.data.OkHttpTransport
import com.xiqueer.android.notify.Notifications
import com.xiqueer.android.sync.DailyRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application。
 *
 * 启动路径(P3a,实测):
 * - **通知渠道与后台任务的注册都挪到后台线程**。它们都不需要在首帧前完成 ——
 *   具体代价见下面 [onCreate] 里的注释,那里是真实的崩溃栈与耗时来源。
 * - 重排闹钟要读缓存文件,同样放后台([AppViewModel] 里)。
 *
 * 也就是:首帧只等 Compose 把界面画出来,别的一律不等。
 */
class XqApp : Application(), Configuration.Provider {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * WorkManager 的按需初始化配置。
     *
     * 我们在 manifest 里 **删掉了** `androidx.work.WorkManagerInitializer` 这个
     * startup 项 —— 它原本会在 `InitializationProvider.onCreate` 里同步跑完,
     * 而那就是启动路径上最贵的一件事:WorkManager 首次初始化会打开 Room 数据库(SQLite)。
     *
     * 删掉之后 WorkManager 变成"第一次 `getInstance()` 时才初始化",
     * 而我们的第一次调用在后台线程里([DailyRefreshWorker.enqueue])。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // 为什么不在主线程做这两件事:
        // 1. `WorkManager.getInstance()` 过去由 startup 在启动时同步触发,
        //    它要建/开 Room 库,栈是
        //        InitializationProvider.onCreate → WorkManagerInitializer →
        //        WorkDatabase_Impl.<init> → SQLite open
        //    —— 首帧完全不必等它。
        // 2. 建通知渠道只影响"发通知那一刻",而每个接收器
        //    (ClassAlarmReceiver / DigestAlarmReceiver / SelectionWatchService)
        //    都会自己再 ensure 一次,所以延后是安全的。
        appScope.launch {
            runCatching { Notifications.ensureChannels(this@XqApp) }
                .onFailure { Log.w(TAG, "ensureChannels failed", it) }
            runCatching { DailyRefreshWorker.enqueue(this@XqApp) }
                .onFailure { Log.w(TAG, "enqueue refresh worker failed", it) }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            // UI 不可见:先什么都不做。用户可能马上切回来,清缓存反而要重建
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "onTrimMemory: UI hidden")
            }
            // 后台压力大:清闲置连接
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            -> {
                OkHttpTransport.client.connectionPool.evictAll()
                Log.d(TAG, "onTrimMemory: evicted connection pool ($level)")
            }
            // 前台也在吃紧 / 系统即将杀进程:能放的全放
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> {
                OkHttpTransport.client.connectionPool.evictAll()
                Log.w(TAG, "onTrimMemory: critical ($level)")
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        OkHttpTransport.client.connectionPool.evictAll()
        Log.w(TAG, "onLowMemory")
    }

    private companion object {
        const val TAG = "XqApp"
    }
}
