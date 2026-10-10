package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.XqJson
import java.io.File
import java.time.LocalDate

/**
 * 一节课的**快照** —— 创建调休时把源那天要搬的课记下来。
 *
 * ⚠️ **为什么非存不可**:调来时要把源那天的课"取出来"渲染到目标日,而课表
 * **一次只加载一周**。源日期不在当前这一周时,底表里根本查不到那几节课 ——
 * 这就是「原位置灰显了、目标位置却没有课」的成因。
 * 快照把这条路径变成自足的,不再依赖"当时有没有加载到源那一周"。
 *
 * 只存渲染需要的字段(名称/教师/教室/节次)。**刻意不存课程身份**:
 * 快照出来的那一节在底表里并不存在,给它一个身份反而会让 `courseOf`
 * 误配到同名课程上,点开详情会指向错误的一天。
 */
data class ShiftCourse(
    val name: String,
    val teacher: String = "",
    val room: String = "",
    /** 接口格式的 `jcxx`,如 `"1-2"`。 */
    val periods: String = "",
)

/**
 * 一次调休:把 [from] 那天的课挪到 [to] 那天上。
 *
 * [courses] 为 `null` 表示整天都挪;非 null 表示只挪其中这几门(按课程名)。
 * 日期存字符串(`yyyy-MM-dd`)而不是 `LocalDate` —— 序列化时不用处理任何日期格式。
 *
 * [snapshot] 是创建时记下的"源那天实际要搬的课",见 [ShiftCourse]。
 * 老记录可能为空 —— 那不影响同周调休,只有跨周时会表现为"目标位置没有课";
 * 一旦用户翻到源那一周,`AppViewModel` 会自动把它补上。
 */
data class Shift(
    val id: String,
    val from: String,
    val to: String,
    val courses: List<String>? = null,
    val note: String = "",
    val snapshot: List<ShiftCourse> = emptyList(),
) {
    val fromDate: LocalDate? get() = runCatching { LocalDate.parse(from) }.getOrNull()
    val toDate: LocalDate? get() = runCatching { LocalDate.parse(to) }.getOrNull()

    /** 一行的可读描述。 */
    fun describe(): String = buildString {
        append(from).append(" → ").append(to)
        if (!courses.isNullOrEmpty()) append(" · ").append(courses.joinToString("、"))
        if (note.isNotBlank()) append(" · ").append(note)
    }
}

/**
 * 调休的**本地覆盖层**。
 *
 * ⚠️ 这一层与"服务器课表"是**两个写者不相交**的数据:
 * - 底表(课表缓存)只由"刷新"写入;
 * - 本层只由用户 / AI 工具写入。
 *
 * 所以刷新永远不会冲掉调休,调休也永远不会污染服务器数据 ——
 * "本地缓存与服务器拉取冲突"这个问题**在结构上就不存在**,不需要冲突检测。
 */
class ShiftStore(context: Context) {

    private val file = File(context.filesDir, "shifts.json")

    fun all(): List<Shift> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "调休数据损坏,已丢弃", it)
            file.delete()
            emptyList()
        }
    }

    /**
     * 添加一条调休。
     *
     * @param snapshot 源那天要搬的课的快照。取不到时传空 —— **不要因此拒绝创建**:
     *   同周调休本来就不需要它,跨周的那部分等用户翻到源周时由 `AppViewModel` 补齐。
     * @return 实际写入的条目;被拒绝时返回 null(调用方据此给人话解释)
     */
    fun add(
        from: String,
        to: String,
        courses: List<String>?,
        snapshot: List<ShiftCourse> = emptyList(),
        note: String = "",
    ): Shift? {
        val f = runCatching { LocalDate.parse(from.trim()) }.getOrNull() ?: return null
        val t = runCatching { LocalDate.parse(to.trim()) }.getOrNull() ?: return null
        // 同一天挪给自己没有意义,直接拒掉而不是存一条空操作
        if (f == t) return null

        val list = all().toMutableList()
        // 同一 (from, to, 课程集合) 重复添加视为无操作
        val dup = list.any {
            it.from == f.toString() && it.to == t.toString() &&
                it.courses.orEmpty().toSet() == courses.orEmpty().toSet()
        }
        if (dup) return null

        val shift = Shift(
            id = "${f}_${t}_${System.currentTimeMillis()}",
            from = f.toString(),
            to = t.toString(),
            courses = courses?.takeIf { it.isNotEmpty() },
            note = note.trim(),
            snapshot = snapshot,
        )
        list.add(shift)
        write(list)
        return shift
    }

    /**
     * 补上某条调休缺掉的快照。
     *
     * 用于"创建时源日期还没被加载过、后来用户翻到了那一周"的情形 ——
     * 不补的话这条调休的跨周搬运永远是空的。
     *
     * **已经有快照就绝不动它**:后来的底表刷新不该悄悄改写"当时要搬什么"。
     *
     * @return 是否真的写入
     */
    fun setSnapshot(id: String, snapshot: List<ShiftCourse>): Boolean {
        if (snapshot.isEmpty()) return false
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        if (list[i].snapshot.isNotEmpty()) return false
        list[i] = list[i].copy(snapshot = snapshot)
        write(list)
        return true
    }

    fun remove(id: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) write(list)
        return removed
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun write(list: List<Shift>) {
        val json = list.joinToString(",", "[", "]") { s ->
            buildString {
                append("""{"id":${JsonText.quote(s.id)},"from":${JsonText.quote(s.from)},"to":${JsonText.quote(s.to)}""")
                append(""","note":${JsonText.quote(s.note)}""")
                append(""","courses":""")
                if (s.courses == null) {
                    append("null")
                } else {
                    append(s.courses.joinToString(",", "[", "]") { JsonText.quote(it) })
                }
                append(""","snapshot":""")
                append(
                    s.snapshot.joinToString(",", "[", "]") { c ->
                        """{"name":${JsonText.quote(c.name)},"teacher":${JsonText.quote(c.teacher)}""" +
                            ""","room":${JsonText.quote(c.room)},"periods":${JsonText.quote(c.periods)}}"""
                    },
                )
                append("}")
            }
        }
        file.writeText(json)
    }

    private fun parse(text: String): List<Shift> =
        XqJson.parseArray(text).mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val id = m["id"]?.toString() ?: return@mapNotNull null
            val from = m["from"]?.toString() ?: return@mapNotNull null
            val to = m["to"]?.toString() ?: return@mapNotNull null
            val courses = (m["courses"] as? List<*>)?.mapNotNull { it?.toString() }
            Shift(
                id = id,
                from = from,
                to = to,
                courses = courses?.takeIf { it.isNotEmpty() },
                note = m["note"]?.toString().orEmpty(),
                snapshot = parseSnapshot(m["snapshot"]),
            )
        }

    /**
     * 解析快照数组。
     *
     * 单独拆成一个函数而不是在 [parse] 里再嵌一层 `mapNotNull` ——
     * 嵌套之后 `return@mapNotNull` 会指向哪一个变得含糊(编译器直接报标签歧义)。
     */
    private fun parseSnapshot(raw: Any?): List<ShiftCourse> =
        (raw as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { c ->
                val name = c["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ShiftCourse(
                    name = name,
                    teacher = c["teacher"]?.toString().orEmpty(),
                    room = c["room"]?.toString().orEmpty(),
                    periods = c["periods"]?.toString().orEmpty(),
                )
            }

    private companion object {
        const val TAG = "XqShift"
    }
}
