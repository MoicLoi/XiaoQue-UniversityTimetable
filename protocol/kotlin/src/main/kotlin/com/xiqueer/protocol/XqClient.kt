package com.xiqueer.protocol

/** 登录结果。`flag` 的语义见 协议规格 §8。 */
data class XqLoginResult(
    val ok: Boolean,
    val flag: String?,
    val message: String?,
    val user: XqUser?,
    val data: Map<String, Any?>,
) {
    companion object {
        fun describe(flag: String?): String = when (flag) {
            "0" -> "成功"
            "1" -> "用户名或密码错误"
            "-99" -> "签名/版本校验未通过(缺 encrptSecretKey 或 xqerSign)"
            "2" -> "服务器异常"
            "3" -> "账号已锁定"
            null -> "登录失败"
            else -> "登录失败 (flag=$flag)"
        }
    }
}

/**
 * 喜鹊儿协议客户端。对应 协议规格 与 同一协议的 JS 参考实现。
 *
 * ```kotlin
 * val client = XqClient(transport = okHttpTransport())
 * val r = client.login("2026...", "******", "10162")
 * if (!r.ok) error(r.message)
 * val kb = client.call("getKb", mapOf("step" to "kbdetail_bz", "xnxq" to "20260"))
 * ```
 *
 * 联网方法都是 `suspend` 的,取消信号会一路传到 [XqTransport](见那里的说明)。
 */
class XqClient(
    private val transport: XqTransport,
    val serviceUrl: String = XqRsa.SERVICE_URL,
    initialUser: XqUser = XqUser(),
    private val appinfo: String = XqRsa.APPINFO,
    private val appsjxh: String = "Android",
) {

    var user: XqUser = initialUser
        private set

    /**
     * 每次**认证态**调用结束后回调一次。
     *
     * @param ok 这次请求服务端是否正常受理(业务错误也算 false)
     * @param sessionExpired 是不是"服务端不认这个会话"(`errcode:-1`)。
     *   单独给一个位是因为**网络抖动绝不能算作会话失效** ——
     *   那会把"后台请求没连上网"误记成"该重新登录了"。
     *
     * 加它是为了量一个我们不知道的数:**会话有效期到底多长**。
     * 只有同时知道"上次成功是什么时候"和"第一次被拒是什么时候",
     * 才能区分两种截然不同的机制:
     *
     * - 被拒时刻 ≈ 登录时刻 + TTL,与最近的请求无关 → **绝对有效期**;
     * - 被拒时刻 ≈ 上次成功 + TTL → **滑动有效期**(每次请求续命)。
     *
     * 这两者对应完全相反的应对(前者要提前重登,后者只要别让后台请求断掉),
     * 所以不能靠猜。协议层不碰 Android,回调由调用方注入。
     */
    var onAuthResult: ((ok: Boolean, sessionExpired: Boolean) -> Unit)? = null

    val endpoint: String
        get() = if (serviceUrl.endsWith("/")) serviceUrl + WAP_PATH else "$serviceUrl/$WAP_PATH"

    /** SPEC §1 + §6:发九字段,返回**已解码**的响应文本(缓存友好)。 */
    suspend fun sendText(plain: String, token: String = user.token): String {
        val fields = XqEnvelope.buildFields(
            plain,
            EnvelopeOptions(token = token, appinfo = appinfo, appsjxh = appsjxh),
        )
        val body = fields.entries.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        val headers = if (user.jwt.isNotEmpty()) mapOf("Authorization_kingo" to user.jwt) else emptyMap()
        return XqEnvelope.decodeResponse(transport.post(endpoint, body, headers))
    }

    suspend fun send(plain: String, token: String = user.token): Map<String, Any?> =
        parseObject(sendText(plain, token))

    /** 认证态调用(SPEC §5.1)。 */
    suspend fun call(action: String, params: Map<String, String?> = emptyMap()): Map<String, Any?> =
        parseObject(callText(action, params))

    /**
     * 认证态调用的原始响应文本 —— 仓储层缓存用这个,避免再写一个 JSON 序列化器。
     *
     * 业务级失败在**这里**统一拦(见 [XqErrors]):所有认证态接口都经过它,
     * 所以不会有"某个接口忘了查 errcode、静默返回空列表"这种事。
     */
    suspend fun callText(action: String, params: Map<String, String?> = emptyMap()): String {
        val text = sendText(buildAuthPlain(action, params))
        // 成功与否都回报一次:调用方靠它记"上次成功时刻",才能量出会话有效期
        return try {
            XqErrors.raiseIfFailed(action, text)
            onAuthResult?.invoke(true, false)
            text
        } catch (e: Throwable) {
            onAuthResult?.invoke(false, XqErrors.isSessionExpired(e))
            throw e
        }
    }

    /** 组装认证态明文(`userId`/`usertype` 已在 map 内,不会重复追加)。纯计算,不联网。 */
    fun buildAuthPlain(action: String, params: Map<String, String?> = emptyMap()): String {
        val map = LinkedHashMap<String, String?>()
        map["userId"] = user.userid
        map["usertype"] = user.usertype
        map["action"] = action
        map.putAll(params)
        return XqEnvelope.authPlain(map, user)
    }

    private fun parseObject(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val asMap = runCatching { XqJson.parse(json) }.getOrNull() as? Map<String, Any?>
        return asMap ?: mapOf("raw" to json)
    }

    /** 匿名调用(SPEC §5.2),token 固定 `"00000"`。 */
    suspend fun callAnon(map: Map<String, String?>): Map<String, Any?> =
        send(XqEnvelope.anonPlain(map), token = "00000")

    /** SPEC §7 —— 登录并采用返回的身份。 */
    suspend fun login(
        username: String,
        password: String,
        xxdm: String,
        model: String = "Android",
        osVersion: String = "11",
    ): XqLoginResult {
        val data = callAnon(XqEnvelope.loginMap(username, password, xxdm, model, osVersion))
        val flag = data["flag"]?.toString()
        if (flag != "0") {
            return XqLoginResult(false, flag, data["msg"]?.toString() ?: XqLoginResult.describe(flag), null, data)
        }
        val next = user.copy(
            userid = data["userid"]?.toString() ?: user.userid,
            uuid = data["uuid"]?.toString() ?: user.uuid,
            usertype = data["usertype"]?.toString() ?: "STU",
            xm = data["xm"]?.toString() ?: "",
            xxdm = data["xxdm"]?.toString() ?: xxdm,
            md5 = data["md5"]?.toString() ?: user.md5,
            token = data["token"]?.toString() ?: "00000",
            jwt = data["jwt"]?.toString() ?: "",
        )
        user = next
        return XqLoginResult(true, flag, next.xm.ifEmpty { username }, next, data)
    }

    /** 直接采用已缓存的会话,不联网。 */
    fun setUser(u: XqUser): XqClient {
        user = u
        return this
    }

    companion object {
        const val WAP_PATH = "wap/wapController.jsp"
    }
}

// ---- 响应读取的小工具(顶层,方便调用方直接 import) ----

fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>) ?: emptyMap()
