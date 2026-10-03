package com.xiqueer.android.notify

import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.Shift
import com.xiqueer.protocol.Timetable
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一次"实际要上"的课,带上调休留下的痕迹。
 *
 * @param occurrence 这节课(若为调来的,`date` 已经是**目标日**)
 * @param movedFrom  非 null = 这是从那天调来的
 * @param movedTo    非 null = 这节课已经被调走(界面灰显,不可点)
 */
data class EffectiveCourse(
    val occurrence: ClassOccurrence,
    val movedFrom: LocalDate? = null,
    val movedTo: LocalDate? = null,
) {
    val isMovedOut: Boolean get() = movedTo != null
    val isMovedIn: Boolean get() = movedFrom != null
}

/**
 * 课表 + 调休 → **实际要上的课**。
 *
 * 这是整个调休功能唯一的计算入口:日程页、课表页、课前提醒、每日摘要
 * 全都走这里。谁都不许自己 `forDate` 之后再手工过滤 ——
 * 一旦有两处实现,必然有一处忘记调休(最典型的就是"课挪走了闹钟还在原日子响")。
 */
object ScheduleOverrides {

    /**
     * [date] 那天实际要上的课。
     *
     * 顺序:调出标记 → 调来 → 合并 → 按时间排序。
     * 目标日本来就有课时**两门都保留**(冲突如实显示,不覆盖不丢)。
     */
    fun forDate(
        timetable: Timetable,
        times: PeriodTimes,
        shifts: List<Shift>,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EffectiveCourse> {
        val base = ClassReminder.forDate(timetable, date, times)

        // 1) 被调走的:标灰,但仍然留在原位(需求明确要求"原位置变成灰色选项卡")
        val out = base.map { o ->
            val moved = shifts.firstOrNull { it.fromDate == date && it.covers(o.courseName) }
            EffectiveCourse(o, movedTo = moved?.toDate)
        }.toMutableList()

        // 2) 调来的:从各自的 from 那天取课,重算到本日
        for (s in shifts) {
            if (s.toDate != date) continue
            val from = s.fromDate ?: continue
            if (from == date) continue
            ClassReminder.forDate(timetable, from, times)
                .filter { s.covers(it.courseName) }
                .forEach { o ->
                    out.add(
                        EffectiveCourse(
                            occurrence = ClassReminder.retime(o, date, zone),
                            movedFrom = from,
                        ),
                    )
                }
        }

        return out.sortedBy { it.occurrence.classStartMillis ?: Long.MAX_VALUE }
    }

    /**
     * 未来 [horizonMillis] 内需要提醒的课,**已按调休修正**。
     *
     * 直接复用 [forDate] 逐日算,而不是另写一套遍历 —— 两套实现一定会走偏。
     * 代价是每天多几次 [ClassReminder.forDate] 调用,而它只是过滤一个 7×N 的数组,
     * 在 24 小时窗口(2 天)上完全可忽略。
     */
    fun upcoming(
        timetable: Timetable,
        times: PeriodTimes,
        shifts: List<Shift>,
        leadMinutes: Int,
        nowMillis: Long,
        horizonMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ClassOccurrence> {
        if (!times.configured) return emptyList()
        val leadMs = leadMinutes.coerceAtLeast(0) * 60_000L
        val latest = nowMillis + horizonMillis
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        val out = ArrayList<ClassOccurrence>()
        // 窗口跨天,所以从前一天算起(昨晚的调休课可能落在今天凌晨)
        for (offset in -1..2) {
            val date = today.plusDays(offset.toLong())
            for (e in forDate(timetable, times, shifts, date, zone)) {
                // 被调走的课**不提醒** —— 它已经不在那天上了
                if (e.isMovedOut) continue
                val start = e.occurrence.classStartMillis ?: continue
                val fire = start - leadMs
                // 已过提醒点的不补,否则半夜装完 App 会被当天所有早课轰一遍
                if (fire <= nowMillis || fire > latest) continue
                out.add(e.occurrence.copy(fireAtMillis = fire))
            }
        }
        return out.sortedBy { it.fireAtMillis ?: Long.MAX_VALUE }
    }

    /**
     * 整周(下标 0=周一 … 6=周日)的 effective 课。
     *
     * 课表网格页与**导出图片**都用它 —— 这是"屏幕上看到的"和"导出的"必须一致的地方。
     * 各自调 [forDate] 逐日算也不是不行,但那样就有了两个循环、两个起点,
     * 迟早会有一处忘了带 `shifts`(本项目已经犯过一次同类错误)。
     *
     * 算不出日历日(`weekStart` 与 `termStartMonday` 都没有)时返回 7 个空列,
     * 调用方需要自己决定退回原始课表 —— 这里不替它做这个决定。
     */
    fun week(
        timetable: Timetable,
        times: PeriodTimes,
        shifts: List<Shift>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<List<EffectiveCourse>> {
        val monday = ClassReminder.weekMonday(timetable) ?: return List(7) { emptyList() }
        return (0..6).map { forDate(timetable, times, shifts, monday.plusDays(it.toLong()), zone) }
    }

    /** 某条调休是否管到这门课:`courses` 为空表示整天都管。 */
    private fun Shift.covers(courseName: String): Boolean =
        courses.isNullOrEmpty() || courses.any { it == courseName }
}
