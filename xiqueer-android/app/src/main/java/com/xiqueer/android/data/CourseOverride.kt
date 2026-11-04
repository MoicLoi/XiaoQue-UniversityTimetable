package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.XqJson
import java.io.File

/**
 * 「这一节不上了」的两种呈现方式。
 *
 * 为什么是**每条各自选**而不是一个全局开关:两种诉求都真实存在 ——
 * 「课取消了,但我想在课表上看见它被取消了」和「就当没这节课」。
 * 一条规则管不了两种意图;而且合并成一个开关之后,改一次开关会**改写历史**
 * (昨天标灰的课,今天全变成不见了)。
 */
enum class VoidMode(val label: String) {
    /** 留在原位置,整格灰掉,角标「已失效」。 */
    GREY("标灰"),

    /** 课表与日程里**完全不出现**。 */
    HIDE("移除"),
    ;

    companion object {
        fun of(raw: String?): VoidMode? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
                entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
            }
    }
}

/**
 * 对**某一节课**的本地覆写。
 *
 * ⚠️ **定位的粒度是"这一节",不是"这门课"**:
 * `(week, weekday, courseKey)` 三个一起才唯一确定一次课 —— 比如
 * 「第 5 周 · 周三 · 第 5-6 节 · 实验动物解剖学」。
 * 改它只影响那一节,别的周次、别的星期几都不动。
 *
 * [courseKey] 必须是**底表(服务端课表)**算出来的身份,不能用覆写之后的值 ——
 * 否则把课从 5-6 节挪到 3-4 节之后,就再也找不到自己当初改的是哪一节了。
 *
 * 四个可选项(全为 null 的条目没有意义,[CourseOverrideStore.put] 会直接删掉它):
 * - [room] / [periods]:改这一节的教室 / 节次;
 * - [voided]:这一节**失效**(标灰或移除)。
 *
 * ⚠️ **失效与"跨日调换"互斥**:一节课要么取消、要么换一天上,不能同时成立。
 * 换天由 `Shift` 的实例级条目(`Shift.courseKeys`)承载,不放在这里 ——
 * 因为这里的键含**周次**,而目标日可能落在**别的周**,那一周根本找不到这条覆写。
 */
data class CourseOverride(
    val id: String,
    val week: Int,
    /** 0=周一 … 6=周日 */
    val weekday: Int,
    val courseKey: String,
    /** 新教室;null = 不改。 */
    val room: String? = null,
    /** 新节次,格式同接口的 `jcxx`,如 `"3-4"`;null = 不改。 */
    val periods: String? = null,
    /** 失效方式;null = 正常上课。 */
    val voided: VoidMode? = null,
    val note: String = "",
) {
    /** 这条覆写是否真的改变了什么 —— 全空就是空操作。 */
    val effective: Boolean
        get() = !room.isNullOrBlank() || !periods.isNullOrBlank() || voided != null

    /** 把覆写打到一节课上。两项都没给时原样返回。 */
    fun applyTo(c: Course): Course = c.copy(
        room = room?.takeIf { it.isNotBlank() } ?: c.room,
        periods = periods?.takeIf { it.isNotBlank() } ?: c.periods,
    )

    /** 人话描述,给管理面板与 AI 工具回执用。 */
    fun describe(): String = buildString {
        append("第 ").append(week).append(" 周 ")
        append(com.xiqueer.protocol.XqFeatures.weekdayName(weekday))
        val bits = ArrayList<String>(3)
        if (!periods.isNullOrBlank()) bits.add("节次 → $periods")
        if (!room.isNullOrBlank()) bits.add("教室 → $room")
        voided?.let { bits.add("已失效(${it.label})") }
        if (bits.isNotEmpty()) append(" · ").append(bits.joinToString(" · "))
        if (note.isNotBlank()) append(" · ").append(note)
    }
}

/**
 * 课节覆写的**本地覆盖层**。
 *
 * 与 [ShiftStore] 同构,也是同一个原则:
 *
 * ⚠️ 本层与"服务器课表"是**两个写者不相交**的数据 ——
 * 底表(课表缓存)只由"刷新"写入,本层只由用户 / AI 工具写入。
 * 所以刷新永远不会冲掉覆写,覆写也永远不会污染服务器数据。
 *
 * 注意这与"冲突检测"不是一回事:界面上那个「恢复」按钮比的是
 * **当前覆写与服务端值是否还有差异**(用户要求"不一致才显示"),
 * 那是**展示层**的判断,不是数据层的一致性要求 —— 数据层从来不会冲突。
 */
class CourseOverrideStore(context: Context) {

    private val file = File(context.filesDir, "course_overrides.json")

    fun all(): List<CourseOverride> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "课节覆写数据损坏,已丢弃", it)
            file.delete()
            emptyList()
        }
    }

    fun find(week: Int, weekday: Int, courseKey: String): CourseOverride? =
        all().firstOrNull { it.week == week && it.weekday == weekday && it.courseKey == courseKey }

    /**
     * 写入/更新一条覆写。
     *
     * @param room 新教室;传 null 或空串表示"不改教室"
     * @param periods 新节次;传 null 或空串表示"不改节次"
     * @param voided 失效方式;null 表示正常上课
     * @return 实际落地的条目;三项都没给(等价于清除)时返回 null
     */
    fun put(
        week: Int,
        weekday: Int,
        courseKey: String,
        room: String?,
        periods: String?,
        voided: VoidMode? = null,
        note: String = "",
    ): CourseOverride? {
        if (week <= 0 || weekday !in 0..6 || courseKey.isBlank()) return null

        val r = room?.trim()?.takeIf { it.isNotEmpty() }
        val p = periods?.trim()?.takeIf { it.isNotEmpty() }
        val list = all().toMutableList()
        list.removeAll { it.week == week && it.weekday == weekday && it.courseKey == courseKey }

        // 全都没给 = 清除覆写,不要留一条空操作在文件里
        if (r == null && p == null && voided == null) {
            write(list)
            return null
        }

        val entry = CourseOverride(
            id = "$week-$weekday-${System.currentTimeMillis()}",
            week = week,
            weekday = weekday,
            courseKey = courseKey,
            room = r,
            periods = p,
            voided = voided,
            note = note.trim(),
        )
        list.add(entry)
        write(list)
        return entry
    }

    fun remove(week: Int, weekday: Int, courseKey: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.week == week && it.weekday == weekday && it.courseKey == courseKey }
        if (removed) write(list)
        return removed
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

    private fun write(list: List<CourseOverride>) {
        val json = list.joinToString(",", "[", "]") { o ->
            buildString {
                append("""{"id":${JsonText.quote(o.id)}""")
                append(""","week":${o.week}""")
                append(""","weekday":${o.weekday}""")
                append(""","courseKey":${JsonText.quote(o.courseKey)}""")
                append(""","room":${JsonText.quoteOrNull(o.room)}""")
                append(""","periods":${JsonText.quoteOrNull(o.periods)}""")
                append(""","voided":${JsonText.quoteOrNull(o.voided?.name)}""")
                append(""","note":${JsonText.quote(o.note)}}""")
            }
        }
        file.writeText(json)
    }

    private fun parse(text: String): List<CourseOverride> =
        XqJson.parseArray(text).mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val key = m["courseKey"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val week = (m["week"] as? Number)?.toInt() ?: m["week"]?.toString()?.toIntOrNull()
            val weekday = (m["weekday"] as? Number)?.toInt() ?: m["weekday"]?.toString()?.toIntOrNull()
            if (week == null || weekday == null) return@mapNotNull null
            CourseOverride(
                id = m["id"]?.toString() ?: "$week-$weekday-$key",
                week = week,
                weekday = weekday,
                courseKey = key,
                room = m["room"]?.toString()?.takeIf { it.isNotBlank() },
                periods = m["periods"]?.toString()?.takeIf { it.isNotBlank() },
                // 老数据没有这个键 —— VoidMode.of 返回 null,一律当"正常"
                voided = VoidMode.of(m["voided"]?.toString()),
                note = m["note"]?.toString().orEmpty(),
            )
        }

    private companion object {
        const val TAG = "XqCourseOverride"
    }
}
