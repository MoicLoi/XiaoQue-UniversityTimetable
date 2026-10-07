package com.xiqueer.android.data

/**
 * 极简 JSON 写出用的字符串转义。
 *
 * 协议层的 `XqJson` 只有解析器没有序列化器 —— 本地这几个覆盖层存的数据太小,
 * 为它引一个 JSON 库不值。所以这里只提供**唯一一份**转义实现:
 * 三个 Store 各写一遍迟早会长歪(本项目已经因为"同一个规则两处实现"踩过坑)。
 */
internal object JsonText {

    /** 把 [s] 包成一个合法的 JSON 字符串字面量(含两侧引号)。 */
    fun quote(s: String): String = buildString {
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

    /** 可空字符串:null 写成 JSON `null`,而不是空串 —— 两者语义不同。 */
    fun quoteOrNull(s: String?): String = if (s == null) "null" else quote(s)

    /** 字符串数组。 */
    fun quoteList(list: List<String>): String =
        list.joinToString(",", "[", "]") { quote(it) }
}
