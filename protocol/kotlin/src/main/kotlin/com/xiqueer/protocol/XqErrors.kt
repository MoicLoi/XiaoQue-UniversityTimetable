package com.xiqueer.protocol

/**
 * 服务端的**业务级失败**检查。
 *
 * 这套接口不用 HTTP 状态码表达失败,而是在**成功的 200 响应体**里塞一个业务字段。
 * 实测两种约定:
 *
 * | 场景 | 字段 | 语义 |
 * |---|---|---|
 * | 登录 | `flag` | `"0"` = 成功(注意是反的),其余为失败码 |
 * | 数据接口 | `errcode` | **出现即失败**,例如 `-1` + `message:"口令失败"` |
 *
 * 成功的响应体里**不带** `errcode` —— 这条结论有两处依据:
 * `parseTimetable` 长期在线上跑的行为(它原本就"只要有 errcode 就抛"),
 * 以及 协议规格 §5.3 记录的取值表:
 * `-1` 未登录/权限不足、`-2` 非法调用/请升级、`-5` action 不存在 —— **全是失败**。
 *
 * ⚠️ **为什么必须放在入口处**:原来只有 `parseTimetable` 一个接口查了 `errcode`,
 * 其余(成绩/考试/通知/培养方案)都是"读一个 list,读不到就当空"。
 * 于是服务端一报"口令失败",课表会报错、其它页面**静默变成空白** ——
 * 用户看到的是"App 坏了",而不是"登录过期了"。同一个判断写在 N 个接口里,
 * 必然有一个忘写。
 */
object XqErrors {

    /** `errcode` 存在且不是"成功"值就抛。非 JSON、非对象一律放过(交给上层解析报错)。 */
    fun raiseIfFailed(action: String, responseText: String) {
        val map = runCatching { XqJson.parse(responseText) }.getOrNull() as? Map<*, *> ?: return
        val code = map["errcode"]?.toString()?.trim() ?: return
        if (code.isEmpty() || code == "0") return

        val msg = map["message"]?.toString()?.trim().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val payload = map as Map<String, Any?>
        throw XqApiException(
            "$action 失败:$code" + if (msg.isEmpty()) "" else " $msg",
            code,
            payload,
        )
    }

    /**
     * 这个错误是不是"登录不被接受"。
     *
     * 服务端在会话失效时回的是**普通业务错误**(`口令失败`),不是 401,
     * 所以只能按文案识别。识别出来的意义是:界面能直接告诉用户
     * "重新登录一下就好",而不是让他对着空白页猜。
     *
     * 认不出来时返回 false —— 宁可不提示,也不要把网络抖动误报成"登录过期"。
     */
    fun isSessionExpired(t: Throwable): Boolean {
        val text = generateSequence(t) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        if (text.isEmpty()) return false
        return listOf("口令失败", "登录超时", "登录已过期", "未登录", "重新登录", "token")
            .any { text.contains(it, ignoreCase = true) }
    }
}
