package com.xiqueer.android.agent

import android.content.Context
import com.xiqueer.android.data.SecureStore

/**
 * LLM 接入配置(BYOK)。
 *
 * 默认指向 DeepSeek 的 OpenAI 兼容端点;可换任意兼容服务(含本地 Ollama:
 * `http://10.0.2.2:11434/v1` —— 模拟器里 10.0.2.2 是宿主机)。
 *
 * [apiKey] **不在这个数据类里** —— 它是机密,存在 [SecureStore](Keystore 加密)中,
 * 只有真正要发请求时才读出来。这里只留 [keyPresent] 这个明文布尔值,
 * 让界面能显示"配没配 Key"而不必每帧走一次解密。
 */
data class AgentConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-chat",
    /** 是否允许模型看到写操作(仍只生成计划,不会自动执行)。 */
    val exposeWriteTools: Boolean = true,
    /** API Key 是否已配置。**不是 Key 本身。** */
    val keyPresent: Boolean = false,
) {
    val usable: Boolean get() = enabled && keyPresent && baseUrl.isNotBlank()
}

class AgentSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    fun load(): AgentConfig = AgentConfig(
        enabled = prefs.getBoolean("enabled", false),
        baseUrl = prefs.getString("base_url", "https://api.deepseek.com/v1").orEmpty(),
        model = prefs.getString("model", "deepseek-chat").orEmpty(),
        exposeWriteTools = prefs.getBoolean("expose_write", true),
        keyPresent = secure.contains(KEY_API),
    )

    /**
     * 保存配置。
     *
     * [apiKey] 传 `null` 表示"不动已存的 Key",传空串表示"清掉 Key" ——
     * 这样设置页不需要先把 Key 解出来再原样写回去(那等于每次都多一次加解密)。
     */
    fun save(c: AgentConfig, apiKey: String? = null) {
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putString("base_url", c.baseUrl)
            .putString("model", c.model)
            .putBoolean("expose_write", c.exposeWriteTools)
            .apply()
        when {
            apiKey == null -> Unit
            apiKey.isBlank() -> secure.remove(KEY_API)
            else -> secure.put(KEY_API, apiKey.trim())
        }
    }

    /** 只在真要调模型时读:每次都会过一次 Keystore 解密。 */
    fun apiKey(): String = secure.get(KEY_API).orEmpty()

    fun clearKey() = secure.remove(KEY_API)

    private companion object {
        const val KEY_API = "api_key"
    }
}
