package com.xiqueer.android.agent

import java.time.LocalDate

/** 助手在跑一轮对话时抛给 UI 的事件。 */
sealed interface AgentEvent {
    /** 模型说了一段话(可能后面还要调工具)。 */
    data class Say(val text: String) : AgentEvent

    /** 正在调用某个工具(UI 可以显示"正在查成绩…")。 */
    data class Calling(val name: String, val args: Map<String, String>) : AgentEvent

    /** 工具返回了。 */
    data class Called(val name: String, val result: String) : AgentEvent

    /** 需要用户确认的写操作 —— UI 必须弹确认框。 */
    data class Confirm(val action: PendingAction) : AgentEvent

    /** 出错了(网络/模型/工具),对话结束。 */
    data class Failed(val message: String) : AgentEvent

    /** 一轮结束。 */
    object Done : AgentEvent
}

/**
 * 工具调用循环。
 *
 * 关键约束(与 [ToolRegistry] 一起构成安全边界):
 * - 写操作**只**产出 [AgentEvent.Confirm],循环立刻停;
 * - 模型看不到 token / 密码 —— 这些从不出现在任何工具返回值里;
 * - 工具返回值全部经过截断,避免一次查全部通知把上下文撑爆。
 */
class AgentRunner(
    private val tools: ToolRegistry,
    private val configProvider: () -> AgentConfig,
    /**
     * API Key 单独取 —— 它在 Keystore 加密存储里,只有真要发请求时才解出来,
     * 而不是随 [AgentConfig] 在内存里到处传、也不是每帧都解密。
     */
    private val apiKeyProvider: () -> String,
    private val maxRounds: Int = 6,
) {

    /** 完整对话历史,UI 重启后如果还想留着就得自己持久化。 */
    val history = ArrayList<ChatMessage>()

    private var pending: PendingAction? = null

    /** 上一次生成的待确认动作(没有就是 null)。 */
    val pendingAction: PendingAction? get() = pending

    fun clearPending() {
        pending = null
    }

    fun reset() {
        history.clear()
        pending = null
    }

    /**
     * 发一条用户消息并跑完整轮循环。
     *
     * @param userText 用户输入
     * @param onEvent  事件回调(在调用方协程上下文里执行,可直接改 UI 状态)
     */
    suspend fun send(userText: String, onEvent: suspend (AgentEvent) -> Unit) {
        val config = configProvider()
        if (!config.usable) {
            onEvent(AgentEvent.Failed("AI 未启用或没填 API Key。请到「设置 → AI」里配置。"))
            onEvent(AgentEvent.Done)
            return
        }

        history.add(ChatMessage(role = "user", content = userText))
        // 历史里塞 system 会每轮重复,所以只在请求时临时前置
        val system = ChatMessage(role = "system", content = SYSTEM_PROMPT())
        val client = LlmClient(config.baseUrl, apiKeyProvider(), config.model)
        val toolsJson = tools.schemaJson(config.exposeWriteTools)

        try {
            for (round in 0 until maxRounds) {
                // 发请求前裁剪:纯聊天(不调工具)的会话也要受长度约束
                trimHistory()
                val reply = client.chat(listOf(system) + history, toolsJson)

                if (reply.text.isNotBlank()) {
                    onEvent(AgentEvent.Say(reply.text))
                }
                if (!reply.wantsTools) {
                    if (reply.text.isBlank()) onEvent(AgentEvent.Say("(模型没有返回内容)"))
                    history.add(ChatMessage(role = "assistant", content = reply.text))
                    onEvent(AgentEvent.Done)
                    return
                }

                // 记下这次 assistant 的 tool_calls,后续必须有配对的 tool 消息
                history.add(
                    ChatMessage(role = "assistant", content = reply.text, toolCalls = reply.toolCalls),
                )

                for (call in reply.toolCalls) {
                    val args = LlmClient.parseArgs(call.arguments)
                    onEvent(AgentEvent.Calling(call.name, args))

                    val tool = tools.tool(call.name)
                    if (tool == null) {
                        val msg = "错误:没有名为 ${call.name} 的工具"
                        history.add(ChatMessage(role = "tool", content = msg, toolCallId = call.id))
                        onEvent(AgentEvent.Called(call.name, msg))
                        continue
                    }

                    // ---- 执行门 ----
                    if (tool.risk == Risk.Write || tool.risk == Risk.Irreversible) {
                        val plan = tools.plan(call.name, args)
                        if (plan == null) {
                            val msg = "错误:无法为 ${call.name} 生成计划"
                            history.add(ChatMessage(role = "tool", content = msg, toolCallId = call.id))
                            onEvent(AgentEvent.Called(call.name, msg))
                            continue
                        }
                        pending = plan
                        // 告诉模型"计划已排队,等用户确认",让它别重试
                        history.add(
                            ChatMessage(
                                role = "tool",
                                content = "已生成待确认计划,正在等用户点击确认。不要重复调用该工具;" +
                                    "现在请用一句话告诉用户去点确认。",
                                toolCallId = call.id,
                            ),
                        )
                        onEvent(AgentEvent.Confirm(plan))
                        // 写操作出现即停 —— 不让模型继续堆动作
                        onEvent(AgentEvent.Done)
                        return
                    }

                    val result = tools.execute(call.name, args)
                    history.add(ChatMessage(role = "tool", content = result, toolCallId = call.id))
                    onEvent(AgentEvent.Called(call.name, result))
                }
            }
            onEvent(AgentEvent.Say("工具调用轮次已达上限,先停下来。你可以把问题拆小一点再问。"))
            onEvent(AgentEvent.Done)
        } catch (e: LlmException) {
            onEvent(AgentEvent.Failed(e.message ?: "模型调用失败"))
            onEvent(AgentEvent.Done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onEvent(AgentEvent.Failed("${e.javaClass.simpleName}: ${e.message}"))
            onEvent(AgentEvent.Done)
        }
    }

    /**
     * 限制历史长度。
     *
     * 两条约束:
     * 1. 不能无限增长 —— 每次请求都要把整个历史发出去,token 会一路涨;
     * 2. **不能从中间切断工具调用** —— `tool` 消息必须紧跟带 `tool_calls` 的 assistant 消息,
     *    否则服务端直接 400。所以从头丢,遇到 `tool` 就一起丢掉。
     */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY) history.removeAt(0)
        // 开头只剩"半截工具调用"时必须继续丢:孤立的 tool 消息、以及后面没有 tool 应答的
        // assistant(tool_calls),服务端都会直接判非法
        while (history.isNotEmpty()) {
            val head = history[0]
            val dangling = head.role == "tool" || (head.role == "assistant" && head.toolCalls.isNotEmpty())
            if (!dangling) break
            history.removeAt(0)
        }
    }

    /** 带工具调用的 assistant 消息后面必须跟配对,所以裁剪只能按"整轮"来。 */
    private companion object {
        const val MAX_HISTORY = 24
    }

    private fun SYSTEM_PROMPT(): String = """
        你是「小鹊课表」内的助手,只能通过工具获取真实数据。今天是 ${LocalDate.now()}。

        硬性规则:
        1. 绝不编造课程、成绩、考试、通知;工具没返回的就明说没查到。
        2. 判空要诚实:2026 级新生没有排考、没有成绩都属正常。
        3. 作息时间:学校接口不提供每节的上课时间。用户要"课前 X 分钟提醒"这类精确功能时,
           请他提供作息表,再用 import_period_times 写入。**绝不要自己猜 08:00 之类的默认值。**
        4. 选课:本校选课接口在公网不可达。search_open_courses 报不可达时如实说明,不要给假课程列表。
        5. 写操作(如 submit_course)只会生成待确认计划,用户点了确认才会真的提交。生成后用一句话
           说明"已在下方生成计划,请确认",不要重复调用。
        6. 中文回答,简洁;不要在回复里暴露学号、token 或任何密钥。
    """.trimIndent()
}
