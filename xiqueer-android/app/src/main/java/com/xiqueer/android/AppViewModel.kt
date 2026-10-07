package com.xiqueer.android

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.xiqueer.android.agent.AgentConfig
import com.xiqueer.android.agent.AgentEvent
import com.xiqueer.android.agent.AgentRunner
import com.xiqueer.android.agent.AgentSettingsStore
import com.xiqueer.android.agent.PendingAction
import com.xiqueer.android.agent.ToolRegistry
import com.xiqueer.android.data.PeriodTimeSource
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.PeriodTimesStore
import com.xiqueer.android.notify.ReminderScheduler
import com.xiqueer.android.watch.SelectionWatch
import com.xiqueer.android.watch.WatchSettingsStore
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.GradeRow
import com.xiqueer.protocol.Timetable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面。
 *
 * ⚠️ [Notices] **不在底栏** —— 它挪进了顶栏「更多」菜单。
 * 但仍留在这个枚举里(页面分发逻辑按它走),底栏渲染用 [BOTTOM_NAV] 而不是 `entries`,
 * 否则 `Tab.entries.forEach` 会把它又画回底栏。
 */
enum class Tab(val label: String) {
    Schedule("日程"),
    Timetable("课表"),
    Grades("成绩"),
    Exams("考试"),
    Plan("方案"),
    Notices("通知"),
    ;

    companion object {
        /** 底栏的五个:日程 / 课表 / 成绩 / 考试 / 方案。 */
        val BOTTOM_NAV: List<Tab> = listOf(Schedule, Timetable, Grades, Exams, Plan)
    }
}

data class UiState(
    val booting: Boolean = true,
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /**
     * 这次失败是不是"服务端不认这个会话"(实测回的是 `口令失败`)。
     * 界面据此把提示从"请求失败"升级成"重新登录一下"。
     */
    val sessionExpired: Boolean = false,
    val username: String = "",
    val xxdm: String = "",
    /** 学校名(用于登录页展示);为空时说明用户还没选过学校。 */
    val schoolName: String = "",
    val schoolPickerOpen: Boolean = false,
    val schools: List<com.xiqueer.android.data.School> = emptyList(),
    val schoolsLoading: Boolean = false,
    val schoolsError: String? = null,
    /** 每门课要带的东西(课程名 → 文本)。用户在设置里手写。 */
    val courseItems: Map<String, String> = emptyMap(),
    /** 顶栏「更多」菜单是否展开。 */
    val moreMenuOpen: Boolean = false,
    /** 「上课携带」编辑页是否展开。 */
    val itemsSheetOpen: Boolean = false,
    /**
     * **本地覆盖层**:调休 + 课节覆写 + 晚自习。
     *
     * 打成一个值而不是三个独立字段,是因为它们的消费方是同一个
     * [com.xiqueer.android.notify.ScheduleOverrides] —— 分成三个字段后,
     * 每加一层就要在 8+ 个传参处逐个补,漏一处的症状是"某一处显示的还是老课表"。
     */
    val overlays: com.xiqueer.android.data.Overlays = com.xiqueer.android.data.Overlays.Empty,
    /** 调休管理面板是否展开。 */
    val shiftSheetOpen: Boolean = false,
    /** 「课节改动」编辑面板是否展开(改某一节的教室 / 节次)。 */
    val courseEditOpen: Boolean = false,
    /** 晚自习设置面板是否展开。 */
    val selfStudySheetOpen: Boolean = false,
    /** 上课悬浮窗开关(默认关,且要系统授权)。 */
    val overlayEnabled: Boolean = false,
    /** 系统是否已经给了「显示在其他应用上层」权限。 */
    val overlayAllowed: Boolean = false,
    val displayName: String = "",
    val tab: Tab = Tab.Schedule,

    // 课表
    val term: String = "",
    val termName: String = "",
    val timetable: Timetable? = null,
    /**
     * **真实本周**。翻页时不变 —— 课前提醒与「今天有什么课」用它,
     * 而 [timetable] 的 `currentWeek` 是"正在看的那一周"。
     */
    val currentWeek: Int = 0,
    /**
     * **下一周**的课表,只给日程页的"明天"用。
     *
     * 课表接口一次只回一周,而日程页要同时看今天和明天 —— 明天可能已经跨周。
     * 没有这一份时,`forDate` 会因为日期不在缓存那一周内而返回空,
     * 界面就误报"明天没有日程"(真机上表现为:得先去课表页翻一下才正常)。
     * 只有明天真的跨周时才会去取它,平时是 null。
     */
    val nextWeekTimetable: Timetable? = null,
    /** 展开的课程详情(存的是一把 key,真正对象从 [timetable] 里取)。 */
    val selectedCourseKey: String? = null,

    // 成绩
    val grades: List<GradeRow> = emptyList(),

    // 通知
    val notices: List<Map<String, Any?>> = emptyList(),
    val noticesLoaded: Boolean = false,
    val noticeDetail: Map<String, Any?>? = null,
    val noticeDetailOpen: Boolean = false,

    // 考试
    val examTerms: List<Map<String, Any?>> = emptyList(),
    val examsLoaded: Boolean = false,
    val examTerm: String? = null,
    val examRound: String? = null,
    val examItems: List<Map<String, Any?>> = emptyList(),

    // 培养方案
    val plan: Map<String, Any?> = emptyMap(),
    val planLoaded: Boolean = false,

    // 作息表(P4)。默认未配置 —— 此时只按节次提醒,通知不带时钟。
    val periodTimes: PeriodTimes = PeriodTimes.Empty,
    val periodNoticeVisible: Boolean = false,

    // AI 助手(P6)
    val agent: AgentUiState = AgentUiState(),
    val agentConfig: AgentConfig = AgentConfig(),
    val settingsOpen: Boolean = false,
    val watch: com.xiqueer.android.watch.WatchConfig = com.xiqueer.android.watch.WatchConfig(),
    val watchPollCount: Int = 0,

    // 提醒设置快照 —— 设置页要能显示当前值,不能每次都去读 SharedPreferences
    val leadMinutes: Int = 20,
    val reminderEnabled: Boolean = true,
    val digestEnabled: Boolean = true,
    val digestAt: String = "07:00",
) {
    val currentTermLabel: String
        get() = termName.ifEmpty { term }
}

/** 对话里的一轮。role: `user` / `ai` / `note` / `error`。 */
data class ChatTurn(val role: String, val text: String)

/**
 * 助手面板状态。
 *
 * [pending] 是**唯一**能让写操作真正发出的入口 —— 它只能由用户点确认来消费。
 */
data class AgentUiState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val turns: List<ChatTurn> = emptyList(),
    val pending: PendingAction? = null,
    val status: String = "",
)

class AppViewModel(
    app: Application,
    /**
     * 进程被系统回收后重建时用来自动恢复状态(P3a)。
     *
     * 只放**小、可序列化、重建后仍然有意义**的东西:当前 tab、展开的课程详情。
     * 课表/成绩这些**不往里放** —— 它们有缓存与网络,恢复时重新读一遍更可靠,
     * 塞进 Bundle 只会拖慢重建并可能撑爆 TransactionTooLarge。
     */
    private val saved: SavedStateHandle,
) : AndroidViewModel(app) {

    private val repo = XqRepository(app)
    private val periodStore = PeriodTimesStore(app)
    private val watchStore = WatchSettingsStore(app)
    private val agentStore = AgentSettingsStore(app)
    private val schoolDir = com.xiqueer.android.data.SchoolDirectory(app)
    private val itemsStore = com.xiqueer.android.data.CourseItemsStore(app)
    private val shiftStore = com.xiqueer.android.data.ShiftStore(app)
    private val courseOverrideStore = com.xiqueer.android.data.CourseOverrideStore(app)
    private val selfStudyStore = com.xiqueer.android.data.SelfStudyStore(app)
    private val overlayStore = com.xiqueer.android.data.OverlaySettingsStore(app)

    /** 从三个 Store 读齐覆盖层。**只在这里拼**,别处不许各自拼一份。 */
    private fun readOverlays(): com.xiqueer.android.data.Overlays =
        com.xiqueer.android.data.Overlays(
            shifts = shiftStore.all(),
            courseOverrides = courseOverrideStore.all(),
            selfStudies = selfStudyStore.all(),
        )

    /** 工具的**唯一**实例 —— 执行门就挂在这里,别在别处再造一个。 */
    private val tools = ToolRegistry(
        repo = repo,
        periods = periodStore,
        watch = watchStore,
        shifts = shiftStore,
        // 读课表的工具必须看到和界面同一份覆盖层,否则 AI 会说"课在 H502"
        // 而屏幕上写的是 H303
        overlays = { readOverlays() },
        applyWatch = { on ->
            val app = getApplication<Application>()
            if (on) SelectionWatch.enable(app) else SelectionWatch.disable(app)
        },
        // 调休改了就必须重排提醒(工具层没有 Context,副作用在这里落地)
        onScheduleChanged = {
            state = state.copy(overlays = readOverlays())
            rescheduleReminders()
        },
    )

    private val runner = AgentRunner(
        tools = tools,
        configProvider = { agentStore.load() },
        // Key 从 Keystore 加密存储取;只在真要发请求时解密一次
        apiKeyProvider = { agentStore.apiKey() },
    )

    /**
     * 每条"频道"只保留最新一个请求。
     *
     * 否则连点刷新会叠加请求,旧响应可能后到并覆盖新数据 —— 而且登出时无法一次性取消。
     * 取消会一路传到 OkHttp 的 `Call.cancel()`(见 `OkHttpTransport`)。
     */
    private val jobs = HashMap<String, Job>()

    var state by mutableStateOf(UiState())
        private set

    /**
     * 把**本地存储**(不是服务器数据)读进 state。
     *
     * 登出 / 重新登录时**必须**再调一次,否则这些全部退回默认值:
     * AI 配置、上课携带、悬浮窗开关、作息时间、提醒设置、调休列表。
     *
     * 真机上就是这么暴露的:重新登录后点 AI 说"还没配置",去设置里转一圈又好了 ——
     * 因为设置页会自己重新读一遍存储。**能自愈的 bug 最容易被当成瑕疵放过去**,
     * 但它其实是"state 与存储脱节",而同一个脱节在别的入口可能不会自愈。
     *
     * 抽成一个函数而不是在 init 里抄一遍:两份实现必然有一处会漏字段
     * (这正是它当初漏掉的原因)。
     */
    private fun withLocalState(s: UiState): UiState = s.copy(
        courseItems = itemsStore.all(),
        overlays = readOverlays(),
        overlayEnabled = overlayStore.enabled,
        periodTimes = periodStore.load(),
        agentConfig = agentStore.load(),
        watch = watchStore.load(),
        watchPollCount = watchStore.pollCount,
        leadMinutes = periodStore.leadMinutes,
        reminderEnabled = periodStore.reminderEnabled,
        digestEnabled = periodStore.digestEnabled,
        digestAt = periodStore.digestAt,
    )

    init {
        repo.restore()
        val loggedIn = repo.hasSession
        val times = periodStore.load()
        state = withLocalState(
            state.copy(
                booting = false,
                loggedIn = loggedIn,
                username = repo.session.username,
                xxdm = repo.session.xxdm,
                schoolName = repo.session.xxmc,
                schools = schoolDir.cached(),
                overlayAllowed = overlayAllowed(),
                displayName = repo.session.user.xm,
                // 首次启动提醒用户:没有作息表就只能按节次提醒
                periodNoticeVisible = loggedIn && !times.configured && !periodStore.noticeShown,
                // 进程被回收后重建:回到用户原来那一页
                tab = savedTab(),
                selectedCourseKey = saved[KEY_SELECTED_COURSE],
            ),
        )
        if (loggedIn) {
            // P3a:**首帧先上缓存课表**,网络刷新在后台跑。
            //
            // 之前这里只有 loadTimetable(),于是打开 App 永远是空课表 + 转圈,
            // 要等一个网络来回才有内容 —— 而缓存明明就在本地。
            // (顺带修掉一处文档与实现不符:README 当时写的"首帧直接读缓存"并没有真的实现。)
            //
            // 放在主线程是有意的:这就是一两个小文件的读取 + 一次 JSON 解析,
            // 换来的是"打开就有课表";丢到 IO 协程反而会让首帧仍然空着。
            repo.currentCachedTimetable()?.let { cached ->
                state = state.copy(
                    timetable = cached,
                    // 首帧看到的必然是缓存里那一周(它来自不带 week 的请求)
                    currentWeek = cached.currentWeek,
                    // 学期代码单独存的;缓存里那棵树本身不带学期
                    term = repo.currentCachedTerm() ?: state.term,
                )
            }
            loadTimetable()
            // 重排闹钟要读缓存文件 —— 放后台,别占首帧
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ReminderScheduler.reschedule(getApplication()) }
                // API 34+ 开机广播起不了前台服务,所以每次打开 App 都对齐一次监听状态
                runCatching { SelectionWatch.resync(getApplication()) }
            }
        }
    }

    private fun savedTab(): Tab {
        val name: String? = saved[KEY_TAB]
        return Tab.entries.firstOrNull { it.name == name } ?: Tab.Timetable
    }

    /** 用户看过"未配置作息"的提示了。 */
    fun dismissPeriodNotice() {
        periodStore.noticeShown = true
        state = state.copy(periodNoticeVisible = false)
    }

    // ---- 学校选择 ----

    /**
     * 拉学校名单并缓存。匿名接口,登录前就能调。
     *
     * 失败**不清空**已有缓存 —— 断网时用户仍然应该能选之前拉过的学校。
     */
    fun loadSchools(force: Boolean = false) {
        if (state.schoolsLoading) return
        if (!force && schoolDir.hasCache) return
        state = state.copy(schoolsLoading = true, schoolsError = null)
        latest(KEY_SCHOOLS) {
            runCatching { repo.schools() }
                .onSuccess { rows ->
                    val n = schoolDir.refresh(rows)
                    state = state.copy(
                        schoolsLoading = false,
                        schools = schoolDir.cached(),
                        schoolsError = if (n == 0) "名单为空" else null,
                        // 顺手把"已知 xxdm"的学校名补上,登录页就不用显示代码了
                        schoolName = state.schoolName.ifEmpty { schoolDir.nameOf(state.xxdm).orEmpty() },
                    )
                }
                .onFailure {
                    state = state.copy(
                        schoolsLoading = false,
                        schools = schoolDir.cached(),
                        schoolsError = "获取学校名单失败,请检查网络后重试(已选过的学校仍可用)",
                    )
                }
        }
    }

    fun openSchoolPicker() {
        state = state.copy(schoolPickerOpen = true)
        loadSchools()
    }

    fun closeSchoolPicker() {
        state = state.copy(schoolPickerOpen = false)
    }

    // ---- 顶栏「更多」菜单 ----

    fun toggleMoreMenu() {
        state = state.copy(moreMenuOpen = !state.moreMenuOpen)
    }

    fun closeMoreMenu() {
        state = state.copy(moreMenuOpen = false)
    }

    fun pickSchool(school: com.xiqueer.android.data.School) {
        repo.session.xxdm = school.xxdm
        repo.session.xxmc = school.xxmc
        state = state.copy(
            xxdm = school.xxdm,
            schoolName = school.xxmc,
            schoolPickerOpen = false,
            error = null,
        )
    }

    /** 本地搜索学校(中文名 / 全拼 / 首字母)。 */
    fun searchSchools(query: String): List<com.xiqueer.android.data.School> =
        schoolDir.search(query)

    // ---- 上课携带物品 ----

    /**
     * 设置某门课要带的东西。
     *
     * 键是**课程名** —— 同一门课不同周次带的东西一样,而按课程名建键
     * 才能让设置页只列一份清单(而不是十几节课各列一遍)。
     */
    fun setCourseItems(courseName: String, text: String) {
        itemsStore.set(courseName, text)
        state = state.copy(courseItems = itemsStore.all())
    }

    /** 当前课表里出现过的课程名(去重、排序),设置页按它列清单。 */
    fun courseNames(): List<String> = (state.timetable?.days?.flatten() ?: emptyList())
        .map { it.name }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    fun openItemsSheet() {
        state = state.copy(itemsSheetOpen = true)
    }

    fun closeItemsSheet() {
        state = state.copy(itemsSheetOpen = false)
    }

    // ---- 调休 / 换课 ----

    /**
     * 添加一条调休。**只写本地覆盖层**,不碰服务器课表。
     *
     * 加完必须立刻重排提醒 —— 否则会出现"课挪走了,闹钟还在原来的日子响"。
     */
    fun addShift(from: String, to: String, courses: List<String>?): String {
        val shift = shiftStore.add(from, to, courses)
            ?: return "没添加:日期格式不对、起止是同一天,或者这条调休已经存在"
        state = state.copy(overlays = readOverlays())
        rescheduleReminders()
        return "已记录调休:${shift.describe()}"
    }

    fun removeShift(id: String) {
        if (shiftStore.remove(id)) {
            state = state.copy(overlays = readOverlays())
            rescheduleReminders()
        }
    }

    // ---- 课节覆写(改某一节的教室 / 节次)----

    /**
     * 写入/更新某个课节的覆写。
     *
     * [week] 用**底表那一周的周次**(`timetable.currentWeek`),不是"真实本周" ——
     * 用户在课表页翻到第 5 周去改,改的就必须是第 5 周那一节。
     *
     * `room` / `periods` 都为 null 等价于"恢复",直接清掉这条覆写。
     *
     * @return 人话回执,直接显示给用户
     */
    fun setCourseOverride(
        week: Int,
        weekday: Int,
        courseKey: String,
        room: String?,
        periods: String?,
    ): String {
        if (week <= 0) return "改不了:课表还没加载出周次"
        val entry = courseOverrideStore.put(week, weekday, courseKey, room, periods)
        state = state.copy(overlays = readOverlays())
        rescheduleReminders()
        return if (entry == null) "已恢复这一节" else "已记录课节改动:${entry.describe()}"
    }

    /** 清掉某一节的覆写(等于"恢复到原课节位置")。 */
    fun clearCourseOverride(week: Int, weekday: Int, courseKey: String): String {
        val removed = courseOverrideStore.remove(week, weekday, courseKey)
        state = state.copy(overlays = readOverlays())
        rescheduleReminders()
        return if (removed) "已恢复这一节" else "这一节本来就没有改动"
    }

    // ---- 晚自习(自定义时段)----

    /** 覆盖式保存晚自习设置。传空列表 = 全部关掉。 */
    fun saveSelfStudies(slots: List<com.xiqueer.android.data.SelfStudySlot>): String {
        val saved = selfStudyStore.save(slots)
        state = state.copy(overlays = readOverlays())
        rescheduleReminders()
        return if (saved.isEmpty()) "已关闭晚自习" else "已保存晚自习:${saved.size} 条"
    }

    fun openSelfStudySheet() {
        state = state.copy(selfStudySheetOpen = true)
    }

    fun closeSelfStudySheet() {
        state = state.copy(selfStudySheetOpen = false)
    }

    // ---- 课节改动编辑面板 ----

    fun openCourseEdit() {
        state = state.copy(courseEditOpen = true)
    }

    fun closeCourseEdit() {
        state = state.copy(courseEditOpen = false)
    }

    fun openShiftSheet() {
        state = state.copy(shiftSheetOpen = true)
    }

    fun closeShiftSheet() {
        state = state.copy(shiftSheetOpen = false)
    }

    private fun rescheduleReminders() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ReminderScheduler.reschedule(getApplication()) }
        }
    }

    /** 作息表/提醒设置变更后刷新快照并重排提醒(AI 工具改的也会走到这里)。 */
    fun refreshPeriodTimes() {
        val times = periodStore.load()
        state = state.copy(
            periodTimes = times,
            // AI 可能刚改了提醒开关/提前量,快照要跟着走,否则设置页显示的是旧值
            reminderEnabled = periodStore.reminderEnabled,
            leadMinutes = periodStore.leadMinutes,
            digestEnabled = periodStore.digestEnabled,
            digestAt = periodStore.digestAt,
            watch = watchStore.load(),
            watchPollCount = watchStore.pollCount,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ReminderScheduler.reschedule(getApplication()) }
        }
    }

    // ---- 导航 ----

    fun selectTab(tab: Tab) {
        state = state.copy(tab = tab)
        saved[KEY_TAB] = tab.name
        when (tab) {
            // 日程和课表都需要课表数据
            Tab.Schedule, Tab.Timetable -> if (state.timetable == null) loadTimetable()
            Tab.Grades -> if (state.grades.isEmpty()) loadGrades()
            Tab.Notices -> if (!state.noticesLoaded) loadNotices()
            Tab.Exams -> if (!state.examsLoaded) loadExams()
            Tab.Plan -> if (!state.planLoaded) loadPlan()
        }
    }

    // ---- 课程详情浮层 ----
    //
    // 状态放在 VM 而不是 MainActivity 的 `remember` 里:
    // remember 在旋转屏幕时就会丢,VM 至少能撑过配置变更。

    fun openCourse(course: Course) {
        val key = courseKey(course)
        state = state.copy(selectedCourseKey = key)
        saved[KEY_SELECTED_COURSE] = key
    }

    fun closeCourse() {
        state = state.copy(selectedCourseKey = null)
        saved[KEY_SELECTED_COURSE] = null
    }

    /** 按 key 从当前课表里找回课程对象 —— 只存 key 是为了不给 Bundle 塞复杂对象。 */
    fun selectedCourse(): Course? = selectedCourseSlot()?.second

    /**
     * 选中的课 + 它在**星期几**(0=周一)。
     *
     * 课节覆写要定位到"第几周 + 星期几 + 这一节",只给一个 [Course] 是不够的 ——
     * 同一门课可能一周上两次,只有星期几能把它们分开。
     */
    fun selectedCourseSlot(): Pair<Int, Course>? {
        val key = state.selectedCourseKey ?: return null
        val days = state.timetable?.days ?: return null
        for (d in days.indices) {
            val hit = days[d].firstOrNull { courseKey(it) == key }
            if (hit != null) return d to hit
        }
        return null
    }

    /** 当前选中那一节已存的覆写;没有则 null。给「课节改动」面板显示与比对用。 */
    fun selectedOverride(): com.xiqueer.android.data.CourseOverride? {
        val (weekday, course) = selectedCourseSlot() ?: return null
        val week = state.timetable?.currentWeek ?: return null
        val k = courseKey(course)
        return state.overlays.courseOverrides.firstOrNull {
            it.week == week && it.weekday == weekday && it.courseKey == k
        }
    }

    private fun courseKey(c: Course) = com.xiqueer.android.data.CourseKey.of(c)

    // ---- 登录 / 登出 ----

    /**
     * 登录。
     *
     * 学校不再由登录页填代码 —— `xxdm` 来自用户在「选择学校」里选中的那一所
     * (`state.xxdm`,存在 [SessionStore] 里)。
     */
    fun login(username: String, password: String) {
        val xxdm = state.xxdm
        // 没选学校就别发请求 —— 服务端只会回一句莫名其妙的错误
        if (xxdm.isBlank()) {
            state = state.copy(error = "请先选择学校")
            return
        }
        cancelAll() // 上一次登录/刷新的在途请求作废
        state = state.copy(busy = true, error = null)
        latest(KEY_AUTH) {
            val result = runCatching { repo.login(username.trim(), password, xxdm.trim()) }
            val r = result.getOrElse { e ->
                Log.e(TAG, "login failed", e)
                state = state.copy(busy = false, error = describe(e))
                return@latest
            }
            if (!r.ok) {
                state = state.copy(busy = false, error = r.message ?: "登录失败")
                return@latest
            }
            state = state.copy(
                busy = false,
                loggedIn = true,
                error = null,
                username = username.trim(),
                xxdm = xxdm.trim(),
                displayName = r.user?.xm.orEmpty(),
                // 登录后才第一次看到课表,这时候才该提示作息表的事
                periodTimes = periodStore.load(),
                periodNoticeVisible = !periodStore.load().configured && !periodStore.noticeShown,
            )
            loadTimetable()
        }
    }

    /**
     * 登出。
     *
     * **先取消所有在途请求再清状态** —— 否则旧账号的响应回来会把数据写回界面,
     * 这是之前实实在在存在的 bug。
     */
    fun logout() {
        cancelAll()
        // 先把「学校 + 学号」留下来。这两个**不是凭据**:
        // 会话过期后重新登录时,让用户只差一个密码,而不是重选一遍学校、再输一遍学号。
        // (SessionStore 本来就保留 xxdm/xxmc/username,这里只是别把界面状态整块清掉。)
        val keepUsername = state.username
        val keepXxdm = state.xxdm
        val keepSchoolName = state.schoolName
        repo.logout()
        // ⚠️ 必须带上本地存储的那一堆设置。只给 username/xxdm/schoolName 的话,
        // AI 配置、上课携带、悬浮窗开关、作息、提醒、调休全部归零 —— 真机上表现为
        // "重新登录后 AI 说没配置,进设置转一圈又好了"。
        state = withLocalState(
            UiState(
                booting = false,
                username = keepUsername,
                xxdm = keepXxdm,
                schoolName = keepSchoolName,
            ),
        )
    }

    // ---- 课表 ----

    /**
     * 拉课表。
     *
     * [week] 为空 = 真实本周(同时刷新"当前周"缓存与提醒排期);
     * [week] 非空 = 翻页看某一周,**不碰当前周缓存、也不重排提醒**。
     *
     * 这个区分是必须的:用户翻到第 10 周时,课前提醒与「今天有什么课」
     * 仍然要按**真实本周**算,否则会拿着第 10 周的课表去提醒今天。
     */
    /**
     * 日程页要显示"明天",而课表接口**一次只回一周**。
     *
     * 明天跨周时(周日看周一、或正在看非本周)必须另外准备一份下周课表,
     * 否则日期落在缓存那一周之外,`forDate` 返回空 → 界面误报"明天没有日程"。
     *
     * 只在**明天真的跨周**时才多发这一个请求:周一到周六完全不会多打接口。
     * 先看本地按周缓存,没有再联网。
     */
    private fun ensureNextWeekLoaded(t: Timetable) {
        val monday = com.xiqueer.android.notify.ClassReminder.weekMonday(t) ?: return
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        val diff = java.time.temporal.ChronoUnit.DAYS.between(monday, tomorrow).toInt()
        if (diff in 0..6) {
            // 明天还在本周 → 不需要那一份了,顺手放掉
            if (state.nextWeekTimetable != null) state = state.copy(nextWeekTimetable = null)
            return
        }
        val next = t.currentWeek + Math.floorDiv(diff, 7)
        if (next < 1 || (t.maxWeek > 0 && next > t.maxWeek)) return
        if (state.nextWeekTimetable?.currentWeek == next) return

        // 翻过页的话按周缓存里已经有了,直接拿,不用联网
        repo.cachedWeek(t.term, next)?.let {
            state = state.copy(nextWeekTimetable = it)
            return
        }
        latest(KEY_NEXT_WEEK) {
            runCatching { repo.refreshTimetable(t.term.ifEmpty { null }, next) }
                .onSuccess { nt -> state = state.copy(nextWeekTimetable = nt) }
                // 取不到不是致命问题:日程页会显示"下周课表还没加载",而不是"明天没有日程"
                .onFailure { Log.w(TAG, "预取第 $next 周课表失败", it) }
        }
    }

    fun loadTimetable(term: String? = null, week: Int? = null, force: Boolean = false) {
        state = state.copy(busy = true, error = null)
        latest(KEY_TIMETABLE) {
            val result = runCatching {
                repo.refreshTimetable(term ?: state.term.ifEmpty { null }, week)
            }
            result.onSuccess { t ->
                state = state.copy(
                    busy = false,
                    timetable = t,
                    term = t.term,
                    // 只有"不带 week"的那次响应才能定义真实本周
                    currentWeek = if (week == null) t.currentWeek else state.currentWeek,
                    error = null,
                )
                // 翻了页就不重排提醒:提醒只跟真实本周有关,重排纯属浪费
                if (week == null) {
                    // 明天跨周的话,顺手把下周也准备好(日程页要用)
                    ensureNextWeekLoaded(t)
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { ReminderScheduler.reschedule(getApplication()) }
                    }
                }
            }.onFailure { fail("timetable", it) }
        }
    }

    /** 翻到第 [week] 周。先上本地缓存,再后台刷新 —— 来回翻页不该反复打接口。 */
    fun showWeek(week: Int) {
        val t = state.timetable ?: return
        val target = week.coerceIn(1, maxOf(1, t.maxWeek))
        if (target == t.currentWeek) return
        repo.cachedWeek(state.term, target)?.let { state = state.copy(timetable = it, error = null) }
        loadTimetable(week = target)
    }

    fun prevWeek() {
        state.timetable?.let { showWeek(it.currentWeek - 1) }
    }

    fun nextWeek() {
        state.timetable?.let { showWeek(it.currentWeek + 1) }
    }

    /** 回到真实本周。不知道真实本周时退回"不带 week"的那次请求。 */
    fun backToCurrentWeek() {
        val cur = state.currentWeek
        if (cur > 0 && state.timetable?.currentWeek != cur) loadTimetable(week = cur) else loadTimetable()
    }

    // ---- 成绩 ----

    fun loadGrades(term: String? = null) {
        state = state.copy(busy = true, error = null)
        latest(KEY_GRADES) {
            runCatching { repo.loadGrades(term ?: state.term.ifEmpty { null }) }
                .onSuccess { state = state.copy(busy = false, grades = it, error = null) }
                .onFailure { fail("grades", it) }
        }
    }

    // ---- 通知 ----

    fun loadNotices() {
        state = state.copy(busy = true, error = null)
        latest(KEY_NOTICES) {
            runCatching { repo.notices() }
                .onSuccess {
                    state = state.copy(busy = false, notices = it, noticesLoaded = true, error = null)
                }
                .onFailure { fail("notices", it) }
        }
    }

    fun openNotice(n: Map<String, Any?>) {
        val dm = n["dm"]?.toString().orEmpty()
        val system = n["system"]?.toString().orEmpty()
        state = state.copy(noticeDetailOpen = true, noticeDetail = null)
        latest(KEY_NOTICE_DETAIL) {
            runCatching { repo.noticeDetail(dm, system) }
                .onSuccess { state = state.copy(noticeDetail = it) }
                .onFailure { e ->
                    Log.e(TAG, "noticeDetail failed", e)
                    state = state.copy(noticeDetailOpen = false, noticeDetail = null)
                    fail("noticeDetail", e)
                }
        }
    }

    fun closeNotice() {
        jobs.remove(KEY_NOTICE_DETAIL)?.cancel()
        state = state.copy(noticeDetailOpen = false, noticeDetail = null)
    }

    // ---- 考试 ----

    fun loadExams() {
        state = state.copy(busy = true, error = null)
        latest(KEY_EXAMS) {
            runCatching { repo.examTerms() }
                .onSuccess { terms ->
                    val first = terms.firstOrNull()
                    val dm = first?.get("dm")?.toString()
                    val lcdm = ((first?.get("kslc") as? List<*>)?.firstOrNull() as? Map<*, *>)
                        ?.get("lcdm")?.toString()
                    state = state.copy(
                        busy = false, examTerms = terms, examsLoaded = true,
                        examTerm = dm, examRound = lcdm, error = null,
                    )
                    if (dm != null && lcdm != null) fetchExamItems(dm, lcdm)
                }
                .onFailure { fail("exams", it) }
        }
    }

    fun selectExamTerm(dm: String) {
        val rounds = state.examTerms.firstOrNull { it["dm"]?.toString() == dm }?.get("kslc") as? List<*>
        val lcdm = (rounds?.firstOrNull() as? Map<*, *>)?.get("lcdm")?.toString()
        state = state.copy(examTerm = dm, examRound = lcdm, examItems = emptyList())
        if (lcdm != null) fetchExamItems(dm, lcdm)
    }

    fun selectExamRound(lcdm: String) {
        val dm = state.examTerm ?: return
        state = state.copy(examRound = lcdm, examItems = emptyList())
        fetchExamItems(dm, lcdm)
    }

    private fun fetchExamItems(xnxq: String, lcdm: String) {
        state = state.copy(busy = true, error = null)
        latest(KEY_EXAM_ITEMS) {
            runCatching { repo.examItems(xnxq, lcdm) }
                .onSuccess { state = state.copy(busy = false, examItems = it, error = null) }
                .onFailure { fail("examItems", it) }
        }
    }

    // ---- 培养方案 ----

    fun loadPlan() {
        state = state.copy(busy = true, error = null)
        latest(KEY_PLAN) {
            runCatching { repo.studyPlan() }
                .onSuccess { state = state.copy(busy = false, plan = it, planLoaded = true, error = null) }
                .onFailure { fail("plan", it) }
        }
    }

    // ---- 设置 ----

    fun openSettings() {
        state = state.copy(
            settingsOpen = true,
            agentConfig = agentStore.load(),
            watch = watchStore.load(),
            watchPollCount = watchStore.pollCount,
            overlayEnabled = overlayStore.enabled,
            // 每次打开设置都重新问一次系统 —— 用户可能刚从系统设置页回来
            overlayAllowed = overlayAllowed(),
        )
    }

    private fun overlayAllowed(): Boolean = runCatching {
        android.provider.Settings.canDrawOverlays(getApplication())
    }.getOrDefault(false)

    /**
     * 开关悬浮窗。
     *
     * 打开时如果系统还没授权,这里**只记下用户的意愿**,由界面引导去系统设置授权;
     * 绝不自己去弹权限请求 —— "显示在其他应用上层"这种权限必须用户明确点过去。
     */
    fun setOverlayEnabled(on: Boolean) {
        overlayStore.enabled = on
        state = state.copy(overlayEnabled = on, overlayAllowed = overlayAllowed())
        // 立刻按新设置对齐一次(正在上课时开关能马上看到效果)
        rescheduleReminders()
    }

    fun closeSettings() {
        state = state.copy(settingsOpen = false)
    }

    /**
     * 保存 AI 配置。
     *
     * [apiKey] 为 `null` 表示"不改动已存的 Key" —— Key 在加密存储里,
     * 界面不该为了保存别的字段而把它解出来再写回去。
     */
    fun saveAgentConfig(c: AgentConfig, apiKey: String? = null) {
        agentStore.save(c, apiKey)
        state = state.copy(agentConfig = agentStore.load())
    }

    /** 手动写入作息表 —— 与 AI 导入走同一个存储。 */
    fun saveManualPeriodTimes(times: PeriodTimes) {
        periodStore.save(times.copy(source = PeriodTimeSource.Manual))
        periodStore.noticeShown = true
        refreshPeriodTimes()
        state = state.copy(periodNoticeVisible = false)
    }

    fun setReminderEnabled(on: Boolean) {
        periodStore.reminderEnabled = on
        refreshPeriodTimes()
    }

    fun setLeadMinutes(minutes: Int) {
        periodStore.leadMinutes = minutes
        state = state.copy(leadMinutes = periodStore.leadMinutes)
    }

    fun setDigest(enabled: Boolean, at: String) {
        periodStore.digestEnabled = enabled
        periodStore.digestAt = at
        state = state.copy(digestEnabled = enabled, digestAt = at)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ReminderScheduler.reschedule(getApplication()) }
        }
    }

    fun saveWatch(c: com.xiqueer.android.watch.WatchConfig) {
        val prev = watchStore.load()
        watchStore.save(c)
        state = state.copy(watch = c)
        when {
            // 首次开启:清零本轮计数与去重集合,再拉服务
            c.enabled && !prev.enabled -> SelectionWatch.enable(getApplication())
            !c.enabled && prev.enabled -> SelectionWatch.disable(getApplication())
            // 只是改参数(间隔/安静时段),让服务下一轮自己读到新配置即可
            else -> SelectionWatch.resync(getApplication())
        }
    }

    // ---- AI 助手 ----

    fun toggleAgent() {
        state = state.copy(agent = state.agent.copy(open = !state.agent.open))
    }

    fun closeAgent() {
        state = state.copy(agent = state.agent.copy(open = false))
    }

    /** 发一条消息,跑完整个工具调用循环。 */
    fun sendAgent(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || state.agent.busy) return
        state = state.copy(
            agent = state.agent.copy(
                busy = true,
                turns = state.agent.turns + ChatTurn("user", msg),
                status = "思考中…",
            ),
        )
        latest(KEY_AGENT) {
            runner.send(msg) { ev -> handleAgentEvent(ev) }
            state = state.copy(agent = state.agent.copy(busy = false, status = ""))
            // AI 可能刚改了作息表 —— 让提醒重排一次
            if (periodStore.load() != state.periodTimes) refreshPeriodTimes()
        }
    }

    private fun handleAgentEvent(ev: AgentEvent) {
        val a = state.agent
        state = when (ev) {
            is AgentEvent.Say -> state.copy(agent = a.copy(turns = a.turns + ChatTurn("ai", ev.text)))
            is AgentEvent.Calling -> state.copy(
                agent = a.copy(status = "正在 ${toolLabel(ev.name)}…"),
            )
            is AgentEvent.Called -> state.copy(agent = a.copy(status = ""))
            is AgentEvent.Confirm -> state.copy(
                agent = a.copy(
                    pending = ev.action,
                    turns = a.turns + ChatTurn("note", "已生成待确认操作:${ev.action.title}"),
                ),
            )
            is AgentEvent.Failed -> state.copy(
                agent = a.copy(turns = a.turns + ChatTurn("error", ev.message)),
            )
            AgentEvent.Done -> state.copy(agent = a.copy(status = ""))
        }
    }

    /**
     * 用户点了「确认执行」。
     *
     * 这是写操作**唯一**的真实出口 —— 模型没有任何路径能走到这里。
     */
    fun confirmPending() {
        val action = state.agent.pending ?: run {
            Log.w(TAG, "confirmPending: 没有待确认操作")
            return
        }
        Log.i(TAG, "确认执行写操作:${action.toolName} ${action.arguments}")
        state = state.copy(
            agent = state.agent.copy(pending = null, busy = true, status = "正在执行…"),
        )
        latest(KEY_AGENT_COMMIT) {
            val result = runCatching { tools.commit(action) }
                .getOrElse { "执行失败:${it.javaClass.simpleName}: ${it.message}" }
            Log.i(TAG, "写操作返回:${result.take(120)}")
            state = state.copy(
                agent = state.agent.copy(
                    busy = false,
                    status = "",
                    turns = state.agent.turns + ChatTurn("note", result),
                ),
            )
        }
    }

    fun dismissPending() {
        Log.i(TAG, "取消待确认操作:${state.agent.pending?.toolName}")
        runner.clearPending()
        state = state.copy(agent = state.agent.copy(pending = null))
    }

    private fun toolLabel(name: String): String = when (name) {
        "get_today_courses" -> "查今天的课"
        "get_week_timetable" -> "查本周课表"
        "get_grades" -> "查成绩"
        "get_exams" -> "查考试"
        "list_notices" -> "查通知"
        "read_notice" -> "读通知"
        "get_study_plan" -> "查培养方案"
        "get_period_times" -> "读作息设置"
        "import_period_times" -> "写入作息表"
        "set_reminder" -> "改提醒设置"
        "start_selection_watch" -> "开启选课监听"
        "stop_selection_watch" -> "关闭选课监听"
        "search_open_courses" -> "查选课"
        "submit_course" -> "准备选课提交"
        else -> name
    }

    // ---- 工具 ----

    fun refreshCurrent() {
        when (state.tab) {
            // 日程页的刷新就是刷课表(它展示的就是课表里的课)
            Tab.Schedule,
            Tab.Timetable,
            -> {
                // 刷新停在用户正在看的那一周;但如果他看的就是本周,
                // 必须走"不带 week"的那条,否则当前周缓存(提醒依赖它)不会更新
                val viewed = state.timetable?.currentWeek
                loadTimetable(week = viewed?.takeIf { it != state.currentWeek })
            }
            Tab.Grades -> loadGrades()
            Tab.Notices -> loadNotices()
            Tab.Exams -> loadExams()
            Tab.Plan -> loadPlan()
        }
    }

    /** 起一个"频道"的最新请求,取消同频道的上一个。 */
    private fun latest(key: String, block: suspend CoroutineScope.() -> Unit) {
        jobs[key]?.cancel()
        jobs[key] = viewModelScope.launch { block() }
    }

    private fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /** 关掉错误提示条。用户已经看过了,不该一直挂着。 */
    fun dismissError() {
        state = state.copy(error = null, sessionExpired = false)
    }

    private fun fail(tag: String, e: Throwable) {
        Log.e(TAG, "$tag failed", e)
        state = state.copy(
            busy = false,
            error = describe(e),
            sessionExpired = com.xiqueer.protocol.XqErrors.isSessionExpired(e),
        )
    }

    override fun onCleared() {
        cancelAll()
        super.onCleared()
    }

    /** 把异常摊开显示 —— 只写"网络异常"会让排查变成猜谜。 */
    private fun describe(e: Throwable): String {
        if (e is kotlinx.coroutines.CancellationException) return "已取消"
        val chain = generateSequence(e) { it.cause }.take(4)
            .joinToString(" ← ") { it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") }
        return "请求失败:$chain"
    }

    private companion object {
        const val TAG = "XqApp"
        const val KEY_AUTH = "auth"
        const val KEY_TIMETABLE = "timetable"
        const val KEY_GRADES = "grades"
        const val KEY_NOTICES = "notices"
        const val KEY_NOTICE_DETAIL = "noticeDetail"
        const val KEY_EXAMS = "exams"
        const val KEY_EXAM_ITEMS = "examItems"
        const val KEY_PLAN = "plan"
        const val KEY_AGENT = "agent"
        const val KEY_AGENT_COMMIT = "agentCommit"
        const val KEY_SCHOOLS = "schools"
        const val KEY_NEXT_WEEK = "nextWeek"

        // SavedStateHandle 的键(P3a:进程被回收后要能回到原来那一页)
        const val KEY_TAB = "ui.tab"
        const val KEY_SELECTED_COURSE = "ui.selectedCourse"
    }
}
