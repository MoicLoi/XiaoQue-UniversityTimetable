package com.xiqueer.android.agent

import com.xiqueer.android.data.OkHttpTransport
import com.xiqueer.protocol.XqJson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 对话里的一条消息。 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    /** assistant 请求调用的工具。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** role = tool 时,回填对应的调用 id。 */
    val toolCallId: String = "",
)

/** 模型请求的一次函数调用。 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串 —— 由 [parseArgs] 解析。 */
    val arguments: String,
)

/** 一次补全的结果:要么说话,要么要调工具。 */
data class LlmReply(
    val text: String,
    val toolCalls: List<ToolCall>,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

class LlmException(message: String) : RuntimeException(message)

/**
 * OpenAI 兼容的 chat/completions 客户端(带 function calling)。
 *
 * 只用 OkHttp 发请求,`XqJson` 解析 —— 不引额外 JSON 库。
 * 取消语义与 [com.xiqueer.android.data.OkHttpTransport] 一致:`call.cancel()`。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {

    suspend fun chat(messages: List<ChatMessage>, toolsJson: String?): LlmReply {
        val body = buildRequest(messages, toolsJson)
        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val raw = execute(request)
        return parseReply(raw)
    }

    private fun endpoint(): String {
        val base = baseUrl.trimEnd('/')
        // 允许用户直接填 .../v1 或 .../v1/chat/completions
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun buildRequest(messages: List<ChatMessage>, toolsJson: String?): String = buildString {
        append("{\"model\":").append(jsonStr(model))
        append(",\"messages\":[")
        append(messages.joinToString(",") { m ->
            buildString {
                append("{\"role\":").append(jsonStr(m.role))
                if (m.role == "tool") {
                    append(",\"tool_call_id\":").append(jsonStr(m.toolCallId))
                }
                // 有工具调用时 content 允许为 null(部分服务端会拒绝空串)
                if (m.content.isNotEmpty() || m.toolCalls.isEmpty()) {
                    append(",\"content\":").append(jsonStr(m.content))
                } else {
                    append(",\"content\":null")
                }
                if (m.toolCalls.isNotEmpty()) {
                    append(",\"tool_calls\":[")
                    append(m.toolCalls.joinToString(",") { c ->
                        """{"id":${jsonStr(c.id)},"type":"function","function":{"name":${jsonStr(c.name)},"arguments":${jsonStr(c.arguments)}}}"""
                    })
                    append("]")
                }
                append("}")
            }
        })
        append("]")
        if (!toolsJson.isNullOrBlank()) {
            append(",\"tools\":").append(toolsJson)
            append(",\"tool_choice\":\"auto\"")
        }
        append("}")
    }

    /** 只认 OpenAI 的响应结构;不认识的结构就把原文抛出来,好排查。 */
    private fun parseReply(raw: String): LlmReply {
        val root = runCatching { XqJson.parse(raw) as? Map<*, *> }.getOrNull()
            ?: throw LlmException("响应不是 JSON 对象:${raw.take(300)}")
        (root["error"] as? Map<*, *>)?.let { err ->
            throw LlmException("模型服务报错:${err["message"] ?: err.toString()}")
        }
        val choice = (root["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw LlmException("响应里没有 choices:${raw.take(300)}")
        val message = choice["message"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

        val text = message["content"]?.toString().orEmpty()
        val calls = (message["tool_calls"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { c ->
                val fn = c["function"] as? Map<*, *> ?: return@mapNotNull null
                val name = fn["name"]?.toString() ?: return@mapNotNull null
                ToolCall(
                    id = c["id"]?.toString().orEmpty(),
                    name = name,
                    arguments = fn["arguments"]?.toString().orEmpty().ifEmpty { "{}" },
                )
            }.orEmpty()

        return LlmReply(text = text, toolCalls = calls)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = OkHttpTransport.client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(LlmException("网络失败:${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.use { runCatching { it.body?.string().orEmpty() }.getOrDefault("") }
                if (!cont.isActive) return
                if (!response.isSuccessful) {
                    cont.resumeWithException(LlmException("HTTP ${response.code}:${text.take(300)}"))
                } else {
                    cont.resume(text)
                }
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 把模型给的 arguments 解析成 `Map<String,String>`。
         * 模型偶尔会返回非字符串标量,这里统一 stringify —— 工具层只认字符串更省事。
         */
        fun parseArgs(raw: String): Map<String, String> {
            val obj = runCatching { XqJson.parse(raw) as? Map<*, *> }.getOrNull() ?: return emptyMap()
            return obj.entries.mapNotNull { (k, v) ->
                val key = k?.toString() ?: return@mapNotNull null
                key to when (v) {
                    null -> ""
                    is String -> v
                    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                    else -> v.toString()
                }
            }.toMap()
        }
    }
}
