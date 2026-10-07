package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.XqJson
import java.io.File

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
 * [room] / [periods] 为 `null` 表示**这一项不改**;两项都是 null 的条目没有意义,
 * [CourseOverrideStore.put] 会直接把它删掉而不是存一条空操作。
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
    val note: String = "",
) {
    /** 这条覆写是否真的改变了什么 —— 全是 null 就是空操作。 */
    val effective: Boolean get() = !room.isNullOrBlank() || !periods.isNullOrBlank()

    /** 把覆写打到一节课上。两项都为 null 时原样返回。 */
    fun applyTo(c: Course): Course = c.copy(
        room = room?.takeIf { it.isNotBlank() } ?: c.room,
        periods = periods?.takeIf { it.isNotBlank() } ?: c.periods,
    )

    /** 人话描述,给管理面板与 AI 工具回执用。 */
    fun describe(): String = buildString {
        append("第 ").append(week).append(" 周 ")
        append(com.xiqueer.protocol.XqFeatures.weekdayName(weekday))
        val bits = ArrayList<String>(2)
        if (!periods.isNullOrBlank()) bits.add("节次 → $periods")
        if (!room.isNullOrBlank()) bits.add("教室 → $room")
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
     * @return 实际落地的条目;两项都没给(等价于清除)时返回 null
     */
    fun put(
        week: Int,
        weekday: Int,
        courseKey: String,
        room: String?,
        periods: String?,
        note: String = "",
    ): CourseOverride? {
        if (week <= 0 || weekday !in 0..6 || courseKey.isBlank()) return null

        val r = room?.trim()?.takeIf { it.isNotEmpty() }
        val p = periods?.trim()?.takeIf { it.isNotEmpty() }
        val list = all().toMutableList()
        list.removeAll { it.week == week && it.weekday == weekday && it.courseKey == courseKey }

        // 两项都没给 = 清除覆写,不要留一条空操作在文件里
        if (r == null && p == null) {
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
                note = m["note"]?.toString().orEmpty(),
            )
        }

    private companion object {
        const val TAG = "XqCourseOverride"
    }
}
