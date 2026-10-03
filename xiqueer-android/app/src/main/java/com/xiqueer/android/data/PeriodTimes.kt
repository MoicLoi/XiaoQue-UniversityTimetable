package com.xiqueer.android.data

import android.content.Context

/** 某一节的作息。`end` 可缺省 —— 我们只需要开始时间来算提醒。 */
data class PeriodTimeSlot(
    val start: String,
    val end: String? = null,
) {
    /** `08:00-08:45` 或只有 `08:00`。 */
    override fun toString(): String = if (end.isNullOrBlank()) start else "$start-$end"
}

/**
 * 作息时间从哪来。
 *
 * ⚠️ **绝对不要内置一份"常见作息"当默认值。** 各校作息差异很大(有的 8:00 开始、
 * 有的 8:30;有的两节连排 90 分钟),凭空写死等于让所有学校的提醒都错。
 * 学校未提供时就是 [None],此时只按**节次**提醒,不带时钟。
 */
enum class PeriodTimeSource(val label: String) {
    /** 未配置 —— 只能做「第几节」级别的提醒。 */
    None("未配置"),

    /** 用户手动填写。 */
    Manual("手动填写"),

    /** 由 AI 从作息表导入(P6)。 */
    Imported("AI 导入"),

    /** 服务端下发(`sksj/obtainSksj`,目前本校为空)。 */
    Server("学校提供"),
}

/** 完整的作息表。 */
data class PeriodTimes(
    val source: PeriodTimeSource = PeriodTimeSource.None,
    /** index 0 = 第 1 节。允许长度不足 —— 缺的节次就没有时间。 */
    val slots: List<PeriodTimeSlot> = emptyList(),
) {
    /** 是否具备算绝对时间的能力。 */
    val configured: Boolean get() = source != PeriodTimeSource.None && slots.any { it.start.isNotBlank() }

    fun startOf(period: Int): String? = slots.getOrNull(period - 1)?.start?.takeIf { it.isNotBlank() }

    fun endOf(period: Int): String? = slots.getOrNull(period - 1)?.end?.takeIf { it.isNotBlank() }

    companion object {
        val Empty = PeriodTimes()

        /**
         * 解析「逗号分隔的作息」:`08:00-08:45,08:55-09:40`。
         *
         * 手动填写、AI 导入、存储反序列化**共用这一份解析** —— 三处各写一遍必然会长歪。
         * 非法项直接丢弃(而不是猜一个时间)。
         */
        fun parseSlots(raw: String): List<PeriodTimeSlot> =
            raw.split(',', '，').mapNotNull { seg ->
                val s = seg.trim()
                if (s.isEmpty()) return@mapNotNull null
                val dash = s.indexOf('-')
                val start = if (dash > 0) s.substring(0, dash).trim() else s
                val end = if (dash > 0) s.substring(dash + 1).trim().takeIf { it.isNotEmpty() } else null
                if (!isClock(start)) return@mapNotNull null
                PeriodTimeSlot(start, end?.takeIf(::isClock))
            }

        fun isClock(s: String): Boolean =
            Regex("^\\d{1,2}:\\d{2}$").matches(s) &&
                s.substringBefore(':').toIntOrNull() in 0..23 &&
                s.substringAfter(':').toIntOrNull() in 0..59
    }
}

/**
 * 作息表与提醒设置。
 *
 * **默认是空的**(`source = None`)。首次进课表时会提示用户:当前只能按节次提醒,
 * 可以手动填写,或在 P6 里让 AI 从作息表导入。
 */
class PeriodTimesStore(context: Context) {

    private val prefs = context.getSharedPreferences("period_times", Context.MODE_PRIVATE)

    /** 用户是否已经看过"未配置作息"的提示。 */
    var noticeShown: Boolean
        get() = prefs.getBoolean("notice_shown", false)
        set(v) = prefs.edit().putBoolean("notice_shown", v).apply()

    /** 课前多少分钟提醒 —— 只有在 [PeriodTimes.configured] 时才起作用。 */
    var leadMinutes: Int
        get() = prefs.getInt("lead", 20)
        set(v) = prefs.edit().putInt("lead", v.coerceIn(1, 120)).apply()

    /** 课前提醒总开关。 */
    var reminderEnabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** 每日课表摘要。**不依赖作息表**,所以未配置时它是唯一的推送。 */
    var digestEnabled: Boolean
        get() = prefs.getBoolean("digest", true)
        set(v) = prefs.edit().putBoolean("digest", v).apply()

    /** 摘要时间,默认 07:00。 */
    var digestAt: String
        get() = prefs.getString("digest_at", "07:00").orEmpty()
        set(v) = prefs.edit().putString("digest_at", v).apply()

    fun load(): PeriodTimes {
        val raw = prefs.getString("times", null) ?: return PeriodTimes.Empty
        return parse(raw)
    }

    fun save(times: PeriodTimes) {
        prefs.edit().putString("times", format(times)).apply()
    }

    fun clear() {
        prefs.edit().remove("times").apply()
    }

    /**
     * 存储格式:`source;start[-end],start[-end],...`
     * 例:`manual;08:00-08:45,08:55-09:40` / `imported;08:00,08:55,10:00`
     *
     * 刻意用简单分隔符而不是 JSON —— 协议层的 `XqJson` 只有解析器没有序列化器,
     * 为这点数据引一个 JSON 库不值。
     */
    internal fun format(times: PeriodTimes): String {
        val body = times.slots.joinToString(",") { it.toString() }
        return "${times.source.name};$body"
    }

    internal fun parse(raw: String): PeriodTimes {
        val sep = raw.indexOf(';')
        if (sep <= 0) return PeriodTimes.Empty
        val source = runCatching { PeriodTimeSource.valueOf(raw.substring(0, sep)) }
            .getOrDefault(PeriodTimeSource.Manual)
        if (source == PeriodTimeSource.None) return PeriodTimes.Empty

        val slots = PeriodTimes.parseSlots(raw.substring(sep + 1))
        // 解析不出任何有效时间就当作未配置 —— 别把坏数据当成"已配置"
        if (slots.isEmpty()) return PeriodTimes.Empty
        return PeriodTimes(source, slots)
    }
}
