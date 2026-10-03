package com.xiqueer.android.agent

import com.xiqueer.android.XqRepository
import com.xiqueer.android.data.PeriodTimeSlot
import com.xiqueer.android.data.PeriodTimeSource
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.PeriodTimesStore
import com.xiqueer.android.notify.ClassReminder
import com.xiqueer.android.watch.WatchSettingsStore
import com.xiqueer.protocol.XqFeatures
import java.time.LocalDate

/**
 * Agent 能用的工具集,以及**执行门**。
 *
 * 安全模型(不靠提示词,靠代码):
 * - [execute] 只接受 [Risk.Local] / [Risk.Read],碰到写类工具直接拒绝;
 * - [plan] 处理 [Risk.Write] / [Risk.Irreversible],**只生成计划,一行网络代码都没有**;
 * - 真正执行写操作走 [commit],只能由 UI 的"确认"按钮触发。
 *
 * 所以"AI 不会自己提交选课"是**物理保证**,不是祈祷。
 */
class ToolRegistry(
    private val repo: XqRepository,
    private val periods: PeriodTimesStore,
    private val watch: WatchSettingsStore,
    private val shifts: com.xiqueer.android.data.ShiftStore,
    /**
     * 监听开关落到"真的起停前台服务"的钩子。
     * 工具层刻意不持 Context —— 只改配置,副作用交给持有 Context 的一方。
     */
    private val applyWatch: (Boolean) -> Unit = {},
    /**
     * 调休改动后的钩子:**必须重排提醒**。
     * 少了它就会出现"课挪走了,闹钟还在原来的日子响"。
     */
    private val onScheduleChanged: () -> Unit = {},
) {

    val tools: List<XqTool> = listOf(
        XqTool("get_today_courses", "查看今天有哪些课(含节次、地点、教师)", risk = Risk.Read),
        XqTool("get_week_timetable", "查看本周完整课表", risk = Risk.Read),
        XqTool(
            "get_grades", "查看成绩。可按学期筛选",
            listOf(ToolParam("term", "学年学期代码,如 20260;留空看全部", required = false)),
            Risk.Read,
        ),
        XqTool("get_exams", "查看考试安排(学期与轮次)", risk = Risk.Read),
        XqTool("list_notices", "查看教务通知列表", risk = Risk.Read),
        XqTool(
            "read_notice", "读某条通知的正文",
            listOf(ToolParam("dm", "通知的 dm(从 list_notices 拿)"), ToolParam("system", "通知的 system")),
            Risk.Read,
        ),
        XqTool("get_study_plan", "查看培养方案(按学期分组的课程)", risk = Risk.Read),
        XqTool("list_shifts", "查看已记录的调休/换课", risk = Risk.Read),
        XqTool(
            "shift_course",
            "把某一天的课挪到另一天(调休换课)。**只改本机数据,不上传学校**。" +
                "日期用 yyyy-MM-dd,例如 2026-09-28 挪到 2026-09-20。" +
                "不填课程名表示整天都挪;填了只挪那一门。",
            listOf(
                ToolParam("from_date", "原定上课日期 yyyy-MM-dd"),
                ToolParam("to_date", "实际改上的日期 yyyy-MM-dd"),
                ToolParam("course_name", "只挪这一门课;留空表示整天都挪", required = false),
            ),
            Risk.Local,
        ),
        XqTool(
            "cancel_shift",
            "撤销一条调休(用 list_shifts 拿到的 id)",
            listOf(ToolParam("id", "调休的 id")),
            Risk.Local,
        ),
        XqTool("get_period_times", "查看作息表配置状态与提醒/监听开关", risk = Risk.Read),
        XqTool(
            "search_open_courses",
            "查询当前开放的选课课程。注意:本校选课接口由教务内网提供,公网多半不可达;" +
                "不可达时会明说,不许臆造结果。",
            listOf(
                ToolParam("xn", "学年,如 2026", required = false),
                ToolParam("xq", "学期 0 或 1", required = false),
            ),
            Risk.Read,
        ),
        XqTool(
            "import_period_times",
            "写入作息表,写完课前精确提醒立刻生效。传入逗号分隔的每节时间," +
                "每项形如 HH:mm 或 HH:mm-HH:mm,第 1 项就是第 1 节。",
            listOf(ToolParam("times", "如 '08:00-08:45,08:55-09:40'")),
            Risk.Local,
        ),
        XqTool(
            "set_reminder", "开关上课提醒或调整提前量",
            listOf(
                ToolParam("enabled", "true/false", required = false),
                ToolParam("lead_minutes", "课前几分钟提醒", required = false),
            ),
            Risk.Local,
        ),
        XqTool("start_selection_watch", "开启选课监听(2–3 分钟随机一轮,0–8 点静默,最长 7 天)", risk = Risk.Local),
        XqTool("stop_selection_watch", "关闭选课监听", risk = Risk.Local),
        XqTool(
            "submit_course",
            "提交一门课的选课申请。**必须由用户确认** —— 调用后只会生成待确认计划,不会提交。",
            listOf(
                ToolParam("kcdm", "课程代码"),
                ToolParam("skbjdm", "上课班级代码"),
                ToolParam("kcmc", "课程名称(确认时展示用)"),
            ),
            Risk.Irreversible,
        ),
    )

    fun tool(name: String): XqTool? = tools.firstOrNull { it.name == name }

    /** 给模型看的工具清单(OpenAI function calling 用的数组)。 */
    fun schemaJson(exposeWrite: Boolean): String = tools
        .filter { exposeWrite || it.risk == Risk.Local || it.risk == Risk.Read }
        .joinToString(",", "[", "]") { it.toJsonSchema() }

    // ------------------------------------------------------------------ 执行门

    /** 执行只读/本地工具。写类工具在这里**被拒绝**,不可绕过。 */
    suspend fun execute(name: String, args: Map<String, String>): String {
        val t = tool(name) ?: return "错误:没有名为 $name 的工具"
        if (t.risk == Risk.Write || t.risk == Risk.Irreversible) {
            return "错误:$name 是写操作,必须先生成计划并由用户确认后才能执行。"
        }
        return runCatching { dispatch(name, args) }
            .getOrElse { "执行失败:${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 为写操作生成待确认计划。**这个函数不认识网络。** */
    fun plan(name: String, args: Map<String, String>): PendingAction? {
        val t = tool(name) ?: return null
        return when (name) {
            "submit_course" -> PendingAction(
                toolName = name,
                title = "提交选课",
                summary = "将向学校提交选课申请:${args["kcmc"].orEmpty()}" +
                    "(课程 ${args["kcdm"].orEmpty()}、上课班级 ${args["skbjdm"].orEmpty()})。" +
                    "能否选中取决于学校规则与余量,提交后不可撤回。",
                requestPreview = buildString {
                    append("action = oriHd_wsxk\n")
                    append("step   = submitXk_hd\n")
                    append("kcdm   = ${args["kcdm"].orEmpty()}\n")
                    append("skbjdm = ${args["skbjdm"].orEmpty()}\n")
                    append("xh     = <学号>\n")
                    append("(信封 param / param2 / xqerSign 由协议层现场生成)")
                },
                arguments = args,
                risk = t.risk,
            )
            else -> null
        }
    }

    /** 用户点了确认之后才真正执行写操作。 */
    suspend fun commit(action: PendingAction): String = when (action.toolName) {
        "submit_course" -> {
            val r = repo.featureText(
                "oriHd_wsxk",
                mapOf(
                    "step" to "submitXk_hd",
                    "kcdm" to action.arguments["kcdm"].orEmpty(),
                    "skbjdm" to action.arguments["skbjdm"].orEmpty(),
                ),
            )
            "已提交,服务器返回:${r.take(500)}"
        }
        else -> "错误:未知的写操作 ${action.toolName}"
    }

    // ------------------------------------------------------------------ 分发

    // 注意:这里必须是块体 —— 表达式体函数里不允许 `return`(分支内要提前返回)
    private suspend fun dispatch(name: String, args: Map<String, String>): String {
        return when (name) {

        "get_today_courses" -> {
            val t = repo.currentCachedTimetable()
                ?: return "还没有课表缓存,请先在课表页刷新一次。"
            // 作息表已配置就带上开始时间;没配就只有节次,不猜
            val list = ClassReminder.forDate(t, LocalDate.now(), periods.load())
            if (list.isEmpty()) "今天没有课。"
            else "今天 ${list.size} 节课:\n" + list.joinToString("\n") { "· " + it.scheduleLine() }
        }

        "get_week_timetable" -> {
            val t = repo.currentCachedTimetable()
                ?: return "还没有课表缓存,请先在课表页刷新一次。"
            val monday = ClassReminder.weekMonday(t)
            val times = periods.load()
            buildString {
                append("第 ${t.currentWeek}/${t.maxWeek} 周")
                if (monday != null) append("(${monday} 起)")
                append(",每天最多 ${t.maxPeriod} 节\n")
                for (d in 0..6) {
                    val date = monday?.plusDays(d.toLong())
                    val rows = if (date != null) ClassReminder.forDate(t, date, times) else emptyList()
                    if (rows.isEmpty()) continue
                    for (o in rows) append("· ${XqFeatures.weekdayName(d)} ${o.scheduleLine()}\n")
                }
            }.trimEnd()
        }

        "get_grades" -> {
            val rows = repo.loadGrades(args["term"]?.takeIf { it.isNotBlank() })
            if (rows.isEmpty()) "没有查到成绩记录(新生或尚未公布时属正常)。"
            else "查到 ${rows.size} 条记录:\n" + rows.joinToString("\n") { r ->
                val name = r.raw["kcmc"]?.toString()?.takeIf { it.isNotBlank() } ?: r.kcdm
                "· ${r.termName} ${name}"
            }
        }

        "get_exams" -> {
            val terms = repo.examTerms()
            if (terms.isEmpty()) "没有考试安排(2026 级新生当前属正常)。"
            else terms.joinToString("\n") { t ->
                val rounds = (t["kslc"] as? List<*>)
                    ?.mapNotNull { (it as? Map<*, *>)?.get("lcmc")?.toString() }
                    ?.joinToString("、").orEmpty()
                "· ${t["mc"]}(${t["dm"]})  轮次:${rounds.ifEmpty { "无" }}"
            }
        }

        "list_notices" -> {
            val list = repo.notices()
            if (list.isEmpty()) "没有通知。"
            else "共 ${list.size} 条,最近 10 条:\n" + list.take(10).joinToString("\n") {
                "· [${it["systemmc"]}] ${it["title"]} (${it["publish_time"]}) dm=${it["dm"]} system=${it["system"]}"
            }
        }

        "read_notice" -> {
            val d = repo.noticeDetail(args["dm"].orEmpty(), args["system"].orEmpty())
            val fj = (d["fj"] as? List<*>)?.size ?: 0
            "《${d["title"]}》(${d["publish_time"]} ${d["editor"]})\n${d["content"]}\n附件 $fj 个"
        }

        "get_study_plan" -> {
            val llkc = (repo.studyPlan()["llkc"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            if (llkc.isEmpty()) "没有培养方案数据。"
            else llkc.joinToString("\n") { term ->
                val kc = (term["kc"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
                val credits = kc.sumOf { (it["xf"]?.toString())?.toDoubleOrNull() ?: 0.0 }
                "· ${term["xq"]}:${kc.size} 门 / ${"%.1f".format(credits)} 学分 —— " +
                    kc.take(8).joinToString("、") {
                        XqFeatures.splitCourseName(it["kcmc"]?.toString().orEmpty()).first
                    }
            }
        }

        "get_period_times" -> {
            val t = periods.load()
            val w = watch.load()
            buildString {
                append("作息表:")
                if (t.configured) {
                    append(t.source.label).append(" —— ")
                    append(t.slots.take(12).joinToString(",") { it.toString() })
                } else {
                    append("未配置(因此只能按节次提醒,没有课前精确提醒)")
                }
                append("\n上课提醒:")
                append(if (periods.reminderEnabled) "开启" else "关闭")
                append(",提前 ").append(periods.leadMinutes).append(" 分钟")
                append("\n每日摘要:")
                append(if (periods.digestEnabled) "开启" else "关闭")
                append(" @ ").append(periods.digestAt)
                append("\n选课监听:")
                append(if (w.enabled) "开启中" else "关闭")
                append(";间隔 ").append(w.intervalRangeLabel)
                append(";静默 ").append(w.quietLabel)
            }
        }

        "import_period_times" -> {
            // 与手动填写、存储反序列化共用同一份解析(PeriodTimes.parseSlots)
            val slots = PeriodTimes.parseSlots(args["times"].orEmpty())
            if (slots.isEmpty()) {
                "没有解析出有效时间。每节要形如 08:00 或 08:00-08:45,用逗号分隔。"
            } else {
                periods.save(PeriodTimes(PeriodTimeSource.Imported, slots))
                "已写入 ${slots.size} 节的作息(source=AI 导入),课前精确提醒即刻生效。"
            }
        }

        "set_reminder" -> {
            args["enabled"]?.let { periods.reminderEnabled = it.equals("true", ignoreCase = true) }
            args["lead_minutes"]?.toIntOrNull()?.let { periods.leadMinutes = it }
            "提醒:${if (periods.reminderEnabled) "开启" else "关闭"},提前 ${periods.leadMinutes} 分钟。"
        }

        "start_selection_watch" -> {
            val c = watch.load().copy(enabled = true, startedAtMillis = System.currentTimeMillis())
            watch.save(c)
            applyWatch(true)
            "选课监听已开启:间隔 ${c.intervalRangeLabel},静默 ${c.quietLabel},最长 ${c.maxDays} 天。"
        }

        "stop_selection_watch" -> {
            watch.save(watch.load().copy(enabled = false, startedAtMillis = 0L))
            applyWatch(false)
            "选课监听已关闭。"
        }

        "list_shifts" -> {
            val all = shifts.all()
            if (all.isEmpty()) "目前没有任何调休。"
            else all.joinToString("\n") { "· [${it.id}] ${it.describe()}" }
        }

        "shift_course" -> {
            val from = args["from_date"].orEmpty().trim()
            val to = args["to_date"].orEmpty().trim()
            val course = args["course_name"]?.trim()?.takeIf { it.isNotEmpty() }
            val added = shifts.add(from, to, course?.let { listOf(it) })
            if (added == null) {
                "没添加:日期要形如 2026-09-28、起止不能是同一天,或者这条调休已经存在。" +
                    "请确认后再说一次。"
            } else {
                // 提醒必须跟着调休走 —— 这一步不能省
                onScheduleChanged()
                "已记录调休:${added.describe()}。课前提醒已按新日期重排。"
            }
        }

        "cancel_shift" -> {
            val id = args["id"].orEmpty().trim()
            if (shifts.remove(id)) {
                onScheduleChanged()
                "已撤销该调休,提醒已重排。"
            } else {
                "没找到 id 为 $id 的调休。可以用 list_shifts 看一下现有的。"
            }
        }

        "search_open_courses" -> {
            val r = repo.featureText(
                "oriHd_wsxk",
                mapOf(
                    "step" to "getKcxx_kc_hd",
                    "xn" to (args["xn"] ?: "2026"),
                    "xq" to (args["xq"] ?: "0"),
                    "page" to "1",
                    "pagenum" to "20",
                ),
            )
            if (r.contains("<html", ignoreCase = true) || r.contains("lnzyjw")) {
                "选课接口不可达 —— 学校把该请求转发到教务内网(/lnzyjw/)并返回 404。" +
                    "本校在公网下无法直接查选课,请如实告诉用户,不要编造课程列表。"
            } else {
                "服务器返回:${r.take(1200)}"
            }
        }

        else -> "错误:工具 $name 未实现"
        }
    }
}
