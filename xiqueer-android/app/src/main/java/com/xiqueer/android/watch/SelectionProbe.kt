package com.xiqueer.android.watch

import android.content.Context
import android.util.Log
import com.xiqueer.android.XqRepository

/**
 * 一轮探测的结果。
 *
 * [evidence] 是**原始证据**(通知标题原文),不是我们的推断 —— 因为本校的选课接口
 * 在公网不可达,我们只能看间接信号,那就必须把"看到了什么"原样交给用户判断。
 */
data class ProbeResult(
    val hits: List<Signal> = emptyList(),
    val polls: Int = 1,
    val error: String? = null,
) {
    data class Signal(val key: String, val title: String, val detail: String)
}

/**
 * 间接信号探测。
 *
 * **为什么不直接查选课列表:** `oriHd_wsxk` 的各个步骤在本校被转发到教务内网 `/lnzyjw/`
 * 并返回 404,公网拿不到。所以监听只能靠:
 * 1. 教务通知里出现选课相关关键词(正选/补选/退选/选课开放);
 * 2. 课表里突然冒出新课程(说明已被选中)。
 *
 * 这些都是**弱信号**,所以我们不宣称"检测到选课开放",而是把原文推给用户。
 */
object SelectionProbe {

    private const val TAG = "XqWatch"

    /** 命中即视为可能的选课信号词。 */
    private val KEYWORDS = listOf(
        "选课", "正选", "补选", "退选", "重选", "重修报名", "选课开放", "选课系统", "轮次",
    )

    suspend fun probe(context: Context): ProbeResult {
        val repo = XqRepository(context).apply { restore() }
        if (!repo.hasSession) return ProbeResult(error = "没有登录会话")

        return try {
            val notices = repo.notices()
            val hits = ArrayList<ProbeResult.Signal>()
            for (n in notices) {
                val title = n["title"]?.toString().orEmpty()
                val system = n["systemmc"]?.toString().orEmpty()
                val dm = n["dm"]?.toString().orEmpty()
                if (title.isEmpty()) continue
                if (KEYWORDS.none { title.contains(it) }) continue
                hits.add(
                    ProbeResult.Signal(
                        key = "$dm|$title",
                        title = title,
                        detail = "$system · ${n["publish_time"]}",
                    ),
                )
            }
            ProbeResult(hits = hits)
        } catch (e: Exception) {
            Log.w(TAG, "probe failed", e)
            ProbeResult(error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
