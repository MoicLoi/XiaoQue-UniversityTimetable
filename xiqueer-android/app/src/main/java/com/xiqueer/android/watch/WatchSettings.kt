package com.xiqueer.android.watch

import android.content.Context

/**
 * 选课监听配置(防封参数)。
 *
 * 默认**关闭**;开启后:
 * - 每轮间隔在 [minIntervalSec]..[maxIntervalSec] 之间**随机**(默认 2–3 分钟)
 * - [quietStartHour]..[quietEndHour] 为安静时段(默认 00:00–08:00)不请求
 * - 连续运行超过 [maxDays] 天自动停止(默认 7 天)
 */
data class WatchConfig(
    val enabled: Boolean = false,
    val minIntervalSec: Int = 120,
    val maxIntervalSec: Int = 180,
    val quietStartHour: Int = 0,
    val quietEndHour: Int = 8,
    val maxDays: Int = 7,
    /** 本轮连续监听开始时间;0 表示未开始。 */
    val startedAtMillis: Long = 0L,
) {
    val intervalRangeLabel: String get() = "${minIntervalSec / 60}–${maxIntervalSec / 60} 分钟(随机)"
    val quietLabel: String get() = "%02d:00–%02d:00".format(quietStartHour, quietEndHour)

    fun isQuiet(hour: Int): Boolean =
        if (quietStartHour == quietEndHour) false
        else if (quietStartHour < quietEndHour) hour in quietStartHour until quietEndHour
        else hour >= quietStartHour || hour < quietEndHour

    /** 连续监听是否已超上限。 */
    fun exceededMaxDays(nowMillis: Long): Boolean =
        startedAtMillis > 0 && nowMillis - startedAtMillis > maxDays * 24L * 3600_000L
}

class WatchSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun load(): WatchConfig = WatchConfig(
        enabled = prefs.getBoolean("enabled", false),
        minIntervalSec = prefs.getInt("min_interval", 120),
        maxIntervalSec = prefs.getInt("max_interval", 180),
        quietStartHour = prefs.getInt("quiet_start", 0),
        quietEndHour = prefs.getInt("quiet_end", 8),
        maxDays = prefs.getInt("max_days", 7),
        startedAtMillis = prefs.getLong("started_at", 0L),
    )

    fun save(c: WatchConfig) {
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putInt("min_interval", c.minIntervalSec.coerceIn(30, 3600))
            .putInt("max_interval", c.maxIntervalSec.coerceIn(30, 3600))
            .putInt("quiet_start", c.quietStartHour.coerceIn(0, 23))
            .putInt("quiet_end", c.quietEndHour.coerceIn(0, 23))
            .putInt("max_days", c.maxDays.coerceIn(1, 30))
            .putLong("started_at", c.startedAtMillis)
            .apply()
    }

    fun resetRun() = prefs.edit().putLong("started_at", System.currentTimeMillis()).apply()

    // ---- 运行留痕:监听是"每 2-3 分钟打一次学校接口"的行为,必须能自查 ----

    /** 已完成的轮数(本轮连续监听内)。 */
    var pollCount: Int
        get() = prefs.getInt("poll_count", 0)
        private set(v) = prefs.edit().putInt("poll_count", v).apply()

    /** 最近一轮的时间。 */
    var lastPollMillis: Long
        get() = prefs.getLong("last_poll", 0L)
        private set(v) = prefs.edit().putLong("last_poll", v).apply()

    /** 本轮已命中的信号(去重用,换行分隔)。 */
    var seenSignals: Set<String>
        get() = prefs.getStringSet("seen", emptySet()).orEmpty()
        private set(v) = prefs.edit().putStringSet("seen", v).apply()

    fun markPoll(nowMillis: Long = System.currentTimeMillis()) {
        pollCount += 1
        lastPollMillis = nowMillis
    }

    /** 记录信号,返回**本次新增**的那些(只有新增才推通知,避免每轮都响)。 */
    fun recordSignals(keys: Set<String>): Set<String> {
        val fresh = keys - seenSignals
        if (fresh.isNotEmpty()) seenSignals = seenSignals + fresh
        return fresh
    }

    /** 重新开始一轮监听:清零计数与已见信号。 */
    fun startNewRun() {
        pollCount = 0
        lastPollMillis = 0L
        seenSignals = emptySet()
        resetRun()
    }

    fun clearRun() {
        pollCount = 0
        lastPollMillis = 0L
        seenSignals = emptySet()
    }
}
