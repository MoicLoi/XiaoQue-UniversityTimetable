package com.xiqueer.protocol

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher

/**
 * 喜鹊儿协议 —— RSA 层。对应 协议规格 §4。
 *
 * App 用一对 1024-bit RSA 密钥给每个请求签名,密钥以混淆字符串内嵌:
 * 两个来自 `libnative-lib.so` 的 native 常量 + 四个 DEX 常量,拼起来过一遍
 * `param` 密码的逆运算(`ba.b.j`),密钥就是 `DMKEY`。
 *
 *   j10(PKCS#8 私钥) = bj(NDK_APP + Q0_A + K0_A, DMKEY)
 *   j11(X.509  公钥) = bj(NDK_SER + Q0_B + K0_B, DMKEY)
 *
 * ⚠️ **j10 与 j11 不是一对密钥。** 各自是不同服务器密钥对的"客户端那一半":
 * `j11` 的私钥在服务器(解会话密钥),`j10` 的公钥在服务器(验签)。
 * 所以本地没有 encrypt→decrypt 往返可做验证,别写那种单测。
 */
object XqRsa {

    const val DMKEY = "PUT-YOUR-DMKEY-HERE"

    /**
     * 客户端版本号 —— **这是登录的版本闸门**。
     *
     * 服务端在 `getLoginInfoNew` 上比对明文字段 `appver`,低于 `2.6.452` 一律回
     * `flag:"-99"` + 兜底文案「有新版本啦…必须更新后才能登录」。实测(`2026-10`)
     * `2.6.451`/`2.6.45`/`1.0.0` 被拒,`2.6.452` 及以上(含 `2.6.4550`、`3.0.0`)通过
     * —— 所以是**数值比较 `appver >= 2.6.452`**,不是等值匹配。
     *
     * ⚠️ **改版本号时必须同时改 [APPVER] 和 [APPINFO]。** 两者不同源、也不在同一个地方:
     * [APPINFO] 是**请求体**里的第九个字段(信封层),`appver` 是**业务明文**里的字段。
     * 只改其中一个,表现就是"明明升级了版本号还是登不上"。
     *
     * 另外注意一个**反直觉点**:服务端对签名字段(`encrptSecretKey` / `xqerSign`)
     * **只检查存在性、不校验内容** —— 换成任意非空垃圾字符串都能通过,只有**删掉**才报
     * `errcode:-2`。但这**不代表**本文件里的 `PUT-YOUR-*` 占位符可以直接用:
     * 请求里的这两个字段是本地用 [publicKey] / [privateKey] 算出来的,而占位符解不出合法 DER
     * (实测 `unparam1` 虽然不抛异常,但只得到 6 字节、首字节 `0x22`,而 DER SEQUENCE 应为 `0x30`),
     * 所以那两个惰性属性会构造失败,请求根本发不出去。
     * 也就是说:**要能发出请求,这些常量必须解得出合法的 RSA DER**;
     * 而**要能读懂响应**,还必须自己准备真实的 [RESP_KEY] / [RESP_IV](响应是 AES 密文)。
     */
    const val APPVER = "2.6.455"
    const val APPINFO = "android2.6.455"
    const val SERVICE_URL = "https://api.xiqueer.com/manager/"

    /** 响应解密用的固定 AES-128-CBC 密钥/IV(`t9.ba.a` AESAPPUtil)。 */
    val RESP_KEY: ByteArray = "PUT-RESP-KEY-16B".toByteArray(Charsets.UTF_8)
    val RESP_IV: ByteArray = "PUT-RESP-IV--16B".toByteArray(Charsets.UTF_8)

    // ---- 内嵌混淆常量 ----
    // getStringFromNDKAPP() / getStringFromNDKSER(),取自 lib/arm64-v8a/libnative-lib.so
    const val NDK_APP = "PUT-YOUR-NDK_APP-HERE"
    const val NDK_SER = "PUT-YOUR-NDK_SER-HERE"
    // t9.q0 / t9.k0 (LogUtil*SIGE / InternetTool*SIGE)
    const val Q0_A = "PUT-YOUR-Q0_A-HERE"
    const val Q0_B = "PUT-YOUR-Q0_B-HERE"
    const val K0_A = "PUT-YOUR-K0_A-HERE"
    const val K0_B = "PUT-YOUR-K0_B-HERE"

    /** 私钥的 base64(PKCS#8 DER,846 字符)—— 向量里也会比对。 */
    val privateKeyB64: String by lazy { XqCipher.unparam1(NDK_APP + Q0_A + K0_A, DMKEY) }

    /** 公钥的 base64(X.509 DER,216 字符)。 */
    val publicKeyB64: String by lazy { XqCipher.unparam1(NDK_SER + Q0_B + K0_B, DMKEY) }

    /**
     * 内置密钥的 base64 是 **Android `Base64.URL_SAFE` 且无填充** —— 载荷里会出现
     * `-` 和 `_`(实测私钥 846 字符里有 26 个)。交给 [XqCipher.base64UrlDecode] 处理。
     */
    val privateKey: PrivateKey by lazy {
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(XqCipher.base64UrlDecode(privateKeyB64)))
    }

    val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(XqCipher.base64UrlDecode(publicKeyB64)))
    }

    /**
     * `j10` 对应的**公钥**。服务器持有同一把,用来验证 `xqerSign`。
     * 本地只用于校验签名块形状(`RSA/ECB/NoPadding` 裸解后应是 `00 01 FF..FF 00 <md5>`),
     * **不是** `j11`。
     */
    fun publicKeyOfPrivate(): PublicKey {
        val priv = privateKey as java.security.interfaces.RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.RSAPublicKeySpec(priv.modulus, priv.publicExponent))
    }

    /**
     * SPEC §4.2 —— `encrptSecretKey` = `RSA/ECB/PKCS1Padding` 用 j11 加密会话密钥,
     * 输出 URL-safe 无填充 base64。
     *
     * PKCS#1 v1.5 带随机填充,所以同样的输入每次结果都不同,只校验形状不校验字节。
     */
    fun encryptSecretKey(key: String = DMKEY): String {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, publicKey)
        return XqCipher.base64UrlNoPad(c.doFinal(key.toByteArray(Charsets.UTF_8)))
    }

    /**
     * SPEC §4.3 —— `xqerSign`。
     *
     * `md5("param=<param>&param2=&timestamp=<ts>&echo=<echo>" + encrptSecretKey)`
     * 再用 j10 做私钥加密(即签名)。注意签名串里 `param2=` 是**空的**。
     */
    fun sign(encrptSecretKey: String, param: String, timestamp: String, echo: String): String {
        val digest = XqCipher.md5Hex(
            "param=$param&param2=&timestamp=$timestamp&echo=$echo$encrptSecretKey"
        )
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, privateKey)
        return XqCipher.base64UrlNoPad(c.doFinal(digest.toByteArray(Charsets.UTF_8)))
    }

    private const val ECHO_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

    /** SPEC §1 —— 16 位随机,字符集**不含 `0`**。 */
    fun echo16(random: Random = Random()): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(ECHO_CHARS[random.nextInt(ECHO_CHARS.length)]) }
        return sb.toString()
    }

    /** SPEC §1 —— `currentTimeMillis` 的前 10 位十进制。 */
    fun timestamp10(now: Long = System.currentTimeMillis()): String =
        now.toString().take(10)
}
