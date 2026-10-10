package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.XqJson
import java.io.File
import java.time.LocalDate

/**
 * 一条**临时课程** —— 学校系统里不存在、但自己要在课表上看见并被提醒的一段课。
 *
 * 用途是"临时通知来的事":紧急调换、临时开会。它与 [SelfStudySlot] 是**两层**,
 * 不合并:
 *
 * | | 晚自习 [SelfStudySlot] | 临时课程 [CustomCourse] |
 * |---|---|---|
 * | 时段 | 只有绝对时刻 | 首尾节 **或** 绝对时刻 |
 * | 地点 / 教师 | 没有 | 有 |
 * | 语义 | "每天到点就有"的作息 | "这节课" |
 *
 * 生效范围二选一:
 * - [date] 非空 = **只这一次**(临时通知来的会,填 `yyyy-MM-dd`);
 * - [weekdays] 非空 = **每周固定**(在「更多 → 临时课程」里配置)。
 *
 * ⚠️ **不参与调休**:整天课被调走时它仍留在本日。理由见 `ScheduleOverrides` 顶部注释。
 */
data class CustomCourse(
    val id: String,
    val name: String,
    val room: String = "",
    val teacher: String = "",
    /** 首尾节,格式同接口的 `jcxx`,如 `"3-4"`。与 [start]/[end] 二选一。 */
    val periods: String? = null,
    /** `"14:00"`;与 [periods] 二选一。 */
    val start: String? = null,
    val end: String? = null,
    /** 每周重复的星期,0=周一 … 6=周日。与 [date] 二选一。 */
    val weekdays: List<Int> = emptyList(),
    /** 只这一次的日期 `"yyyy-MM-dd"`。与 [weekdays] 二选一。 */
    val date: String? = null,
    val note: String = "",
) {
    /** 按节次填的(与"按时间填"相对)。 */
    val byPeriods: Boolean get() = !periods.isNullOrBlank()

    /** 只生效一次的那种。 */
    val oneOff: Boolean get() = !date.isNullOrBlank()

    /** 时段的人话:`3-4` 或 `14:00-15:30`。 */
    fun timeLabel(): String = when {
        byPeriods -> periods!!
        else -> listOfNotNull(start, end).joinToString("-")
    }

    /** 生效范围的人话:`每周一、周三` 或 `10-08 一次`。 */
    fun scopeLabel(): String = if (oneOff) {
        "$date 一次"
    } else if (weekdays.isEmpty()) {
        "未选择"
    } else {
        "每周" + weekdays.sorted().joinToString("、") {
            com.xiqueer.protocol.XqFeatures.weekdayName(it).removePrefix("周")
        }
    }

    fun describe(): String = buildString {
        append(name)
        append(" · ").append(timeLabel())
        append(" · ").append(scopeLabel())
        if (room.isNotBlank()) append(" · ").append(room)
    }

    /**
     * [date] 那天该不该上。
     *
     * [date] 可空:导出的**网格**是按星期排的,只有"只这一次"的条目才需要日历日 ——
     * 拿不到日期时它**不显示**,而不是猜一个。
     */
    fun appliesOn(date: LocalDate?, weekday: Int): Boolean {
        if (oneOff) return date != null && this.date == date.toString()
        return weekday in weekdays
    }

    companion object {
        /** 至少要有这一样东西才配回车 —— 与"自定义时段"同一个口径。 */
        const val MAX_DAYS = 7

        /**
         * 校验并规整一条输入;不合法返回 null(调用方据此给用户人话解释)。
         *
         * 规整规则刻意写死在这里,而不是让 UI 自己收拾:
         * 面板、AI 工具、反序列化三个入口都必须过同一道闸。
         */
        fun of(
            name: String,
            room: String = "",
            teacher: String = "",
            periods: String? = null,
            start: String? = null,
            end: String? = null,
            weekdays: List<Int> = emptyList(),
            date: String? = null,
            note: String = "",
            id: String = "cc-${System.currentTimeMillis()}",
        ): CustomCourse? {
            val n = name.trim()
            if (n.isEmpty()) return null

            val p = periods?.trim()?.takeIf { it.isNotEmpty() }
            val s = start?.trim()?.takeIf { it.isNotEmpty() }
            val e = end?.trim()?.takeIf { it.isNotEmpty() }

            // 时段:按节次优先;没有节次就必须给一对合法时刻
            if (p == null) {
                if (s == null || !PeriodTimes.isClock(s)) return null
                if (e != null && !PeriodTimes.isClock(e)) return null
                // 结束必须晚于开始 —— 否则"是否上完"和提醒都会反过来
                if (e != null && SelfStudySlot.toMinutes(e) <= SelfStudySlot.toMinutes(s)) return null
            }

            // 生效范围:给了日期就是"只这一次";否则必须至少选一个星期
            val d = date?.trim()?.takeIf { it.isNotEmpty() }
            if (d != null && runCatching { LocalDate.parse(d) }.isFailure) return null
            val days = if (d != null) emptyList() else weekdays.filter { it in 0..6 }.distinct().sorted()
            if (d == null && days.isEmpty()) return null

            return CustomCourse(
                id = id,
                name = n,
                room = room.trim(),
                teacher = teacher.trim(),
                periods = p,
                start = if (p != null) null else s,
                end = if (p != null) null else e,
                weekdays = days,
                date = d,
                note = note.trim(),
            )
        }
    }
}

/**
 * 临时课程的**本地覆盖层**。
 *
 * 与 [ShiftStore] / [CourseOverrideStore] / [SelfStudyStore] 同一个原则:
 * 底表只由刷新写,本层只由用户 / AI 写,两个写者不相交 —— 所以刷新永远不会冲掉它。
 */
class CustomCourseStore(context: Context) {

    private val file = File(context.filesDir, "custom_courses.json")

    fun all(): List<CustomCourse> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "临时课程数据损坏,已丢弃", it)
            file.delete()
            emptyList()
        }
    }

    /** 覆盖式保存:**整份列表一次写**,与晚自习同口径。返回实际落地的列表。 */
    fun save(courses: List<CustomCourse>): List<CustomCourse> {
        val clean = courses.mapNotNull { c ->
            CustomCourse.of(
                name = c.name,
                room = c.room,
                teacher = c.teacher,
                periods = c.periods,
                start = c.start,
                end = c.end,
                weekdays = c.weekdays,
                date = c.date,
                note = c.note,
                id = c.id,
            )
        }
        write(clean)
        return clean
    }

    fun removeById(id: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) write(list)
        return removed
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun write(list: List<CustomCourse>) {
        val json = list.joinToString(",", "[", "]") { c ->
            buildString {
                append("""{"id":${JsonText.quote(c.id)}""")
                append(""","name":${JsonText.quote(c.name)}""")
                append(""","room":${JsonText.quote(c.room)}""")
                append(""","teacher":${JsonText.quote(c.teacher)}""")
                append(""","periods":${JsonText.quoteOrNull(c.periods)}""")
                append(""","start":${JsonText.quoteOrNull(c.start)}""")
                append(""","end":${JsonText.quoteOrNull(c.end)}""")
                append(""","weekdays":${JsonText.quoteList(c.weekdays.map { it.toString() })}""")
                append(""","date":${JsonText.quoteOrNull(c.date)}""")
                append(""","note":${JsonText.quote(c.note)}}""")
            }
        }
        file.writeText(json)
    }

    private fun parse(text: String): List<CustomCourse> =
        XqJson.parseArray(text).mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // weekdays 可能是数字数组,也可能是字符串数组(两边都容忍,免得改格式时炸)
            val days = (m["weekdays"] as? List<*>)?.mapNotNull { v ->
                (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
            }.orEmpty()
            CustomCourse.of(
                name = name,
                room = m["room"]?.toString().orEmpty(),
                teacher = m["teacher"]?.toString().orEmpty(),
                periods = m["periods"]?.toString(),
                start = m["start"]?.toString(),
                end = m["end"]?.toString(),
                weekdays = days,
                date = m["date"]?.toString(),
                note = m["note"]?.toString().orEmpty(),
                id = m["id"]?.toString() ?: "cc-$name",
            )
        }

    private companion object {
        const val TAG = "XqCustomCourse"
    }
}
