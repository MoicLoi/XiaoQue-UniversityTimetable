package com.xiqueer.protocol

/**
 * 协议层**不碰 socket**。调用方注入一个把 urlencoded body POST 出去、
 * 返回原始响应文本的实现即可。
 *
 * * Android:OkHttp / `HttpURLConnection`(建议在 IO dispatcher 上调用)
 * * JVM 测试:直接返回固定字符串的桩
 * * 桌面 / 服务端:`java.net.http.HttpClient`
 *
 * **`post` 是 `suspend` 的**,这样取消信号才能真的传到网络层
 * (Android 端用 `enqueue` + `suspendCancellableCoroutine`,取消时 `Call.cancel()`)。
 * 阻塞实现会让协程取消形同虚设 —— 网络卡住时线程一直被占。
 *
 * `suspend` 是语言特性、`kotlin.coroutines` 在 stdlib 里,**不引入任何依赖**。
 */
interface XqTransport {
    suspend fun post(url: String, body: String, headers: Map<String, String>): String
}

/** 接口返回了非预期的内容(通常是 `errcode`)。 */
class XqApiException(
    message: String,
    val errcode: String? = null,
    val payload: Map<String, Any?>? = null,
) : RuntimeException(message)
