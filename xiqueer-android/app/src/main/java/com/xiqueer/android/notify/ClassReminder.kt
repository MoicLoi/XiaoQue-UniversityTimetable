package com.xiqueer.android.notify

import com.xiqueer.android.data.CourseKey
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.Timetable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 一次课。**不依赖作息表** —— 未配置时间时 [startLabel] 为空,
 * 通知与详情就只显示节次。
 */
data class ClassOccurrence(
    val courseName: String,
    val room: String,
    val teacher: String,
    val periodStart: Int,
    val periodEnd: Int,
    val week: Int,
    val date: LocalDate,
    /** `08:00`,未配置作息时为 null。 */
    val startLabel: String?,
    val endLabel: String?,
    /** 上课的绝对时刻;未配置作息时为 null。 */
    val classStartMillis: Long?,
    /**
     * 下课的绝对时刻。日程页要靠它判断"这节是不是已经上完了"。
     * 未配置作息时为 null —— 此时无法判断是否上完,只能按"已过开始时间"粗略处理。
     */
    val classEndMillis: Long?,
    /** 闹钟应响的时刻;未配置作息时为 null。 */
    val fireAtMillis: Long?,
    /**
     * **底表身份**(`name|periods|room|teacher`,由 [CourseKey.of] 算)。
     *
     * 课节覆写靠它把"这一节"认回来 —— 用覆写后的值去认会认不出自己,
     * 因为覆写改的正是 `periods` / `room` 这两项。
     */
    val courseKey: String = "",
    /**
     * 非 null = 这**不是**服务端课表里的第 N 节,而是一个自定义时段(晚自习)。
     * 有值时界面不显示"第 N 节",改显示它本身;网格里它占的行也由外部指定。
     */
    val customLabel: String? = null,
) {
    /** 是不是自定义时段(晚自习),而不是服务端的第 N 节。 */
    val isCustom: Boolean get() = !customLabel.isNullOrBlank()

    /** 通知/列表里的一行:`第 1-2 节 · 08:00 · 厚德楼-H502` */
    fun scheduleLine(): String = buildString {
        if (isCustom) {
            append(customLabel)
        } else {
            append("第 $periodStart-$periodEnd 节")
        }
        if (startLabel != null) {
            append(" · ").append(startLabel)
            if (endLabel != null) append("-").append(endLabel)
        }
        val where = listOf(room, teacher).filter { it.isNotBlank() }.joinToString(" · ")
        if (where.isNotBlank()) append(" · ").append(where)
    }

    /** 同一节课只应有一条通知。 */
    val stableId: Int get() = "$courseName|$date|$periodStart".hashCode()
}

/**
 * 从课表算提醒。
 *
 * 时间基准(实测确认,见 DESIGN.md §3.1):
 * - `Timetable.weekStart` 就是**本周周一**(接口的 `qssj`);
 * - `Timetable.currentWeek` = `zc`;
 * - `weekN` 数组是**星期**(周一..周日),不是周次。
 *
 * 两种模式:
 * - **[forDate]**:只要节次,任何时候都能用 —— 早间摘要靠它。
 * - **[upcoming]**:要绝对时间,**未配置作息时必然返回空** —— 不能假装能提醒。
 */
object ClassReminder {

    /** `jcxx` = "1-2" / "3" / "1-4"。解析失败返回 null。 */
    fun parsePeriods(jcxx: String): Pair<Int, Int>? {
        val parts = jcxx.split('-', '~', '－', '–').mapNotNull { it.trim().toIntOrNull() }
        return when {
            parts.size >= 2 -> parts[0] to maxOf(parts[0], parts[1])
            parts.size == 1 -> parts[0] to parts[0]
            else -> null
        }
    }

    /**
     * `skzs` 判断某周是否上课。实测见过两种写法:
     * - `3-10周`(区间)
     * - `4,6,8,10,12,14,16,18周`(逐周列出)
     * 另外兼容 `单`/`双` 与全角逗号、括号备注。空串按「每周都上」处理。
     */
    fun weekMatches(skzs: String, week: Int): Boolean {
        val raw = skzs.trim()
        if (raw.isEmpty()) return true
        val oddOnly = raw.contains("单")
        val evenOnly = raw.contains("双")

        val body = raw
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("（[^）]*）"), "")
            .replace("周", "")
        val weeks = HashSet<Int>()
        for (part in body.split(',', '，', '、')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = p.split('-', '~', '－').map { it.trim() }
            if (range.size >= 2) {
                val a = range[0].toIntOrNull() ?: continue
                val b = range[1].toIntOrNull() ?: continue
                for (w in minOf(a, b)..maxOf(a, b)) weeks.add(w)
            } else {
                p.toIntOrNull()?.let { weeks.add(it) }
            }
        }
        if (weeks.isEmpty()) return true
        if (week !in weeks) return false
        if (oddOnly && week % 2 == 0) return false
        if (evenOnly && week % 2 == 1) return false
        return true
    }

    /** 本周周一。优先用接口直接给的 `qssj`。 */
    fun weekMonday(t: Timetable): LocalDate? {
        t.weekStart?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull()?.let { return it } }
        val term = t.termStartMonday ?: return null
        return runCatching { LocalDate.parse(term).plusWeeks(maxOf(0, t.currentWeek - 1).toLong()) }.getOrNull()
    }

    /**
     * 某一天(必须落在课表覆盖的那一周内)的课。
     *
     * [times] 可选:给了且已配置,行里就带时钟(`第 1-2 节 · 08:00 · 厚德楼-H502`);
     * 不给或未配置,就只显示节次 —— **绝不用猜的时间填空**。
     * 早间摘要与「今天有什么课」都走这里。
     */
    fun forDate(
        timetable: Timetable,
        date: LocalDate,
        times: PeriodTimes? = null,
    ): List<ClassOccurrence> {
        val monday = weekMonday(timetable) ?: return emptyList()
        val d = java.time.temporal.ChronoUnit.DAYS.between(monday, date).toInt()
        if (d !in 0..6) return emptyList()
        val week = timetable.currentWeek
        return timetable.days.getOrElse(d) { emptyList() }
            .filter { weekMatches(it.weeks, week) }
            .mapNotNull { c -> occurrence(c, date, week, times) }
            .sortedBy { it.periodStart }
    }

    /**
     * 未来 [horizonMillis] 内需要提醒的课,按触发时间升序。
     *
     * **未配置作息表时返回空列表** —— 没有绝对时间就不该假装能课前提醒。
     * 此时唯一的推送是 [forDate] 支撑的每日摘要。
     */
    fun upcoming(
        timetable: Timetable,
        times: PeriodTimes,
        leadMinutes: Int,
        nowMillis: Long,
        horizonMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ClassOccurrence> {
        if (!times.configured) return emptyList()
        val monday = weekMonday(timetable) ?: return emptyList()
        val week = timetable.currentWeek
        val leadMs = leadMinutes.coerceAtLeast(0) * 60_000L
        val latest = nowMillis + horizonMillis

        val out = ArrayList<ClassOccurrence>()
        for (d in 0..6) {
            val date = monday.plusDays(d.toLong())
            for (c in timetable.days.getOrElse(d) { emptyList() }) {
                if (!weekMatches(c.weeks, week)) continue
                val o = occurrence(c, date, week, times, leadMs, zone) ?: continue
                val fire = o.fireAtMillis ?: continue
                // 已过提醒点的不补 —— 否则半夜装完 App 会被当天所有早课轰一遍
                if (fire <= nowMillis || fire > latest) continue
                out.add(o)
            }
        }
        return out.sortedBy { it.fireAtMillis ?: Long.MAX_VALUE }
    }

    /**
     * 把一次课搬到另一天 —— 只换日期,时间点按新日期重算。
     *
     * 调休用它:一条 `9-28 → 9-20` 的调休,本质就是把 9-28 那节课的
     * `date` / `classStartMillis` / `classEndMillis` 重新算到 9-20 上。
     *
     * **日期与毫秒必须一起改**,否则会出现"日期是 9-20 但闹钟按 9-28 响"这种最坏情况。
     * 所以这个换算只放在这一处,别的代码不许自己在外面拼。
     */
    fun retime(
        o: ClassOccurrence,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ClassOccurrence {
        fun millis(label: String?): Long? = label?.let {
            runCatching {
                LocalDateTime.of(date, LocalTime.parse(it)).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        }
        return o.copy(
            date = date,
            classStartMillis = millis(o.startLabel),
            classEndMillis = millis(o.endLabel),
            // 旧的触发时刻一定不能留 —— 调用方按提前量重算
            fireAtMillis = null,
        )
    }

    private fun occurrence(
        c: Course,
        date: LocalDate,
        week: Int,
        times: PeriodTimes?,
        leadMs: Long = 0,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ClassOccurrence? {
        val (ps, pe) = parsePeriods(c.periods) ?: return null
        val startLabel = times?.startOf(ps)
        val endLabel = times?.endOf(pe)

        val startMillis = if (startLabel != null) {
            runCatching {
                LocalDateTime.of(date, LocalTime.parse(startLabel)).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        } else {
            null
        }
        val endMillis = if (endLabel != null) {
            runCatching {
                LocalDateTime.of(date, LocalTime.parse(endLabel)).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
        } else {
            null
        }

        return ClassOccurrence(
            courseName = c.name,
            room = c.room,
            teacher = c.teacher,
            periodStart = ps,
            periodEnd = pe,
            week = week,
            date = date,
            startLabel = startLabel,
            endLabel = endLabel,
            classStartMillis = startMillis,
            classEndMillis = endMillis,
            fireAtMillis = startMillis?.minus(leadMs),
            // 身份用**底表**的值算 —— 覆写改的正是 periods / room,不能用覆写后的
            courseKey = CourseKey.of(c),
        )
    }

    /**
     * `date` 那天的 `HH:mm` 对应的绝对毫秒;给不出时间或解析失败返回 null。
     *
     * 自定义时段(晚自习)与 [retimePeriods] 都用它 —— 时间字符串只有这一处变成毫秒。
     */
    fun millisAt(date: LocalDate, clock: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (clock.isNullOrBlank()) return null
        return runCatching {
            LocalDateTime.of(date, LocalTime.parse(clock)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 换节次 —— 课节覆写把课从 `5-6` 挪到 `3-4` 时用它。
     *
     * **节次、时间标签、绝对时刻必须一起改**:只改 `periodStart/End` 而留着旧的
     * `startLabel` / `classStartMillis`,就会出现"格子挪到第 3 节了,但提醒还按第 5 节响"。
     * 这与 [retime] 是同一类坑,所以换算同样只放在这一处,别在外面手工拼。
     *
     * 未配置作息([times] 为空或不 `configured`)时标签为 null、时刻为 null ——
     * 不编造时间。
     */
    fun retimePeriods(
        o: ClassOccurrence,
        periodStart: Int,
        periodEnd: Int,
        times: PeriodTimes?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ClassOccurrence {
        val startLabel = times?.startOf(periodStart)
        val endLabel = times?.endOf(periodEnd)
        return o.copy(
            periodStart = periodStart,
            periodEnd = periodEnd,
            startLabel = startLabel,
            endLabel = endLabel,
            classStartMillis = millisAt(o.date, startLabel, zone),
            classEndMillis = millisAt(o.date, endLabel, zone),
            // 触发时刻按新节次重算,不能留旧的
            fireAtMillis = null,
        )
    }
}
