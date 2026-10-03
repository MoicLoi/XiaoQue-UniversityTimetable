package com.xiqueer.android.agent

/**
 * 工具风险等级。**执行门就是靠这个分流的。**
 *
 * | 等级 | 行为 |
 * |---|---|
 * | [Local] | 只改 App 本地设置(可逆),自动执行 |
 * | [Read] | 只读接口,自动执行 |
 * | [Write] | **只生成计划,物理上不发请求**,必须用户点确认 |
 * | [Irreversible] | 同 Write,但要求二次确认 |
 */
enum class Risk { Local, Read, Write, Irreversible }

data class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
)

data class XqTool(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val risk: Risk = Risk.Read,
)

/** 待用户确认的写操作。 */
data class PendingAction(
    val toolName: String,
    val title: String,
    /** 人话描述:用户看到的"将要发生什么"。 */
    val summary: String,
    /** 将发出的请求原样展示(脱敏后),便于核对。 */
    val requestPreview: String,
    val arguments: Map<String, String>,
    val risk: Risk,
)

/** 生成 OpenAI function-calling 的 JSON Schema(手写,避免引 JSON 序列化库)。 */
fun XqTool.toJsonSchema(): String {
    val props = params.joinToString(",") { p ->
        """${jsonStr(p.name)}:{"type":"string","description":${jsonStr(p.description)}}"""
    }
    val required = params.filter { it.required }.joinToString(",") { jsonStr(it.name) }
    return """{"type":"function","function":{"name":${jsonStr(name)},"description":${jsonStr(description)},"parameters":{"type":"object","properties":{$props},"required":[$required]}}}"""
}

internal fun jsonStr(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
