package com.xiqueer.protocol

import java.time.LocalDate

/** 一节课。字段名沿用接口原始 key 的语义。 */
data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val roomRaw: String,
    val periods: String,   // "1-2"
    val weeks: String,     // "3-10周"
    val credit: String,
    val code: String,      // kcdm
    val classId: String,   // skbj
    val note: String,
)

/**
 * 一周课表。
 *
 * ⚠️ 接口返回的 `week1`..`week7` 是**星期**(周一..周日),不是周次 —— 实测确认:
 * `qssj`/`jssj` 只差 6 天,而 `maxzc` 是 18。周次全在标量里。
 * 所以 `days[0]` = 周一 .. `days[6]` = 周日。
 *
 * 刻意**不保留原始 Map**(P3c)—— 解析后的模型和原始树同时驻留是一份数据两份内存。
 */
data class Timetable(
    val term: String,
    val currentWeek: Int,
    val maxWeek: Int,
    val maxPeriod: Int,
    val weekStart: String?,
    val weekEnd: String?,
    /** 第 1 周周一 = `weekStart - (currentWeek-1)*7`。 */
    val termStartMonday: String?,
    val days: List<List<Course>>,
)

data class GradeRow(
    val term: String,
    val termName: String,
    val kcdm: String,
    val skbjdm: String,
    val raw: Map<String, Any?>,
)

/**
 * 领域封装 —— 与 同一协议的 JS 参考实现 对齐。
 * 只做纯解析/组装,网络全走 [XqClient](联网方法均为 `suspend`)。
 */
object XqFeatures {

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayName(index0: Int): String = WEEKDAYS.getOrElse(index0) { "?" }

    // ---- 学期 / 课表 ----

    /** 学年学期列表(getKb/xnxq)。 */
    suspend fun terms(client: XqClient): List<Map<String, Any?>> =
        client.call("getKb", mapOf("step" to "xnxq")).list("xnxq").objects()

    /** 当前学期(校历里 dqxq == "1" 的那个)。 */
    suspend fun currentTerm(client: XqClient): String? {
        val hit = client.call("getXtgn", mapOf("step" to "xnxq"))
            .list("xnxq").objects().firstOrNull { it.str("dqxq") == "1" }
        return hit?.str("dm") ?: terms(client).firstOrNull()?.str("dm")
    }

    /**
     * 课表(`getKb/kbdetail_bz`)。
     *
     * [week] 传第几周就返回**那一周**的课表(服务端按 `skzs` 过滤:
     * 实测 `week=1` 返回 0 门课,因为所有课的 `skzs` 都从第 3 周起)。
     * 不传则以服务端认为的当前周为准 —— 响应里的 `zc` 就是"本次请求的周"。
     *
     * ⚠️ 传了 `week` 之后 `zc` 不再等于"真实本周"。
     * 需要真实本周的地方(课前提醒、今日课程)**不要用翻页结果**,
     * 要单独取一次不带 `week` 的响应(见 App 侧的 `TimetableCache.KEY_CURRENT`)。
     */
    suspend fun timetable(client: XqClient, term: String? = null, week: Int? = null): Timetable {
        val xnxq = term ?: terms(client).firstOrNull()?.str("dm")
        val params = buildMap<String, String?> {
            if (xnxq != null) put("xnxq", xnxq)
            if (week != null) put("week", week.toString())
        }
        return parseTimetable(client.call("getKb", params + mapOf("step" to "kbdetail_bz")), xnxq.orEmpty())
    }

    /**
     * 从 `getKb/kbdetail_bz` 的原始响应解析课表。
     * 单独暴露出来,是为了让调用方能缓存**原始 JSON 文本**再离线重解析。
     */
    fun parseTimetable(r: Map<String, Any?>, term: String): Timetable {
        r.str("errcode")?.let { throw XqApiException("getKb failed: $it ${r.str("message") ?: ""}", it, r) }

        val days = (1..7).map { d -> r.list("week$d").objects().map(::courseOf) }
        val currentWeek = r.str("zc")?.trim()?.toIntOrNull() ?: 0
        val weekStart = r.str("qssj")?.takeIf { it.isNotBlank() }
        val weekEnd = r.str("jssj")?.takeIf { it.isNotBlank() }
        return Timetable(
            term = term,
            currentWeek = currentWeek,
            maxWeek = r.str("maxzc")?.toIntOrNull() ?: 0,
            maxPeriod = r.str("maxjc")?.toIntOrNull() ?: 0,
            weekStart = weekStart,
            weekEnd = weekEnd,
            termStartMonday = weekStart?.let { minusDays(it, maxOf(0, currentWeek - 1) * 7L) },
            days = days,
        )
    }

    fun courseOf(e: Map<String, Any?>): Course = Course(
        name = e.str("kcmc").orEmpty(),
        teacher = e.str("rkjs").orEmpty(),
        room = cleanRoom(e.str("skdd").orEmpty()),
        roomRaw = e.str("skdd").orEmpty(),
        periods = e.str("jcxx").orEmpty(),
        weeks = e.str("skzs").orEmpty(),
        credit = e.str("xf").orEmpty(),
        code = e.str("kcdm").orEmpty(),
        classId = e.str("skbj").orEmpty(),
        note = e.str("bz").orEmpty(),
    )

    /** `厚德楼[厚德楼-H502]` -> `厚德楼-H502`;`操场[（本溪校区）操场]` -> `（本溪校区）操场`。 */
    fun cleanRoom(raw: String): String {
        val i = raw.indexOf('[')
        if (i < 0) return raw
        val inner = raw.substring(i + 1).removeSuffix("]")
        return inner.ifEmpty { raw }
    }

    // ---- 成绩 ----

    /** 有成绩的学期(getStucj/xnxq)。 */
    suspend fun gradeTerms(client: XqClient): List<Map<String, Any?>> =
        client.call("getStucj", mapOf("step" to "xnxq", "xxdm" to client.user.xxdm)).list("xnxq").objects()

    /** 成绩:三步流程,与 XscjfbActivity 一致。 */
    suspend fun grades(client: XqClient, term: String? = null): List<GradeRow> {
        val all = gradeTerms(client)
        val want = if (term != null) all.filter { it.str("dm") == term } else all
        val gh = client.user.userid.substringAfter('_', client.user.userid)
        val out = ArrayList<GradeRow>()
        for (t in want) {
            val dm = t.str("dm").orEmpty()
            val cls = client.call(
                "oriXscjfb",
                mapOf("step" to "getSkbj_xscjfb", "xxdm" to client.user.xxdm, "xnxq" to dm, "gh" to gh),
            )
            for (c in cls.list("resultset").objects()) {
                val kcdm = c.str("kcdm").orEmpty()
                val skbjdm = c.str("skbjdm").orEmpty()
                val r = client.call(
                    "oriXscjfb",
                    mapOf("step" to "getYscjfb_xscjfb", "xnxq" to dm, "gh" to gh, "kcdm" to kcdm, "skbjdm" to skbjdm),
                )
                out.add(GradeRow(dm, t.str("mc").orEmpty(), kcdm, skbjdm, r))
            }
            if (term != null) break
        }
        return out
    }

    // ---- 考试 ----

    /** 考试安排(oriKsap/list)。 */
    suspend fun exams(client: XqClient): List<Map<String, Any?>> =
        client.call("oriKsap", mapOf("step" to "list")).list("xnxq").objects()

    /**
     * `oriKsap/list` -> 有考试的学期与轮次:`[{dm, mc, kslc:[{lcdm, lcmc}]}]`。
     */
    suspend fun examTerms(client: XqClient): List<Map<String, Any?>> = exams(client)

    /**
     * `oriKsap/detail` -> 排考明细。字段取自 `KsapItemNewBean`:
     * `kcmc` 课程、`ksdd` 地点、`kssj` 时间、`kssjqs`/`kssjjs` 起止、
     * `zwh` 座位号、`ksxz` 性质、`lc` 轮次、`xf`/`khfs`。
     *
     * 2026 级新生当前无排考,`ksap` 是空数组 —— 渲染必须吃下空数据。
     */
    suspend fun examItems(client: XqClient, xnxq: String, lcdm: String): List<Map<String, Any?>> =
        client.call("oriKsap", mapOf("step" to "detail", "xnxq" to xnxq, "lcdm" to lcdm))
            .list("ksap").objects()

    // ---- 通知 ----

    /** 通知列表(getXx/list)。 */
    suspend fun notices(client: XqClient): List<Map<String, Any?>> =
        client.call("getXx", mapOf("step" to "list")).list("resultSet").objects()

    /** 通知详情(getXx/detail,需要列表项里的 `dm` 与 `system`)。 */
    suspend fun noticeDetail(client: XqClient, dm: String, system: String): Map<String, Any?> =
        client.call("getXx", mapOf("step" to "detail", "dm" to dm, "system" to system))

    // ---- 培养方案 ----

    /**
     * `oriHd_tsjyk/getPyfa` -> `{resultSet:{llkc:[{xq, kc:[...]}], sjhj:[...]}}`。
     * `llkc` = 理论课程(按学期分组),`sjhj` = 实践环节。
     */
    suspend fun studyPlan(client: XqClient): Map<String, Any?> =
        (client.call("oriHd_tsjyk", mapOf("step" to "getPyfa"))["resultSet"] as? Map<String, Any?>)
            ?: emptyMap()

    // ---- 学校目录 ----

    /**
     * 学校全量名单(`getAgent`,**匿名**可调,登录页就要用)。
     *
     * ⚠️ 两个反直觉点(都实测过):
     * 1. `xxmc` 参数**不起过滤作用** —— 传任何关键词都返回同一份从「安徽滁州技师学院」
     *    开头的完整列表。也就是说"按名字搜"是**客户端**的事;
     * 2. 响应是**裸 JSON 数组**(不是 `{resultSet:[]}`),而 `XqClient.callAnon` 的
     *    `parseObject` 只认对象 —— 数组会被塞进 `raw` 键。所以这里要把 `raw` 再解析一次。
     *
     * 每条字段:`xxdm` / `xxmc` / `pinyin` / `gkksh` / `mode` / `rzfs` / `serviceUrl`。
     */
    suspend fun schools(client: XqClient): List<Map<String, Any?>> {
        val r = client.callAnon(mapOf("action" to "getAgent", "appver" to XqRsa.APPVER))
        val raw = r["raw"] as? String ?: return emptyList()
        return runCatching { XqJson.parseArray(raw).objects() }.getOrDefault(emptyList())
    }

    /** 课程名带 `[课程号]` 前缀,拆开成 (名称, 课程号)。 */    fun splitCourseName(raw: String): Pair<String, String> {
        val m = Regex("^\\[([^\\]]+)]\\s*(.*)$").find(raw.trim())
        return if (m != null) m.groupValues[2] to m.groupValues[1] else raw.trim() to ""
    }

    // ---- helpers ----

    private fun minusDays(ymd: String, days: Long): String =
        LocalDate.parse(ymd).minusDays(days).toString()
}

/** `List<Any?>` -> `List<Map<String,Any?>>`,丢弃非对象元素。 */
@Suppress("UNCHECKED_CAST")
fun List<Any?>.objects(): List<Map<String, Any?>> = mapNotNull { it as? Map<String, Any?> }
