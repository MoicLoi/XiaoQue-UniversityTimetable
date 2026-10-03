package com.xiqueer.android.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.xiqueer.android.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.random.Random

/**
 * 选课监听的前台服务:每轮之间**随机**等 2–3 分钟,安静时段不请求,超过 7 天自动停。
 *
 * 为什么要前台服务:轮次之间要活过 Doze 与后台限制。挂一个 LOW 重要性的常驻通知
 * 也顺带满足"用户随时知道它在跑"——监听本身就是个需要自觉的行为。
 *
 * 防封的三条都写在 [WatchConfig] 里,而且这里是**唯一**发探测请求的地方:
 * - 间隔随机(固定轮询节奏最像脚本)
 * - 00:00–08:00 不请求
 * - 连续 7 天封顶
 */
class SelectionWatchService : Service() {

    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        startForeground(Notifications.ID_WATCH, Notifications.watchOngoing(this, "正在检查…"))

        if (scope != null) return START_NOT_STICKY // 已在跑,别叠第二个循环
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { loop() }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        val store = WatchSettingsStore(this)
        try {
            while (scope?.isActive == true) {
                val c = store.load()
                if (!c.enabled) {
                    Log.i(TAG, "watch disabled → stop")
                    break
                }
                if (c.exceededMaxDays(System.currentTimeMillis())) {
                    Log.i(TAG, "watch exceeded ${c.maxDays} days → auto stop")
                    store.save(c.copy(enabled = false))
                    store.clearRun()
                    break
                }

                // 安静时段:直接睡到结束,不空转 —— 空转也是在打日志、耗电
                val hour = LocalTime.now().hour
                if (c.isQuiet(hour)) {
                    val waitMs = millisUntilQuietEnd(c)
                    Log.i(TAG, "quiet hour → sleep ${waitMs / 60000} min")
                    update("安静时段,${c.quietEndHour}:00 后继续")
                    delay(waitMs)
                    continue
                }

                val result = SelectionProbe.probe(this)
                store.markPoll()
                if (result.error != null) {
                    Log.w(TAG, "probe error: ${result.error}")
                    update("上轮出错,稍后重试")
                } else {
                    val fresh = store.recordSignals(result.hits.map { it.key }.toSet())
                    val hit = result.hits.firstOrNull { it.key in fresh }
                    if (hit != null) {
                        Log.i(TAG, "signal hit: ${hit.title}")
                        Notifications.selectionAlert(this, hit.title, hit.detail)
                    }
                    update("已检查 ${store.pollCount} 轮(弱信号 ${result.hits.size} 条)")
                }

                // 随机间隔:同一秒数重复轮询是最容易被风控认出来的特征
                val sec = Random.nextInt(c.minIntervalSec, c.maxIntervalSec + 1)
                Log.i(TAG, "next poll in ${sec}s")
                delay(sec * 1000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "watch loop died", e)
        } finally {
            stopSelf()
        }
    }

    private fun millisUntilQuietEnd(c: WatchConfig): Long {
        val now = LocalTime.now()
        var target = LocalTime.of(c.quietEndHour.coerceIn(0, 23), 0)
        // 已过或正处于安静时段:安静时段跨午夜时(如 23:00–06:00)目标可能是明天
        if (!target.isAfter(now)) target = target.plusHours(24)
        val ms = java.time.Duration.between(now, target).toMillis()
        return ms.coerceIn(60_000L, 12L * 3600_000L)
    }

    private fun update(text: String) {
        runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.notify(Notifications.ID_WATCH, Notifications.watchOngoing(this, text))
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XqWatch"
    }
}

/**
 * 监听的开与关。
 *
 * **只有一个入口** —— 设置页与 AI 工具都调这里,避免状态分叉。
 */
object SelectionWatch {

    private const val TAG = "XqWatch"

    /**
     * 按当前配置对齐运行状态。
     *
     * @param fromBackground 由开机广播等后台路径调用。API 34+ 不允许后台起 dataSync
     *   前台服务,这种情况只记日志,等用户下次打开 App 再由前台路径拉起 —— 不能崩在开机里。
     */
    fun resync(context: Context, fromBackground: Boolean = false) {
        val store = WatchSettingsStore(context)
        val c = store.load()
        if (!c.enabled) {
            stop(context)
            return
        }
        if (c.exceededMaxDays(System.currentTimeMillis())) {
            store.save(c.copy(enabled = false))
            store.clearRun()
            stop(context)
            return
        }
        if (fromBackground && Build.VERSION.SDK_INT >= 34) {
            Log.i(TAG, "API 34+: 不在后台启动 dataSync 前台服务,等前台再拉起")
            return
        }
        start(context)
    }

    fun start(context: Context) {
        val intent = Intent(context, SelectionWatchService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { Log.e(TAG, "start failed", it) }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, SelectionWatchService::class.java)) }
            .onFailure { Log.w(TAG, "stop failed", it) }
    }

    /** 用户手动开启:重置本轮计时与去重集合。 */
    fun enable(context: Context) {
        val store = WatchSettingsStore(context)
        store.save(store.load().copy(enabled = true))
        store.startNewRun()
        start(context)
    }

    fun disable(context: Context) {
        val store = WatchSettingsStore(context)
        store.save(store.load().copy(enabled = false, startedAtMillis = 0L))
        store.clearRun()
        stop(context)
    }
}
