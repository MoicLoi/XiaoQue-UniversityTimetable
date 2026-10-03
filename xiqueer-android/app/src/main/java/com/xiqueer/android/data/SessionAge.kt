package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 会话寿命的埋点。
 *
 * 存在的理由:**会话有效期到底多长,我们不知道**,而且这一个数决定了两件相反的事。
 * 唯一的数据是"约 7-8 天后被拒",这既可能是
 *
 * - **绝对有效期**:登录后固定 TTL,期间请求多少次都不延长;
 * - **滑动有效期**:每次成功请求都往后推,停手 TTL 之后才失效。
 *
 * 两者的应对完全相反 —— 前者要"到期前提前重登",后者只要"别让后台请求断掉"
 * (国产 ROM 杀掉 WorkManager 就会断)。所以在量出来之前,不做任何按天数的预设动作。
 *
 * 区分方法就是同时记三个时刻:登录、上次成功、第一次被拒。
 *
 * | 观测 | 结论 |
 * |---|---|
 * | 被拒 ≈ 登录 + TTL,且距上次成功很远 | 绝对有效期 |
 * | 被拒 ≈ 上次成功 + TTL | 滑动有效期 |
 *
 * 存在的形式刻意做得很轻:一个 SharedPreferences,几个 Long。
 * 它不参与任何业务判断 —— 只在日志里说话,免得"埋点"变成"悄悄改行为"。
 */
class SessionAgeStore(context: Context) {

    private val prefs = context.getSharedPreferences("session_age", Context.MODE_PRIVATE)

    /** [onRequestOk] 的节流基准。只活在内存里 —— 进程重启后多写一次无所谓。 */
    @Volatile
    private var lastOkWriteAt = 0L

    /** 最近一次登录成功的时刻。 */
    var lastAuthAt: Long
        get() = prefs.getLong(KEY_AUTH, 0L)
        set(v) = prefs.edit().putLong(KEY_AUTH, v).apply()

    /** 最近一次**认证态请求成功**的时刻 —— 区分绝对/滑动就靠它。 */
    var lastOkAt: Long
        get() = prefs.getLong(KEY_OK, 0L)
        set(v) = prefs.edit().putLong(KEY_OK, v).apply()

    /** 第一次被拒的时刻(重登成功后清掉)。 */
    var firstRejectAt: Long
        get() = prefs.getLong(KEY_REJECT, 0L)
        set(v) = prefs.edit().putLong(KEY_REJECT, v).apply()

    /** 已经就"会话失效"发过通知了吗 —— 避免每 6 小时重复打扰。 */
    var notifiedExpired: Boolean
        get() = prefs.getBoolean(KEY_NOTIFIED, false)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFIED, v).apply()

    fun onLoginSuccess(now: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_AUTH, now)
            .putLong(KEY_OK, now)
            .remove(KEY_REJECT)
            .putBoolean(KEY_NOTIFIED, false)
            .apply()
        Log.i(TAG, "会话起点:登录成功于 ${stamp(now)}")
    }

    fun onRequestOk(now: Long = System.currentTimeMillis()) {
        // 节流:成绩那种"一个学期一门课一次请求"的循环会连着打几十个请求,
        // 每个都写一次盘毫无必要 —— 这个值只要精确到分钟就够区分绝对/滑动了。
        if (now - lastOkWriteAt < OK_THROTTLE_MS) return
        lastOkWriteAt = now
        prefs.edit().putLong(KEY_OK, now).apply()
    }

    /**
     * 服务端明确拒绝了这个会话 —— 记下**第一次**被拒的时刻并打一行可读的账。
     *
     * 这行日志是这次埋点的全部产出:它同时给出"距登录多久"和"距上次成功多久",
     * 拿两三个样本就能判定是绝对还是滑动。
     */
    fun onSessionRejected(now: Long = System.currentTimeMillis()): Boolean {
        val first = firstRejectAt
        if (first != 0L) return false // 只记第一次,后面重复的没有信息量

        prefs.edit().putLong(KEY_REJECT, now).apply()
        val auth = lastAuthAt
        val ok = lastOkAt
        Log.w(
            TAG,
            buildString {
                append("会话被拒:${stamp(now)}")
                append(" | 距登录 ").append(days(now, auth))
                append(" | 距上次成功 ").append(days(now, ok))
                append(" | 登录于 ").append(stamp(auth))
                append(" | 上次成功 ").append(stamp(ok))
                append("  → 若两者接近=滑动有效期;若'距登录'≈固定值而'距上次成功'很长=绝对有效期")
            },
        )
        return true
    }

    /** 供界面/日志展示的一句人话。 */
    fun describe(): String {
        val auth = lastAuthAt
        if (auth == 0L) return "无会话起点记录"
        return "登录于 ${stamp(auth)},上次成功 ${stamp(lastOkAt)}" +
            if (firstRejectAt != 0L) ",首次被拒 ${stamp(firstRejectAt)}" else ""
    }

    private fun days(now: Long, then: Long): String {
        if (then <= 0L) return "无记录"
        val d = (now - then).toDouble() / TimeUnit.DAYS.toMillis(1)
        return "%.2f 天".format(d)
    }

    private fun stamp(t: Long): String =
        if (t <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(t))

    private companion object {
        const val TAG = "XqSession"
        const val KEY_AUTH = "lastAuthAt"
        const val KEY_OK = "lastOkAt"
        const val KEY_REJECT = "firstRejectAt"
        const val KEY_NOTIFIED = "notifiedExpired"

        /** 写"上次成功"的最小间隔。1 分钟足够区分绝对/滑动有效期。 */
        const val OK_THROTTLE_MS = 60_000L
    }
}
