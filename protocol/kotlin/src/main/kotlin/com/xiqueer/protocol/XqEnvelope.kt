package com.xiqueer.protocol

import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 登录后持有的身份,对应 App 的 `t9.j0.PersonMessage`。 */
data class XqUser(
    val userid: String = "",
    val uuid: String = "",
    val usertype: String = "STU",
    val xm: String = "",
    val xxdm: String = "",
    /** `android.CACHE_MD5` 偏好 */
    val md5: String = "",
    val token: String = "00000",
    val jwt: String = "",
)

/** `buildFields` 的可调项;留空即用协议默认值。 */
data class EnvelopeOptions(
    val key: String = XqRsa.DMKEY,
    val token: String = "00000",
    val timestamp: String? = null,
    val echo: String? = null,
    val appinfo: String = XqRsa.APPINFO,
    val appsjxh: String = "Android",
    val random: Random = Random(),
)

/**
 * 喜鹊儿协议 —— 封套组装与响应解码。
 * 对应 `protocol/SPEC.md` §1(九字段)、§5(明文)、§6(响应)、§7(登录)。
 */
object XqEnvelope {

    /** SPEC §5.1 —— `ba.b.u(map, true, ctx)`。 */
    fun authPlain(map: Map<String, String?>, user: XqUser): String {
        var s = map.entries.joinToString("&") { (k, v) -> "${k.trim()}=${v ?: ""}" }
        if (s.startsWith("&")) s = s.substring(1)
        val sb = StringBuilder(s)
        sb.append("&xqerxm=").append(XqCipher.urlEscape(user.xm))
        sb.append("&uuid=").append(user.uuid)
        sb.append("&md5=").append(user.md5)
        if (!map.containsKey("userId")) sb.append("&userId=").append(user.userid)
        if (!map.containsKey("usertype") && !map.containsKey("userType")) {
            sb.append("&usertype=").append(user.usertype)
        }
        return sb.toString()
    }

    /** SPEC §5.2 —— `ba.b.u(map, false, ctx)`,无任何后缀。 */
    fun anonPlain(map: Map<String, String?>): String =
        map.entries.joinToString("&") { (k, v) -> "${k.trim()}=${v ?: ""}" }

    /**
     * SPEC §1 —— 返回**九个**字段。
     *
     * 缺 `encrptSecretKey` / `xqerSign` 时服务器一律回
     * `{"errcode":"-2","message":"请升级到最新版本"}`,那是签名校验失败的兜底文案,
     * 与版本无关。
     */
    fun buildFields(plain: String, opts: EnvelopeOptions = EnvelopeOptions()): Map<String, String> {
        val ts = opts.timestamp ?: XqRsa.timestamp10()
        val echo = opts.echo ?: XqRsa.echo16(opts.random)
        val param = XqCipher.param1(plain, opts.key)
        val secret = XqRsa.encryptSecretKey(opts.key)
        return linkedMapOf(
            "param" to param,
            "param2" to XqCipher.param2(plain),
            "timestamp" to ts,
            "echo" to echo,
            "encrptSecretKey" to secret,
            "xqerSign" to XqRsa.sign(secret, param, ts, echo),
            "token" to opts.token,
            "appinfo" to opts.appinfo,
            "appsjxh" to opts.appsjxh,
        )
    }

    /**
     * SPEC §6 —— 拆响应。数据类接口直接是明文 JSON;登录/配置类是 AES-128-CBC。
     */
    fun decodeResponse(body: String): String {
        val trimmed = body.trim()
        val text = XqCipher.percentDecode(trimmed)
        if (text.startsWith("{") || text.startsWith("[")) return text

        val b64 = text.replace('-', '+').replace('_', '/')
        val ct = try {
            XqCipher.base64UrlDecode(b64)
        } catch (_: Exception) {
            return text
        }
        if (ct.isEmpty() || ct.size % 16 != 0) return text

        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(XqRsa.RESP_KEY, "AES"),
                IvParameterSpec(XqRsa.RESP_IV),
            )
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            text
        }
    }

    /** SPEC §7 —— 登录 map(`getLoginInfoNew`,匿名态)。 */
    fun loginMap(
        username: String,
        password: String,
        xxdm: String,
        model: String = "Android",
        osVersion: String = "11",
    ): Map<String, String> = linkedMapOf(
        "loginId" to username,
        "xxdm" to xxdm,
        // BaseApplication.K == "1" -> 密码先做 App 自制转义
        "pwd" to XqCipher.urlEscape(password),
        "pwdsfzm" to "1",
        "action" to "getLoginInfoNew",
        "zddl" to "1",
        "isky" to "1",
        "sjbz" to "",                     // PhoneMessageTools.G0() 直接返回 ""
        "sswl" to "",                     // PhoneMessageTools.H0()
        "sjxh" to model,
        "os" to "android",
        "xtbb" to osVersion,
        "loginmode" to "0",
        "appver" to "2.6.451",
    )
}
