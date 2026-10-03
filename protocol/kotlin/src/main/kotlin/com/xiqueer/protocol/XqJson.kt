package com.xiqueer.protocol

/**
 * 极简 JSON 解析器 —— **零依赖**,以便主源集不引入任何第三方库。
 *
 * 只需覆盖喜鹊儿接口响应的形态;输出类型:
 * `Map<String, Any?>` / `List<Any?>` / `String` / `Long` / `Double` / `Boolean` / `null`。
 *
 * 不是通用实现(不支持注释、不保留数字精度语义),但对本协议足够,
 * 且被 `XqJsonTest` 用真实的 `vectors.json` 覆盖。
 */
object XqJson {

    fun parse(text: String): Any? {
        // 跳过开头的 UTF-8 BOM。
        //
        // 服务器响应不会有 BOM,但我们自己往磁盘写的 JSON 可能被别的工具
        // (编辑器、备份、`Set-Content -Encoding UTF8`)加上一个 ——
        // 而调用方一旦解析失败就按"数据损坏"处理并**删掉文件**,
        // 于是用户看到的是"我的调休怎么全没了"。为一个不可见字符丢数据不值得。
        val body = text.removePrefix("\uFEFF")
        val p = Parser(body)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) throw JsonException("trailing content at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw JsonException("not a JSON object")

    @Suppress("UNCHECKED_CAST")
    fun parseArray(text: String): List<Any?> =
        parse(text) as? List<Any?> ?: throw JsonException("not a JSON array")

    class JsonException(message: String) : RuntimeException(message)

    private class Parser(private val s: String) {
        var pos = 0
        fun eof() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun value(): Any? = when (val c = peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't', 'f' -> bool()
            'n' -> nil()
            else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c' at $pos")
        }

        private fun peek(): Char {
            if (eof()) throw JsonException("unexpected end of input")
            return s[pos]
        }

        private fun expect(c: Char) {
            if (eof() || s[pos] != c) throw JsonException("expected '$c' at $pos")
            pos++
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonException("unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) throw JsonException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (!eof() && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (!eof() && s[pos] == '.') {
                isDouble = true
                pos++
                while (!eof() && s[pos] in '0'..'9') pos++
            }
            if (!eof() && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (!eof() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!eof() && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            return if (isDouble) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }

        private fun bool(): Boolean = when {
            s.startsWith("true", pos) -> { pos += 4; true }
            s.startsWith("false", pos) -> { pos += 5; false }
            else -> throw JsonException("bad literal at $pos")
        }

        private fun nil(): Any? {
            if (!s.startsWith("null", pos)) throw JsonException("bad literal at $pos")
            pos += 4
            return null
        }
    }
}
