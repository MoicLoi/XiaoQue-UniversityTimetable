package com.xiqueer.android.notify

import com.xiqueer.android.data.CourseOverride
import com.xiqueer.android.data.CustomCourse
import com.xiqueer.android.data.Overlays
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.SelfStudySlot
import com.xiqueer.android.data.Shift
import com.xiqueer.android.data.ShiftCourse
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
 * @param snapshotOnly 见该字段的说明
 */
data class EffectiveCourse(
    val occurrence: ClassOccurrence,
    val movedFrom: LocalDate? = null,
    val movedTo: LocalDate? = null,
    /**
     * 这一节是**从调休快照还原出来的** —— 源日期在别的周,底表当前这一周里没有它。
     *
     * 界面据此**不要**把它当成可点的课程:`courseOf` 是按底表身份找的,
     * 找不到就会退化成按课名匹配,从而误配到**同名但另一天**的那一节上 ——
     * 点开详情、再点「课节改动」,改的就成了错误的那一天。
     */
    val snapshotOnly: Boolean = false,
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
 * 覆盖层有四层,顺序固定:
 * 1. **课节覆写**(改某一节的教室/节次)—— 改的是"这节课本身长什么样";
 * 2. **自定义时段**(晚自习)—— 凭空多出来的课,不属于服务端课表;
 * 3. **临时课程**(紧急调换 / 开会)—— 同样不属于服务端课表,但有名称/地点/节次;
 * 4. **调休** —— 把上面的结果按天搬运。
 *
 * ⚠️ 调休只搬运**服务端的课**,不搬运第 2、3 层:
 * 它们要么是"每天到点就有"的作息(晚自习),要么是"钉"在时间上的临时安排。
 * 把整天课调到别处时顺手把它们也搬走是错的 —— 用户明确要求临时课程**不参与调休**。
 */
object ScheduleOverrides {

    /** 节次区之外的那些"人工行"上写什么。*/
    data class ManualRow(val label: String, val start: String)

    /**
     * 节次区之外的人工行(晚自习在前,按时间填的临时课程在后)。
     *
     * 行号 = `maxOf(1, maxPeriod) + index + 1`。网格页、导出图、xlsx 的左侧栏
     * 全都读它 —— 三处各写一遍必然有一处漏掉新增的层。
     */
    fun manualRows(timetable: Timetable, times: PeriodTimes, overlays: Overlays): List<ManualRow> {
        val self = overlays.selfStudies.map { ManualRow(it.label, it.start) }
        val cus = fallbackCourses(times, overlays).map { ManualRow(it.name, it.start.orEmpty()) }
        return self + cus
    }

    /**
     * 课表网格的总行数 = 服务端的节次数 + 人工行数。
     *
     * 网格与导出图都必须用它决定高度,否则自定义层会被裁掉
     * (`export/TimetableImage` 里就曾经按 `1..maxPeriod` 过滤,晚自习会静默消失)。
     */
    fun gridRows(timetable: Timetable, times: PeriodTimes, overlays: Overlays): Int =
        maxOf(1, timetable.maxPeriod) + manualRows(timetable, times, overlays).size

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

        // 2) 晚自习 + 临时课程:两层都不参与调休,所以先并进来再统一走后面的排序
        val withCustom = if (dayIndex == null) patched
        else patched +
            selfStudyOccurrences(timetable, overlays.selfStudies, date, dayIndex, times, zone) +
            customCourseOccurrences(timetable, times, overlays, date, dayIndex, zone)

        // 3) 被调走的:标灰,但仍然留在原位(需求明确要求"原位置变成灰色选项卡")
        val out = withCustom.map { o ->
            // 自定义层不参与调休 —— 整天课被调走时它们仍在本日
            val moved = if (o.isCustom) null
            else overlays.shifts.firstOrNull { it.fromDate == date && it.covers(o.courseName) }
            EffectiveCourse(o, movedTo = moved?.toDate)
        }.toMutableList()

        // 4) 调来的:两条取数路径,取决于源日期在不在当前加载的这一周
        for (s in overlays.shifts) {
            if (s.toDate != date) continue
            val from = s.fromDate ?: continue
            if (from == date) continue
            val fromIndex = dayIndex(timetable, from)

            if (fromIndex != null) {
                // 源日期就在当前这一周 → 用实时底表,顺带带上那一天的课节覆写
                ClassReminder.forDate(timetable, from, times)
                    .filter { s.covers(it.courseName) }
                    .map { applyOverride(it, week, fromIndex, overlays.courseOverrides, times, zone) }
                    .forEach { o ->
                        out.add(
                            EffectiveCourse(
                                occurrence = ClassReminder.retime(o, date, zone),
                                movedFrom = from,
                            ),
                        )
                    }
            } else {
                // 源日期在**别的周** —— 底表里查不到那几节课,只能靠创建时记下的快照。
                //
                // 修复前这里共用上面那条 `ClassReminder.forDate(timetable, from, …)`,
                // 而它对"不在本周的日期"一律返回空 —— 于是目标位置什么都没有,
                // 原位置却照常灰显(那是纯日期比较,不受周限制)。
                s.snapshot
                    .filter { s.covers(it.name) }
                    .mapNotNull { materialize(it, date, week, times, zone) }
                    .forEach { o ->
                        out.add(EffectiveCourse(o, movedFrom = from, snapshotOnly = true))
                    }
            }
        }

        return out.sortedBy { it.occurrence.classStartMillis ?: Long.MAX_VALUE }
    }

    /**
     * 取 [date] 那天、[courses] 指定的那几节课的**快照**。创建调休时调用。
     *
     * 取不到(那天不在 [timetable] 覆盖的那一周)返回空 —— **调用方不该因此拒绝创建**:
     * 同周调休本来就不需要快照,跨周的那部分等用户翻到源周时由 `AppViewModel` 补齐。
     */
    fun snapshotOf(
        timetable: Timetable,
        date: LocalDate,
        times: PeriodTimes,
        courses: List<String>?,
    ): List<ShiftCourse> =
        ClassReminder.forDate(timetable, date, times)
            .filter { courses.isNullOrEmpty() || courses.any { n -> n == it.courseName } }
            .map { ShiftCourse(it.courseName, it.teacher, it.room, "${it.periodStart}-${it.periodEnd}") }

    /**
     * 把一条快照还原成目标日的一次课。
     *
     * 节次解析不出来就返回 null(丢弃而不是猜)。时间标签按**目标日**的作息表算 ——
     * 源周与目标周共用同一份作息,没有歧义。
     */
    private fun materialize(
        sc: ShiftCourse,
        date: LocalDate,
        week: Int,
        times: PeriodTimes,
        zone: ZoneId,
    ): ClassOccurrence? {
        val (ps, pe) = ClassReminder.parsePeriods(sc.periods) ?: return null
        val startLabel = times.startOf(ps)
        val endLabel = times.endOf(pe)
        return ClassOccurrence(
            courseName = sc.name,
            room = sc.room,
            teacher = sc.teacher,
            periodStart = ps,
            periodEnd = pe,
            week = week,
            date = date,
            startLabel = startLabel,
            endLabel = endLabel,
            classStartMillis = ClassReminder.millisAt(date, startLabel, zone),
            classEndMillis = ClassReminder.millisAt(date, endLabel, zone),
            fireAtMillis = null,
            // 底表里没有这一节 —— 身份留空,配合 snapshotOnly 让 courseOf 不去误配同名课
            courseKey = "",
        )
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
        // 晚自习与按时间填的临时课程自带 `HH:mm`,本身就是绝对时刻,不依赖作息表。
        // 所以只要还有这类条目,就不能整个返回空。
        if (!times.configured && !overlays.hasOwnClock) return emptyList()
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

    // ---- 覆盖层 3:临时课程(紧急调换 / 开会) ----

    /**
     * 临时课程在网格里占的节次行;定不出来返回 null(= 挂到人工行上)。
     *
     * - 按节次填的 → 就是那几节;
     * - 按起止时间填的 → 取**与它时间重叠的节次**(14:00-15:30 落在第 5-6 节就画在 5-6);
     * - 没配作息、或时间落在所有节次之外(比如晚上)—— 定不出来,交给人工行。
     *
     * ⚠️ 这是**唯一**一份"临时课程画在哪一行"的实现。网格页、导出图、xlsx 都走它,
     * 各自算一遍必然会出现"屏幕上在 5-6 节、导出到最底下"这种对不上的情况。
     */
    fun placementRows(c: CustomCourse, times: PeriodTimes): IntRange? {
        ClassReminder.parsePeriods(c.periods.orEmpty())?.let { (s, e) ->
            if (s >= 1 && e >= s) return s..e
        }
        val s = c.start ?: return null
        val e = c.end ?: return null
        if (!times.configured) return null
        val sm = SelfStudySlot.toMinutes(s)
        val em = SelfStudySlot.toMinutes(e)
        val hit = times.slots.indices.filter { i ->
            val slot = times.slots[i]
            if (!PeriodTimes.isClock(slot.start)) return@filter false
            val ss = SelfStudySlot.toMinutes(slot.start)
            // 缺结束时间的节次按 45 分钟估 —— 只用来判断"有没有重叠",不写进任何输出
            val se = slot.end?.takeIf { PeriodTimes.isClock(it) }
                ?.let { SelfStudySlot.toMinutes(it) } ?: (ss + 45)
            ss < em && se > sm
        }
        return if (hit.isEmpty()) null else (hit.first() + 1)..(hit.last() + 1)
    }

    /**
     * 这条临时课程占的**人工行号**(1 起);它不在人工行上时返回 null。
     *
     * 导出网格要靠它把"挂人工行"的临时课程画到正确的行 —— 行号算法只有这一份,
     * 消费方各算一遍必然出现"屏幕上在末尾、导出到中间"这种对不上的情况。
     */
    fun manualRowIndex(
        c: CustomCourse,
        timetable: Timetable,
        times: PeriodTimes,
        overlays: Overlays,
    ): Int? {
        val i = fallbackCourses(times, overlays).indexOfFirst { it.id == c.id }
        if (i < 0) return null
        return maxOf(1, timetable.maxPeriod) + overlays.selfStudies.size + i + 1
    }

    /**
     * 挂到**人工行**上的临时课程,顺序稳定。
     *
     * 与 [manualRows] 必须给出同一个顺序,否则左侧栏写的名字会和格子里的课对不上。
     */
    private fun fallbackCourses(times: PeriodTimes, overlays: Overlays): List<CustomCourse> =
        overlays.customCourses
            .filter { placementRows(it, times) == null }
            .sortedWith(compareBy({ it.start.orEmpty() }, { it.name }, { it.id }))

    /**
     * 临时课程在 [date] 的那一节。
     *
     * `courseKey` 留空、`customLabel` 填名字 —— 后者让 [ClassOccurrence.isCustom] 为真,
     * 于是调休那一步会跳过它(需求要求不参与调休),界面也不会把它显示成"第 N 节"
     * 或按底表身份去误配同名课程。
     */
    private fun customCourseOccurrences(
        timetable: Timetable,
        times: PeriodTimes,
        overlays: Overlays,
        date: LocalDate,
        weekday: Int,
        zone: ZoneId,
    ): List<ClassOccurrence> {
        if (overlays.customCourses.isEmpty()) return emptyList()

        return overlays.customCourses.mapNotNull { c ->
            if (!c.appliesOn(date, weekday)) return@mapNotNull null
            val rows = placementRows(c, times)
                ?: manualRowIndex(c, timetable, times, overlays)?.let { it..it }
                ?: return@mapNotNull null

            // 按节次填的:起止时间从作息表取(没配就是 null,不编造);
            // 按时间填的:时间本来就是用户写的
            val startLabel = if (c.byPeriods) times.startOf(rows.first) else c.start
            val endLabel = if (c.byPeriods) times.endOf(rows.last) else c.end

            ClassOccurrence(
                courseName = c.name,
                room = c.room,
                teacher = c.teacher,
                periodStart = rows.first,
                periodEnd = rows.last,
                week = timetable.currentWeek,
                date = date,
                startLabel = startLabel,
                endLabel = endLabel,
                classStartMillis = ClassReminder.millisAt(date, startLabel, zone),
                classEndMillis = ClassReminder.millisAt(date, endLabel, zone),
                fireAtMillis = null,
                courseKey = "",
                customLabel = c.name,
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
