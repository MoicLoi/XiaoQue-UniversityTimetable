package com.xiqueer.android.data

import com.xiqueer.protocol.XqTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [XqTransport] 的 OkHttp 实现。
 *
 * 协议层不碰 socket,只认这个接口 —— 换 HttpURLConnection / Ktor 都不用动协议代码。
 *
 * **用 `enqueue` + `suspendCancellableCoroutine`,不用阻塞的 `execute()`**:
 * 阻塞调用会让协程取消传不到网络层 —— 网络卡住时 `viewModelScope.cancel()` 也停不下来,
 * 线程一直被占。这里在 `invokeOnCancellation` 里 `call.cancel()`,取消是**真的**取消。
 */
class OkHttpTransport : XqTransport {

    override suspend fun post(url: String, body: String, headers: Map<String, String>): String =
        suspendCancellableCoroutine { cont ->
            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody(FORM))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val call = client.newCall(builder.build())

            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val text = response.use {
                        runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                    }
                    if (cont.isActive) cont.resume(text)
                }
            })
        }

    companion object {
        private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        /**
         * **单例** —— OkHttpClient 自带连接池与线程池,官方也明确建议复用。
         * 之前它建在 [com.xiqueer.android.XqRepository] 的构造里,每个 ViewModel 一个纯属浪费。
         */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
