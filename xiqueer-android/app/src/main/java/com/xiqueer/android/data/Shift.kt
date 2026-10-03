package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.XqJson
import java.io.File
import java.time.LocalDate

/**
 * 一次调休:把 [from] 那天的课挪到 [to] 那天上。
 *
 * [courses] 为 `null` 表示整天都挪;非 null 表示只挪其中这几门(按课程名)。
 * 日期存字符串(`yyyy-MM-dd`)而不是 `LocalDate` —— 序列化时不用处理任何日期格式。
 */
data class Shift(
    val id: String,
    val from: String,
    val to: String,
    val courses: List<String>? = null,
    val note: String = "",
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
     * @return 实际写入的条目;被拒绝时返回 null(调用方据此给出人话解释)
     */
    fun add(from: String, to: String, courses: List<String>?, note: String = ""): Shift? {
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
        )
        list.add(shift)
        write(list)
        return shift
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
                append("""{"id":${str(s.id)},"from":${str(s.from)},"to":${str(s.to)}""")
                append(""","note":${str(s.note)}""")
                append(""","courses":""")
                if (s.courses == null) {
                    append("null")
                } else {
                    append(s.courses.joinToString(",", "[", "]") { str(it) })
                }
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
            Shift(id, from, to, courses?.takeIf { it.isNotEmpty() }, m["note"]?.toString().orEmpty())
        }

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    private companion object {
        const val TAG = "XqShift"
    }
}
