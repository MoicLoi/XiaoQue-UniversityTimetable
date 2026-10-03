package com.xiqueer.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * 喜鹊儿协议 —— 密码原语。
 *
 * 对应 协议规格 §2(`param` / `ba.b.k`)、§2 逆运算(`ba.b.j`)、
 * §3(`param2`)、§5.3(`urlEscape`)。
 *
 * 正确性由同一套一致性测试向量保证,不要凭直觉改这里的边界处理。
 */
object XqCipher {

    private const val B36 = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val HEX = "0123456789abcdef"

    /** 10^0 .. 10^9 —— 末组位宽校验用,w 只会是 3/6/9。 */
    private val POW10 = longArrayOf(
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L,
        10_000_000L, 100_000_000L, 1_000_000_000L,
    )

    fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 小写 base36,无前导零(`0` 输出 `"0"`)。对应 `ba.b.i`。 */
    fun base36(n: Long): String {
        if (n < 0) return "-" + base36(-n)
        if (n == 0L) return "0"
        var v = n
        val sb = StringBuilder()
        while (v > 0) {
            sb.append(B36[(v % 36).toInt()])
            v /= 36
        }
        return sb.reverse().toString()
    }

    /**
     * SPEC §2 —— `ba.b.k(str, key)`,产出 `param`。
     *
     * 每字符 → 3 位十进制(code + 循环密钥 code + ceil2);数字流每 9 位一组,
     * 解析为十进制后转 6 位小写 base36。
     *
     * 注意 `takeLast(3)` / `takeLast(6)`:Java 侧是 `"000" + v` 再取 `substring(len-3)`,
     * 即 **末三位**。码元 > 999 会截断,这正是明文必须是纯 ASCII 的原因
     * (App 先用 `urlEscape` 把非 ASCII 转成 `%uXXXX`)。
     */
    fun param1(str: String, key: String): String {
        val l = key.length
        val l2 = str.length
        if (l == 0 || l2 == 0) return str

        val ceil2 = ((l2 + 2) / 3) * 6 % l

        // i 从 1..l2,密钥下标取 (i-1) % l —— 与 Java 的双层循环等价
        val digits = StringBuilder(l2 * 3)
        for (i in 1..l2) {
            val v = str[i - 1].code + key[(i - 1) % l].code + ceil2
            digits.append(threeDigits(v))
        }

        val out = StringBuilder()
        var i = 0
        while (i < digits.length) {
            val end = minOf(i + 9, digits.length)
            out.append(sixBase36(digits.substring(i, end).toLong()))
            i += 9
        }
        return out.toString()
    }

    private fun threeDigits(v: Int): String {
        val t = v.toString()
        return if (t.length >= 3) t.substring(t.length - 3) else t.padStart(3, '0')
    }

    private fun sixBase36(v: Long): String {
        val t = base36(v)
        return if (t.length >= 6) t.substring(t.length - 6) else t.padStart(6, '0')
    }

    /**
     * SPEC §2 —— `param1` 的逆运算(`ba.b.j`)。
     *
     * 编码端每字符出 3 位、每 9 位重组,所以末组除了明文长度是 3 的倍数外都是短的。
     * 组数只能给出长度的上界,因此这里枚举候选长度,取第一个不含控制字符的结果。
     */
    fun unparam1(cipher: String, key: String): String {
        val l = key.length
        val g = (cipher.length + 5) / 6
        if (l == 0 || g == 0) return ""

        val groups = LongArray(g)
        for (k in 0 until g) {
            val seg = cipher.substring(k * 6, minOf(k * 6 + 6, cipher.length))
            var n = 0L
            for (ch in seg) {
                val idx = B36.indexOf(ch.lowercaseChar())
                if (idx < 0) return ""
                n = n * 36 + idx
            }
            groups[k] = n
        }

        var fallback: String? = null
        for (l2 in (3 * (g - 1) + 1)..(3 * g)) {
            val w = 3 * l2 - 9 * (g - 1)
            if (groups[g - 1] >= POW10[w]) continue

            val sb = StringBuilder(l2 * 3)
            for (k in 0 until g - 1) sb.append(groups[k].toString().padStart(9, '0'))
            sb.append(groups[g - 1].toString().padStart(w, '0'))
            val digits = sb.toString()

            val ceil2 = ((l2 + 2) / 3) * 6 % l
            val out = StringBuilder(l2)
            var broken = false
            for (i in 0 until l2) {
                val v = digits.substring(i * 3, i * 3 + 3).toInt()
                val c = v - key[i % l].code - ceil2
                if (c < 0 || c > 0xFFFF) { broken = true; break }
                out.append(c.toChar())
            }
            if (broken) continue

            val s = out.toString()
            if (s.none { isControl(it) }) return s
            if (fallback == null) fallback = s
        }
        return fallback ?: ""
    }

    private fun isControl(c: Char): Boolean =
        (c.code < 0x20 && c != '\t' && c != '\r' && c != '\n') || c.code == 0x7F

    /**
     * SPEC §3 —— `ba.b.f(str)`,产出 `param2`:**与密钥无关**。
     *
     * `md5( md5(str) 去掉 1 基第 3、10、17、25 位 )`
     */
    fun param2(str: String): String {
        val d = md5Hex(str)
        val sb = StringBuilder(28)
        for (i in d.indices) {
            if (i == 2 || i == 9 || i == 16 || i == 24) continue
            sb.append(d[i])
        }
        return md5Hex(sb.toString())
    }

    /**
     * SPEC §5.3 —— `t9.y.a`,App 自制的 URL 转义。
     *
     * `[0-9A-Za-z-_.!~*'()]` 原样;其余 ASCII → `%XX`;其他 → `%uXXXX`。
     * 十六进制**大写**。
     *
     * **按 UTF-16 码元遍历**(等价 Java 的 `charAt`)。若改成按码点遍历,
     * emoji 会输出 `%u1F600`,而 App 输出 `%uD83D%uDE00`。
     */
    fun urlEscape(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s) {
            val c = ch.code
            when {
                c in 48..57 || c in 65..90 || c in 97..122 -> sb.append(ch)
                ch in "-_.!~*'()" -> sb.append(ch)
                c <= 127 -> sb.append('%').append(hex(c, 2))
                else -> sb.append("%u").append(hex(c, 4))
            }
        }
        return sb.toString()
    }

    private fun hex(v: Int, width: Int): String =
        v.toString(16).uppercase().padStart(width, '0')

    /** Android `Base64.URL_SAFE or Base64.NO_WRAP` 的等价物。 */
    fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * 解码 URL-safe base64,**容忍缺失的填充**。
     *
     * 为什么不能直接用 `Base64.getMimeDecoder()`:`-` 和 `_` 不在 MIME 字母表里,
     * 会被静默丢弃。内置 RSA 密钥(Android `Base64.URL_SAFE`)里就有这些字符,
     * 丢一个 DER 就截断了。签名/会话密钥同理。
     */
    fun base64UrlDecode(s: String): ByteArray {
        val n = s.replace('-', '+').replace('_', '/')
        val padded = when (n.length % 4) {
            2 -> "$n=="
            3 -> "$n="
            else -> n
        }
        return Base64.getMimeDecoder().decode(padded)
    }

    /**
     * URL 百分号解码,但**不**把 `+` 当空格(与 JS 的 `decodeURIComponent` 对齐)。
     *
     * ⚠️ 必须用 `decode(String, String)` 这个重载:`decode(String, Charset)` 是
     * **Java 10+ 才有的**,Android 上没有 —— 桌面 JVM 单测跑得通,真机会抛
     * `NoSuchMethodError`。协议层只能使用 API 26 上确实存在的 JDK API。
     */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
