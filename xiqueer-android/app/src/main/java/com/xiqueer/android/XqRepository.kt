package com.xiqueer.android

import android.content.Context
import com.xiqueer.android.data.CacheStore
import com.xiqueer.android.data.OkHttpTransport
import com.xiqueer.android.data.SessionStore
import com.xiqueer.android.repository.TimetableCache
import com.xiqueer.protocol.GradeRow
import com.xiqueer.protocol.Timetable
import com.xiqueer.protocol.XqClient
import com.xiqueer.protocol.XqFeatures
import com.xiqueer.protocol.XqJson
import com.xiqueer.protocol.XqLoginResult
import com.xiqueer.protocol.XqUser

/**
 * 协议层与界面之间的唯一入口。
 *
 * 联网方法都是 `suspend`;取消会一路传到 OkHttp(`Call.cancel()`)。
 * 纯缓存/纯计算的方法保持普通函数(见 [cachedTimetable])。
 *
 * 缓存策略:课表按学期缓存原始 JSON,首帧直接读缓存,刷新在后台。
 */
class XqRepository(context: Context) {

    // 会话寿命埋点(见 SessionAgeStore 的说明:为了量出有效期到底多长)
    private val appContext = context.applicationContext
    private val sessionAge = com.xiqueer.android.data.SessionAgeStore(appContext)

    val session = SessionStore(context)
    private val cache = CacheStore(context)
    private val client = XqClient(transport = OkHttpTransport(), initialUser = session.user)
        .also { c ->
            c.onAuthResult = { ok, sessionExpired ->
                if (ok) {
                    sessionAge.onRequestOk()
                } else if (sessionExpired) {
                    // 只在**第一次**被拒时记账
                    if (sessionAge.onSessionRejected()) {
                        // 而且只在**后台**弹通知:前台有顶部提示条,再弹一条是噪音。
                        // 后台弹这条的意义是——用户不在看屏幕,这是他唯一的知情渠道。
                        if (!com.xiqueer.android.notify.AppVisibility.foreground) {
                            com.xiqueer.android.notify.Notifications.ensureChannels(appContext)
                            com.xiqueer.android.notify.Notifications.sessionExpired(
                                appContext,
                                sessionAge.describe(),
                            )
                        }
                    }
                }
                // 网络抖动(ok=false 且非会话问题)刻意**不记** ——
                // 把"没连上网"误记成"会话失效"会让埋点给出错误结论。
            }
        }

    val hasSession: Boolean get() = session.hasSession

    /** 用已保存的会话恢复,不联网。 */
    fun restore() {
        client.setUser(session.user)
    }

    suspend fun login(username: String, password: String, xxdm: String): XqLoginResult {
        val r = client.login(username, password, xxdm)
        if (r.ok) {
            session.saveUser(client.user)
            // 只留学号做登录页预填;**密码不落盘** —— 全项目没有任何读取点,
            // 存下来只会多一处明文凭据(历史版本存过,已清理)
            session.username = username
            session.xxdm = xxdm
            // 会话寿命从这个时刻起算
            sessionAge.onLoginSuccess()
            com.xiqueer.android.notify.Notifications.cancelSessionExpired(appContext)
        }
        return r
    }

    /**
     * 登出。**只清本地状态**,不发网请求 ——
     * 在途请求由 ViewModel 取消(见 [AppViewModel.logout])。
     */
    fun logout() {
        session.clearSession()
        cache.clear()
        client.setUser(XqUser())
        // 会话没了,那条"登录已过期"的通知也就不该还挂着
        com.xiqueer.android.notify.Notifications.cancelSessionExpired(appContext)
    }

    suspend fun terms(): List<Map<String, Any?>> = XqFeatures.terms(client)

    suspend fun currentTerm(): String? = XqFeatures.currentTerm(client)

    /** 只读缓存,**永不联网** —— 首帧用这个。纯本地,不需要协程。 */
    fun cachedTimetable(term: String): Timetable? {
        val text = cache.read(cacheKey(term)) ?: return null
        return runCatching { XqFeatures.parseTimetable(XqJson.parseObject(text), term) }.getOrNull()
    }

    fun cachedAt(term: String): Long = cache.savedAt(cacheKey(term))

    /**
     * 联网刷新课表。
     *
     * [week] 为空 = 取服务端认为的当前周,并写"当前周"缓存(提醒与 AI 工具读的就是它);
     * [week] 非空 = 翻页取某一周,**只写翻页缓存** ——
     * 绝不能覆盖 [TimetableCache.KEY_CURRENT],否则用户翻到第 10 周,
     * 课前提醒就会按第 10 周去算"今天有什么课"。
     */
    suspend fun refreshTimetable(term: String?, week: Int? = null): Timetable {
        val dm = term ?: currentTerm().orEmpty()
        val params = buildMap<String, String?> {
            put("step", "kbdetail_bz")
            put("xnxq", dm)
            if (week != null) put("week", week.toString())
        }
        val text = client.callText("getKb", params)
        // ⚠️ 顺序很重要:**先解析、后写缓存**。
        // 原来先写再解析,于是一次失败(或返回错误载荷)的刷新会把那段文本
        // 当成课表覆盖掉好缓存 —— 之后就变成"离线冷启动也没数据"。
        // 只有确认能解析成课表,才配写进缓存。
        val parsed = XqFeatures.parseTimetable(XqJson.parseObject(text), dm)
        if (week == null) {
            cache.write(cacheKey(dm), text)
            cache.write(TimetableCache.KEY_CURRENT, text)
            // 缓存里只有课表 JSON,没有"这是哪个学期" —— 单独记一份,
            // 否则离线冷启动时标题栏会缺学期(首帧用的是缓存)
            cache.write(TimetableCache.KEY_CURRENT_TERM, dm)
        } else {
            cache.write(weekCacheKey(dm, week), text)
        }
        return parsed
    }

    /** 翻页缓存读取:纯本地,来回翻页不重复打接口。 */
    fun cachedWeek(term: String, week: Int): Timetable? {
        val text = cache.read(weekCacheKey(term, week)) ?: return null
        return runCatching { XqFeatures.parseTimetable(XqJson.parseObject(text), term) }.getOrNull()
    }

    suspend fun loadGrades(term: String? = null): List<GradeRow> = XqFeatures.grades(client, term)

    /**
     * 原始接口调用 —— 只给 AI 工具层这种"需要探一个没有封装的动作"的场景用。
     * 返回**原始响应文本**,让调用方自己判断,不在这一层假装懂业务。
     */
    suspend fun featureText(action: String, params: Map<String, String?>): String =
        client.callText(action, params)

    /** 学校全量名单(匿名,登录页就要用)。 */
    suspend fun schools(): List<Map<String, Any?>> = XqFeatures.schools(client)

    /** 当前学期课表缓存(与后台闹钟/Worker 读的是同一份)。纯本地。 */
    fun currentCachedTimetable(): Timetable? {
        val text = cache.read(TimetableCache.KEY_CURRENT) ?: return null
        return runCatching { XqFeatures.parseTimetable(XqJson.parseObject(text), "") }.getOrNull()
    }

    /** 缓存对应的学期代码;没有缓存时为 null。首帧标题栏要用。 */
    fun currentCachedTerm(): String? = cache.read(TimetableCache.KEY_CURRENT_TERM)?.ifBlank { null }

    // ---- P5 ----

    suspend fun notices(): List<Map<String, Any?>> = XqFeatures.notices(client)

    suspend fun noticeDetail(dm: String, system: String): Map<String, Any?> =
        XqFeatures.noticeDetail(client, dm, system)

    suspend fun examTerms(): List<Map<String, Any?>> = XqFeatures.examTerms(client)

    suspend fun examItems(xnxq: String, lcdm: String): List<Map<String, Any?>> =
        XqFeatures.examItems(client, xnxq, lcdm)

    suspend fun studyPlan(): Map<String, Any?> = XqFeatures.studyPlan(client)

    private fun cacheKey(term: String) = "timetable_${term.ifEmpty { "current" }}"

    /** 翻页缓存键。与 [cacheKey] 分开,避免翻页把"当前周"那份覆盖掉。 */
    private fun weekCacheKey(term: String, week: Int) =
        "timetable_${term.ifEmpty { "current" }}_w$week"
}
