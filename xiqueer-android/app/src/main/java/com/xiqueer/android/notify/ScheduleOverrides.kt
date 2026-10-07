package com.xiqueer.android.notify

import com.xiqueer.android.data.CourseOverride
import com.xiqueer.android.data.Overlays
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.SelfStudySlot
import com.xiqueer.android.data.Shift
import com.xiqueer.protocol.Timetable
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 一次"实际要上"的课,带上覆盖层留下的痕迹。
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
 * 课表 + 本地覆盖层 → **实际要上的课**。
 *
 * 这是整个覆盖层唯一的计算入口:日程页、课表页、课前提醒、每日摘要、
 * 悬浮窗、导出图片全都走这里。谁都不许自己 `forDate` 之后再手工过滤 ——
 * 一旦有两处实现,必然有一处忘记覆盖层(最典型的就是"课挪走了闹钟还在原日子响")。
 *
 * 覆盖层有三层,顺序固定:
 * 1. **课节覆写**(改某一节的教室/节次)—— 改的是"这节课本身长什么样";
 * 2. **自定义时段**(晚自习)—— 凭空多出来的课,不属于服务端课表;
 * 3. **调休** —— 把上面的结果按天搬运。
 *
 * ⚠️ 调休只搬运**服务端的课**,不搬运晚自习:晚自习是"每天到点就有"的作息,
 * 不是某天专属的一节课。把整天课调到别处时顺手把晚自习也搬走是错的。
 */
object ScheduleOverrides {

    /**
     * 课表网格的总行数 = 服务端的节次数 + 自定义时段数。
     *
     * 网格与导出图都必须用它决定高度,否则晚自习会被裁掉
     * (`export/TimetableImage` 里就曾经按 `1..maxPeriod` 过滤,晚自习会静默消失)。
     */
    fun gridRows(timetable: Timetable, overlays: Overlays): Int =
        maxOf(1, timetable.maxPeriod) + overlays.selfStudies.size

    /**
     * [date] 那天实际要上的课。
     *
     * 顺序:课节覆写 → 晚自习 → 调出标记 → 调来 → 合并 → 按时间排序。
     * 目标日本来就有课时**两门都保留**(冲突如实显示,不覆盖不丢)。
     */
    fun forDate(
        timetable: Timetable,
        times: PeriodTimes,
        overlays: Overlays,
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<EffectiveCourse> {
        val week = timetable.currentWeek
        val dayIndex = dayIndex(timetable, date)

        // 1) 底表 + 课节覆写
        val patched = ClassReminder.forDate(timetable, date, times).map { o ->
            if (dayIndex == null) o
            else applyOverride(o, week, dayIndex, overlays.courseOverrides, times, zone)
        }

        // 2) 晚自习:不参与调休,所以先并进来再统一走后面的排序
        val withSelf = if (dayIndex == null) patched
        else patched + selfStudyOccurrences(timetable, overlays.selfStudies, date, dayIndex, times, zone)

        // 3) 被调走的:标灰,但仍然留在原位(需求明确要求"原位置变成灰色选项卡")
        val out = withSelf.map { o ->
            // 晚自习不参与调休 —— 整天课被调走时它仍在本日
            val moved = if (o.isCustom) null
            else overlays.shifts.firstOrNull { it.fromDate == date && it.covers(o.courseName) }
            EffectiveCourse(o, movedTo = moved?.toDate)
        }.toMutableList()

        // 4) 调来的:从各自的 from 那天取课,重算到本日
        for (s in overlays.shifts) {
            if (s.toDate != date) continue
            val from = s.fromDate ?: continue
            if (from == date) continue
            val fromIndex = dayIndex(timetable, from)
            ClassReminder.forDate(timetable, from, times)
                .filter { s.covers(it.courseName) }
                .map { o ->
                    if (fromIndex == null) o
                    else applyOverride(o, week, fromIndex, overlays.courseOverrides, times, zone)
                }
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
     * 未来 [horizonMillis] 内需要提醒的课,**已按覆盖层修正**。
     *
     * 直接复用 [forDate] 逐日算,而不是另写一套遍历 —— 两套实现一定会走偏。
     * 代价是每天多几次 [ClassReminder.forDate] 调用,而它只是过滤一个 7×N 的数组,
     * 在 24 小时窗口(2 天)上完全可忽略。
     */
    fun upcoming(
        timetable: Timetable,
        times: PeriodTimes,
        overlays: Overlays,
        leadMinutes: Int,
        nowMillis: Long,
        horizonMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ClassOccurrence> {
        // "没配作息就不假装能提醒"这条**只对服务端课表成立** ——
        // 晚自习的时间是用户自己填的,本身就是绝对时刻,不依赖作息表。
        // 所以只要还有启用了的自定义时段,就不能整个返回空。
        val hasSelfStudy = overlays.selfStudies.any { it.weekdays.isNotEmpty() }
        if (!times.configured && !hasSelfStudy) return emptyList()
        val leadMs = leadMinutes.coerceAtLeast(0) * 60_000L
        val latest = nowMillis + horizonMillis
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        val out = ArrayList<ClassOccurrence>()
        // 窗口跨天,所以从前一天算起(昨晚的调休课可能落在今天凌晨)
        for (offset in -1..2) {
            val date = today.plusDays(offset.toLong())
            for (e in forDate(timetable, times, overlays, date, zone)) {
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
     * 迟早会有一处忘了带覆盖层(本项目已经犯过一次同类错误)。
     *
     * 算不出日历日(`weekStart` 与 `termStartMonday` 都没有)时返回 7 个空列,
     * 调用方需要自己决定退回原始课表 —— 这里不替它做这个决定。
     */
    fun week(
        timetable: Timetable,
        times: PeriodTimes,
        overlays: Overlays,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<List<EffectiveCourse>> {
        val monday = ClassReminder.weekMonday(timetable) ?: return List(7) { emptyList() }
        return (0..6).map { forDate(timetable, times, overlays, monday.plusDays(it.toLong()), zone) }
    }

    // ---- 覆盖层 1:课节覆写 ----

    /**
     * 把课节覆写打到一节课上。
     *
     * 只认 `(周次, 星期, 底表身份)` 三条全中的条目 —— 粒度是"**这一节**",
     * 不是"这门课":同一门课别的星期几、别的周次都不受影响。
     *
     * 只改节次时必须走 [ClassReminder.retimePeriods] 重算时间标签与绝对时刻,
     * 否则会出现"格子挪到第 3 节了,但提醒还按第 5 节响"。
     */
    private fun applyOverride(
        o: ClassOccurrence,
        week: Int,
        weekday: Int,
        overrides: List<CourseOverride>,
        times: PeriodTimes,
        zone: ZoneId,
    ): ClassOccurrence {
        if (overrides.isEmpty() || o.courseKey.isBlank()) return o
        val hit = overrides.firstOrNull {
            it.week == week && it.weekday == weekday && it.courseKey == o.courseKey
        } ?: return o

        val room = hit.room?.takeIf { it.isNotBlank() } ?: o.room
        val withRoom = if (room == o.room) o else o.copy(room = room)

        val periods = hit.periods?.takeIf { it.isNotBlank() } ?: return withRoom
        val parsed = ClassReminder.parsePeriods(periods) ?: return withRoom
        val (ps, pe) = parsed
        if (ps == o.periodStart && pe == o.periodEnd) return withRoom
        return ClassReminder.retimePeriods(withRoom, ps, pe, times, zone)
    }

    // ---- 覆盖层 2:自定义时段(晚自习) ----

    /**
     * 晚自习在网格里占的行:接在最后一节之后,一条时段一行。
     *
     * `periodStart/periodEnd` 复用成"行号",所以它一定大于 `maxPeriod` ——
     * 网格与导出图据此把它画在节次区之外,而 [ClassOccurrence.isCustom] 让界面
     * 不把它显示成"第 13 节"。
     */
    private fun selfStudyOccurrences(
        timetable: Timetable,
        slots: List<SelfStudySlot>,
        date: LocalDate,
        weekday: Int,
        times: PeriodTimes,
        zone: ZoneId,
    ): List<ClassOccurrence> {
        if (slots.isEmpty()) return emptyList()
        val base = maxOf(1, timetable.maxPeriod)
        return slots.mapIndexedNotNull { i, slot ->
            if (!slot.enabledOn(weekday)) return@mapIndexedNotNull null
            val row = base + i + 1
            // 结束时间缺省时退回该节次作息表的结束时间(如果用户配了作息表);
            // 都没有就只显示开始时间 —— 不编造
            val endLabel = slot.end?.takeIf { it.isNotBlank() } ?: times.slots.getOrNull(row - 1)?.end
            ClassOccurrence(
                courseName = slot.label,
                room = "",
                teacher = "",
                periodStart = row,
                periodEnd = row,
                week = timetable.currentWeek,
                date = date,
                startLabel = slot.start,
                endLabel = endLabel,
                classStartMillis = ClassReminder.millisAt(date, slot.start, zone),
                classEndMillis = ClassReminder.millisAt(date, endLabel, zone),
                fireAtMillis = null,
                courseKey = "",
                customLabel = slot.label,
            )
        }
    }

    // ---- helpers ----

    /** `date` 相对课表那一周周一的下标(0..6);不在覆盖范围内返回 null。 */
    private fun dayIndex(timetable: Timetable, date: LocalDate): Int? {
        val monday = ClassReminder.weekMonday(timetable) ?: return null
        return ChronoUnit.DAYS.between(monday, date).toInt().takeIf { it in 0..6 }
    }

    /** 某条调休是否管到这门课:`courses` 为空表示整天都管。 */
    private fun Shift.covers(courseName: String): Boolean =
        courses.isNullOrEmpty() || courses.any { it == courseName }
}
