package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.notify.ClassOccurrence
import com.xiqueer.android.notify.ClassReminder
import com.xiqueer.android.notify.ScheduleOverrides
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.Timetable
import com.xiqueer.protocol.XqFeatures
import kotlinx.coroutines.delay
import java.time.LocalDate

/** 已上完 = 绿;未上 = 黄;已调走 = 灰。刻意用**底条**,免得盖过课程内容。 */
private val DONE = androidx.compose.ui.graphics.Color(0xFF4CD07D)
private val TODO = androidx.compose.ui.graphics.Color(0xFFE8C547)
private val MOVED = androidx.compose.ui.graphics.Color(0xFF6B7280)

/**
 * 日程页:今天有什么课、下一节是什么、要带什么。
 *
 * 几条设计决定:
 * - **按"时间远近"排**,不按节次:用户关心的是"接下来是哪节"。
 * - **离当前时间最近的那一节加粗放大**(需求里明确要的),其余弱化。
 * - **今天的课上完之后自动显示明天**:大学里下午没课是常态,
 *   一直停在"今天没有课了"没用,不如提前告诉他明天第一节是什么。
 * - 时间基准是**系统时间**,每秒刷新一次"还剩多少分钟"。
 */
@Composable
fun ScheduleScreen(
    timetable: Timetable?,
    times: PeriodTimes,
    items: Map<String, String>,
    /** 调休覆盖层。日程必须按它渲染,否则调了休这里还是老样子。 */
    shifts: List<com.xiqueer.android.data.Shift> = emptyList(),
    /**
     * 下一周课表。只有"明天跨周"时才需要 —— 见 [timetableForDate]。
     * 没传或为 null 时,跨周的明天会显示"下周课表还没加载",而不是误报"没有课"。
     */
    nextWeekTimetable: Timetable? = null,
    modifier: Modifier = Modifier,
    onCourseClick: (com.xiqueer.protocol.Course) -> Unit = {},
) {
    if (timetable == null) {
        EmptyState("还没有课表,点右上角刷新", modifier)
        return
    }

    // 每分钟刷新一次"距今多少分钟"。用一个 state 而不是记时器逐个更新,
    // 这样整页只有一处会因时间变化而重组。
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    val today = LocalDate.now()
    val todayCourses = remember(timetable, times, shifts, today) {
        ScheduleOverrides.forDate(timetable, times, shifts, today)
    }
    val now = nowMillis
    // 被调走的课不参与"还剩几节"的判断 —— 它今天不上了
    val stillToAttend = todayCourses.filter { !it.isMovedOut && !isDone(it.occurrence, now) }

    /**
     * 显示哪一天。
     *
     * 需求里三条要同时成立:
     * 1. **已上完的课要留着**,用绿底条表示"上完了"(而不是消失);
     * 2. 今天的课**全部上完**之后,自动切到明天;
     * 3. 被调走的课要在**原位置灰显**。
     *
     * 第 2、3 条会打架:如果今天的课全被调走,"没人要上了"和"都上完了"看起来一样。
     * 但它们的含义完全不同 —— 所以这里**只在"今天确实没有课"或"该上的都上完了"时切明天**,
     * 全被调走时仍然留在今天,把灰卡摆给用户看(灰卡上已经写了调去哪天)。
     */
    val hasAnyToday = todayCourses.isNotEmpty()
    val allAttended = stillToAttend.isEmpty() && todayCourses.any { !it.isMovedOut }
    val showingTomorrow = !hasAnyToday || allAttended
    val tomorrow = today.plusDays(1)

    // 明天可能已经跨周。课表接口一次只回一周,用本周那份算明天的课,`forDate` 会
    // 因为日期不在本周范围内而返回空 —— 界面于是误报"明天没有课"。
    // 真机上的表现就是:得先去课表页翻一下,这里才正常。
    val tomorrowTimetable = remember(timetable, nextWeekTimetable, tomorrow) {
        timetableForDate(timetable, nextWeekTimetable, tomorrow)
    }
    // 明天跨周但下周课表还没拿到 —— 这时"空"是**不知道**,不是"没有课"
    val tomorrowUnknown = showingTomorrow && tomorrowTimetable == null

    val list = if (showingTomorrow) {
        if (tomorrowTimetable == null) emptyList() else {
            remember(tomorrowTimetable, times, shifts, tomorrow) {
                ScheduleOverrides.forDate(tomorrowTimetable, times, shifts, tomorrow)
            }
        }
    } else {
        todayCourses
    }
    val showDate = if (showingTomorrow) tomorrow else today

    // "离当前时间最近的一节":未上的里面开始时间最早的那节
    val nearest = stillToAttend.firstOrNull { (it.occurrence.classStartMillis ?: Long.MAX_VALUE) >= now }
        ?: stillToAttend.firstOrNull()

    Column(modifier.fillMaxSize().padding(top = 6.dp)) {
        DateHeaderCard(showDate, timetable, times, showingTomorrow)
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            if (list.isEmpty()) {
                EmptyState(
                    when {
                        // 跨周且下周还没加载:这是"不知道",不能写成"没有课"
                        tomorrowUnknown -> "下周课表还没加载出来,点右上角刷新"
                        showingTomorrow -> "明天没有课"
                        else -> "今天没有课"
                    },
                    Modifier.fillMaxWidth().height(200.dp),
                )
            } else if (list.all { it.isMovedOut }) {
                // 今天全被调走:只留灰卡也太空,顺手说一句避免用户以为界面坏了
                Text(
                    "今天的课都调走了 —— 下面灰卡标着调去了哪天",
                    color = XqColors.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                list.forEach { e -> ScheduleRow(e, nearest, showingTomorrow, now, items, times, timetable, onCourseClick) }
            } else {
                list.forEach { e -> ScheduleRow(e, nearest, showingTomorrow, now, items, times, timetable, onCourseClick) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 日程里的一行:卡片 + 间距。抽出来是因为"全部被调走"那条分支也要用。 */
@Composable
private fun ScheduleRow(
    e: com.xiqueer.android.notify.EffectiveCourse,
    nearest: com.xiqueer.android.notify.EffectiveCourse?,
    showingTomorrow: Boolean,
    now: Long,
    items: Map<String, String>,
    times: PeriodTimes,
    timetable: Timetable,
    onCourseClick: (com.xiqueer.protocol.Course) -> Unit,
) {
    val o = e.occurrence
    CourseCard(
        o = o,
        isNearest = e === nearest,
        isDone = !showingTomorrow && isDone(o, now),
        nowMillis = now,
        itemText = items[o.courseName].orEmpty(),
        hasPeriodTimes = times.configured,
        movedOut = e.movedTo,
        movedIn = e.movedFrom,
        onClick = { courseOf(timetable, o)?.let(onCourseClick) },
    )
    Spacer(Modifier.height(8.dp))
}

/** 顶部:几号、周几、第几周,以及"明天"的提示。 */
@Composable
private fun DateHeaderCard(
    date: LocalDate,
    t: Timetable,
    times: PeriodTimes,
    isTomorrow: Boolean,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 注意:GlassSurface 的内容槽是 BoxScope,多块内容必须自己包 Column
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (isTomorrow) "明天" else "今天",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = XqColors.TextPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${XqFeatures.weekdayName(date.dayOfWeek.value - 1)}",
                    color = XqColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                if (times.configured) {
                    "第 ${t.currentWeek} 周 · 作息已配置"
                } else {
                    "第 ${t.currentWeek} 周 · 未填作息,只显示节次"
                },
                color = XqColors.TextTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 一张课程卡。
 *
 * [isNearest] 的那张加粗放大 —— 这是这一页唯一要抢注意力的东西。
 * 左侧色条表达状态(绿=已上完 / 黄=未上 / 灰=已调走),整卡不染色。
 *
 * 调休的两种痕迹(需求明确要求):
 * - [movedOut] 非 null:这节课被调走了 → **整卡灰掉、不可点**,并标出调去了哪天;
 * - [movedIn] 非 null:这节课是从别处调来的 → 正常显示,加一个「调休」小标。
 */
@Composable
private fun CourseCard(
    o: ClassOccurrence,
    isNearest: Boolean,
    isDone: Boolean,
    nowMillis: Long,
    itemText: String,
    hasPeriodTimes: Boolean,
    movedOut: java.time.LocalDate? = null,
    movedIn: java.time.LocalDate? = null,
    onClick: () -> Unit,
) {
    val disabled = movedOut != null
    val bar = when {
        disabled -> MOVED                        // 灰:已调走
        isDone -> DONE                           // 绿:已上完
        else -> TODO                             // 黄:未上
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tint = if (isNearest && !disabled) GlassTokens.FillStrong else GlassTokens.Fill,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 左侧色条
            Box(Modifier.width(5.dp).height(if (isNearest && !disabled) 118.dp else 92.dp).background(bar))
            Column(
                Modifier
                    .weight(1f)
                    // 调走了就不可点 —— 这正是需求里"disabled"的意思
                    .clickable(enabled = !disabled, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 括号不能省:`"a" + if (c) "b" else "" + " 节"` 里 else 分支会先算 `"" + " 节"`,
                        // 于是多节连堂那条分支把"节"丢了(显示成「第 3-4」)
                        "第 ${o.periodStart}" +
                            (if (o.periodEnd != o.periodStart) "-${o.periodEnd}" else "") +
                            " 节",
                        color = when {
                            disabled -> XqColors.TextTertiary
                            isNearest -> XqColors.AccentSoft
                            else -> XqColors.TextTertiary
                        },
                        fontSize = if (isNearest && !disabled) 13.sp else 11.sp,
                        fontWeight = if (isNearest && !disabled) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    o.startLabel?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasPeriodTimes) "$it" + (o.endLabel?.let { e -> "–$e" } ?: "") else it,
                            color = XqColors.TextTertiary,
                            fontSize = 11.sp,
                        )
                    }
                    if (movedIn != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("调休 · 来自 ${movedIn.monthValue}-${movedIn.dayOfMonth}", color = XqColors.AccentSoft, fontSize = 10.sp)
                    }
                    if (disabled) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已调至 ${movedOut!!.monthValue}-${movedOut.dayOfMonth}",
                            color = XqColors.TextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                    if (isDone && !disabled) {
                        Spacer(Modifier.width(8.dp))
                        Text("已上完", color = DONE, fontSize = 10.sp)
                    }
                    // 正在上的一节:给出"还剩多少分钟"
                    if (!isDone && !disabled && isOngoing(o, nowMillis)) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "进行中 · 还剩 ${minutesLeft(o, nowMillis)} 分钟",
                            color = XqColors.AccentSoft,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    o.courseName,
                    color = if (disabled) XqColors.TextTertiary else XqColors.TextPrimary,
                    fontSize = if (isNearest && !disabled) 19.sp else 15.sp,
                    fontWeight = if (isNearest && !disabled) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = if (isNearest && !disabled) 24.sp else 20.sp,
                )
                val where = listOf(o.room, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                if (where.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        where,
                        color = if (disabled) XqColors.TextTertiary else XqColors.TextSecondary,
                        fontSize = if (isNearest && !disabled) 13.sp else 12.sp,
                    )
                }
                if (disabled) {
                    // 灰卡就不再喊"要带什么"了 —— 这节课今天不上了
                } else if (itemText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "携带:$itemText",
                        color = XqColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isNearest) FontWeight.Medium else FontWeight.Normal,
                    )
                } else if (isNearest) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "还没填要带的东西 —— 在「设置 → 上课携带」里写一次,以后都会显示",
                        color = XqColors.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

// ---- 判定 ----

/** 上完了 = 下课时间已过。没有作息时退化为"开始时间已过"。 */
private fun isDone(o: ClassOccurrence, nowMillis: Long): Boolean =
    (o.classEndMillis ?: o.classStartMillis)?.let { it < nowMillis } ?: false

/** 正在上 = 开始已过、下课还没到。没有作息时间就无法判断,一律 false。 */
private fun isOngoing(o: ClassOccurrence, nowMillis: Long): Boolean {
    val s = o.classStartMillis ?: return false
    val e = o.classEndMillis ?: return false
    return nowMillis in s until e
}

private fun minutesLeft(o: ClassOccurrence, nowMillis: Long): Long {
    val e = o.classEndMillis ?: return 0
    return ((e - nowMillis) / 60_000L).coerceAtLeast(0)
}

/**
 * 算 [date] 那天该用哪一份课表。
 *
 * [date] 落在 [t] 覆盖的那一周里 → 用 [t];否则用 [next](下一周那份),
 * 而 [next] 为 null 表示**这一周的课表还没拿到** —— 调用方必须把它当成
 * "不知道"而不是"没有课"。这两件事在界面上差别很大:
 * 前者该提示去刷新,后者才是真的没课。
 */
private fun timetableForDate(
    t: Timetable?,
    next: Timetable?,
    date: java.time.LocalDate,
): Timetable? {
    t ?: return null
    val monday = com.xiqueer.android.notify.ClassReminder.weekMonday(t) ?: return t
    val diff = java.time.temporal.ChronoUnit.DAYS.between(monday, date).toInt()
    return if (diff in 0..6) t else next
}

/**
 * 日程卡片 / 网格格子要能点开课程详情,所以得把 ClassOccurrence 映射回 Course。
 *
 * 放在这里、两页共用一份:按"课名 + 起始节次"匹配。
 * 分成两份实现的话,迟早有一页点不开或多点出不该有的详情。
 */
internal fun courseOf(t: Timetable, o: ClassOccurrence): com.xiqueer.protocol.Course? =
    t.days.flatten().firstOrNull {
        it.name == o.courseName && it.periods.contains(o.periodStart.toString())
    }
