package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.XqClient
import com.xiqueer.protocol.XqJson
import java.io.File

/** 一所学校。字段来自 `getAgent`。 */
data class School(
    val xxdm: String,
    val xxmc: String,
    /** 全拼,如 `liaoningzhongyiyaodaxue`。用来做拼音与首字母搜索。 */
    val pinyin: String,
)

/**
 * 首字母匹配 —— **子序列**匹配,不是"取出每个音节的首字母"。
 *
 * 为什么不做真正意义的首字母:服务端给的 `pinyin` 是**连写、无音节分隔**的
 * (`liaoningzhongyiyaodaxue`),不引拼音词典就没法可靠切音节。
 *
 * ⚠️ 早先这里写的是 `pinyin.filter { it.isLetter() }`,那等于**原样返回全拼** ——
 * 于是 `lnzyy` 一条都匹配不到。更糟的是我当时的"验证"是拿整个 dump 去 `match 辽宁中医药大学`,
 * 而**登录页在弹层背后那栏刚好显示着这个校名**(那时 xxdm 还有个默认值),
 * 于是假阳性通过、bug 留下。教训:验证要断言"结果列表里出现了它",
 * 而不是"屏幕某处有这个字符串"。
 *
 * 子序列近似是可接受的:敲 `lnzyy` 时,它确实是
 * `l-iaon-ing-zhong-y-i-y-aodaxue` 的首字母序列,能命中;
 * 误报的代价只是多几条候选,而候选本来就是给用户挑的。
 */
internal fun pinyinMatchesInitials(pinyin: String, query: String): Boolean {
    if (query.length < 2 || query.length > 8) return false
    var i = 0
    for (c in pinyin) {
        if (i < query.length && c == query[i]) i++
        if (i == query.length) return true
    }
    return false
}

/**
 * 学校目录(本地缓存 + 搜索)。
 *
 * **为什么不是"让用户输名字然后正则匹配一份内置名单"**:
 * 实测 `getAgent`(匿名可调)会返回**全量名单 513 所**,每条带 `xxdm` / `xxmc` / `pinyin` ——
 * 也就是官方 App 的做法是"把名单拉下来本地搜"。所以我们照做:
 * 名单来自服务端(永远是最新的)、不占包体、并且能用**中文名 / 全拼 / 首字母**三种方式搜。
 *
 * 内置名单的方案被否掉的原因:513 条虽然只有 ~92 KB,但学校增删改名我们无法感知,
 * 而拉一次接口的成本极低(匿名、无凭据)。
 *
 * 搜索是纯本地的,所以**断网也能选学校**(只要之前拉过一次)。
 */
class SchoolDirectory(context: Context) {

    private val file = File(context.filesDir, "schools.json")

    fun cached(): List<School> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "学校名单缓存损坏,已丢弃", it)
            file.delete()
            emptyList()
        }
    }

    val hasCache: Boolean get() = file.exists() && file.length() > 0

    /** 用协议层拉到的原始行覆盖缓存。返回写入条数(0 表示没拿到,不动缓存)。 */
    fun refresh(rows: List<Map<String, Any?>>): Int {
        val schools = rows.mapNotNull { m ->
            val dm = m["xxdm"]?.toString()?.trim().orEmpty()
            val mc = m["xxmc"]?.toString()?.trim().orEmpty()
            if (dm.isEmpty() || mc.isEmpty()) null
            else School(dm, mc, m["pinyin"]?.toString()?.trim().orEmpty())
        }
        if (schools.isEmpty()) return 0
        file.writeText(serialize(schools))
        return schools.size
    }

    /** 按 `xxdm` 找学校名(登录页要把代码换成名字显示)。 */
    fun nameOf(xxdm: String): String? = cached().firstOrNull { it.xxdm == xxdm }?.xxmc

    /**
     * 本地搜索。命中优先级:
     * 完全同名 > 名称开头 > 首字母开头 > 全拼开头 > 名称包含 > 全拼包含。
     *
     * 不做正则:用户输入里一个 `.` 或 `(` 就可能让正则抛异常或者匹配到一堆无关的东西,
     * 而这里的需求本来只是"前缀 + 包含"。
     */
    fun search(query: String, limit: Int = 60): List<School> {
        val all = cached()
        val q = query.trim().lowercase().replace(" ", "")
        if (q.isEmpty()) return all.take(limit)

        val scored = ArrayList<Pair<Int, School>>()
        for (s in all) {
            val name = s.xxmc.lowercase()
            val py = s.pinyin.lowercase()
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                py.startsWith(q) -> 2
                name.contains(q) -> 3
                py.contains(q) -> 4
                // 首字母(子序列)放最后 —— 它最宽松,误报也最多
                pinyinMatchesInitials(py, q) -> 5
                else -> -1
            }
            if (score < 0) continue
            // 同样的匹配等级里,短名字更像用户要找的
            scored.add(score * 1000 + minOf(name.length, 999) to s)
        }
        return scored.sortedBy { it.first }.map { it.second }.take(limit)
    }

    private fun parse(text: String): List<School> {
        val arr = XqJson.parseArray(text)
        return arr.mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val dm = m["xxdm"]?.toString().orEmpty()
            val mc = m["xxmc"]?.toString().orEmpty()
            if (dm.isEmpty() || mc.isEmpty()) null else School(dm, mc, m["pinyin"]?.toString().orEmpty())
        }
    }

    /** 手写序列化 —— 协议层的 `XqJson` 只有解析器,不为这点数据引 JSON 库。 */
    private fun serialize(list: List<School>): String =
        list.joinToString(",", "[", "]") { s ->
            """{"xxdm":${str(s.xxdm)},"xxmc":${str(s.xxmc)},"pinyin":${str(s.pinyin)}}"""
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
        const val TAG = "XqSchool"
    }
}
