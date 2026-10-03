package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.Shift
import com.xiqueer.android.notify.ScheduleOverrides
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.Timetable
import com.xiqueer.protocol.XqFeatures
import java.time.LocalDate

private val ROW_HEIGHT = 54.dp
private val GUTTER_WIDTH = 40.dp

/** 已调走的格子用的灰(与日程页左侧色条同一个灰)。 */
private val MOVED_GRAY = Color(0xFF6B7280)

/** 课程配色只有一份,见 [com.xiqueer.android.CourseColors] —— 导出图片用的是同一套。 */
private fun courseColor(name: String): Color = Color(com.xiqueer.android.CourseColors.of(name))

/**
 * 网格里的一格。
 *
 * 为什么不直接用 [Course]:调休之后"这一格该显示什么"已经不是原始课表能回答的了 ——
 * 被调走的课要**留在原位灰显**,调来的课要**出现在目标日**。
 * 所以网格和日程页一样,数据源统一是 [ScheduleOverrides]。
 */
private data class GridCell(
    val course: Course?,
    val name: String,
    val start: Int,
    val end: Int,
    val room: String,
    /** 非 null = 已调走(灰显 + 不可点),值是调去的日期。 */
    val movedOut: LocalDate? = null,
    /** 非 null = 从那天调来。 */
    val movedIn: LocalDate? = null,
)

private data class Placed(val cell: GridCell, val span: Int, val lane: Int, val lanes: Int)

/** `jcxx` = "1-2" / "3" / "1-4"。解析失败返回 null(该课不参与网格布局)。 */
private fun parsePeriods(jcxx: String): Pair<Int, Int>? {
    val parts = jcxx.split('-', '~', '－', '–').mapNotNull { it.trim().toIntOrNull() }
    return when {
        parts.size >= 2 -> parts[0] to maxOf(parts[0], parts[1])
        parts.size == 1 -> parts[0] to parts[0]
        else -> null
    }
}

/**
 * 把一天里的格子排进不重叠的"泳道",处理同一时段多门课(冲突)的情况。
 * 无冲突时 lanes = 1,课程占满整列宽。
 */
private fun layoutDay(cells: List<GridCell>, maxPeriod: Int): List<Placed> {
    val parsed = cells
        .filter { it.start in 1..maxPeriod }
        .sortedWith(compareBy({ it.start }, { -it.end }))

    val laneEnds = ArrayList<Int>()
    val laneOf = ArrayList<Int>()
    val kept = ArrayList<GridCell>()

    for (c in parsed) {
        var lane = laneEnds.indexOfFirst { it < c.start }
        if (lane < 0) {
            laneEnds.add(c.end)
            lane = laneEnds.size - 1
        } else {
            laneEnds[lane] = c.end
        }
        kept.add(c)
        laneOf.add(lane)
    }
    val lanes = maxOf(1, laneEnds.size)
    return kept.mapIndexed { i, c -> Placed(c, c.end - c.start + 1, laneOf[i], lanes) }
}

/**
 * 整周要显示的格子(下标 0=周一 … 6=周日),**已经过调休修正**。
 *
 * 数据来自 [ScheduleOverrides.week] —— 与日程页 / 提醒 / 摘要 / 悬浮窗 /
 * **导出图片**同一个来源。谁都不许在网格里自己过滤调休:一旦有两处实现,
 * 必然有一处忘记 —— 这个坑在本项目已经踩过一次(课挪走了闹钟还在原日子响,
 * 以及网格页不跟着灰显)。
 *
 * 只有算不出日历日(`weekStart` 为空)时才退回原始课表 ——
 * 那时候连"调休发生在哪天"都无从谈起。
 */
private fun weekCells(
    t: Timetable,
    times: PeriodTimes,
    shifts: List<Shift>,
): List<List<GridCell>> {
    if (t.weekStart == null) {
        return (0..6).map { day ->
            t.days.getOrElse(day) { emptyList() }.mapNotNull { c ->
                parsePeriods(c.periods)?.let { (s, e) -> GridCell(c, c.name, s, e, c.room) }
            }
        }
    }
    return ScheduleOverrides.week(t, times, shifts).map { col ->
        col.map { e ->
            val o = e.occurrence
            GridCell(
                course = courseOf(t, o),
                name = o.courseName,
                start = o.periodStart,
                end = o.periodEnd,
                room = o.room,
                movedOut = e.movedTo,
                movedIn = e.movedFrom,
            )
        }
    }
}

@Composable
fun TimetableScreen(
    timetable: Timetable?,
    times: PeriodTimes,
    modifier: Modifier = Modifier,
    /** 真实本周(翻页时不变),用来决定要不要显示「回到本周」。 */
    currentWeek: Int = 0,
    /** 调休覆盖层。网格必须按它渲染,否则调了休这里还是老样子。 */
    shifts: List<Shift> = emptyList(),
    onCourseClick: (Course) -> Unit = {},
    onPrevWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onCurrentWeek: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    if (timetable == null) {
        EmptyState("还没有课表,点右上角刷新", modifier)
        return
    }

    val today = remember(timetable.weekStart) { todayColumnIndex(timetable) }
    val maxPeriod = maxOf(1, timetable.maxPeriod)

    Column(modifier.fillMaxSize().padding(top = 6.dp)) {
        WeekHeaderCard(
            t = timetable,
            realCurrentWeek = currentWeek,
            onPrevWeek = onPrevWeek,
            onNextWeek = onNextWeek,
            onCurrentWeek = onCurrentWeek,
            onExport = onExport,
        )
        Spacer(Modifier.height(10.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
        ) {
            DayHeaderRow(timetable, today)
            Spacer(Modifier.height(4.dp))
            GridBody(timetable, times, shifts, maxPeriod, today, onCourseClick)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 周标题卡:周次 + 日期范围 + 翻页。
 *
 * ⚠️ **内容必须包在一个 Column 里**。`GlassSurface` 的 content 是 `BoxScope`,
 * 直接放 `Row` + `Text` 两个兄弟节点会让它们**叠在一起** ——
 * 真机上「第 4 周」和「2026-09-21 ~ 2026-09-27」就是这么重叠的。
 * 全项目 6 处 GlassSurface,只有这里当初漏了 Column。
 */
@Composable
private fun WeekHeaderCard(
    t: Timetable,
    realCurrentWeek: Int,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onExport: () -> Unit,
) {
    val viewing = t.currentWeek
    val maxWeek = maxOf(1, t.maxWeek)
    val notCurrent = realCurrentWeek > 0 && viewing != realCurrentWeek

    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用字形而不是图标:避免为一个箭头引 material-icons
                WeekArrow("‹", enabled = viewing > 1, onClick = onPrevWeek)

                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "第 $viewing 周",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = XqColors.TextPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "共 $maxWeek 周 · 每天 ${t.maxPeriod} 节",
                            color = XqColors.TextTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(t.weekStart, t.weekEnd).joinToString(" ~ "),
                            color = XqColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        // 导出入口放在日期右边:不额外占一行,也贴着"这是哪一周"的上下文
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(GlassTokens.FillStrong)
                                .clickable(onClick = onExport)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("导出", color = XqColors.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                WeekArrow("›", enabled = viewing < maxWeek, onClick = onNextWeek)
            }

            if (notCurrent) {
                Spacer(Modifier.height(8.dp))
                // 明确告诉用户"你现在看的不是本周",并给一条回去的路
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "本周是第 $realCurrentWeek 周",
                        color = XqColors.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(XqColors.Accent.copy(alpha = 0.24f))
                            .clickable(onClick = onCurrentWeek)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("回到本周", color = XqColors.AccentSoft, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** 左右翻页箭头。到边界时变灰且不可点。 */
@Composable
private fun WeekArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(36.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) GlassTokens.FillStrong else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) XqColors.TextSecondary else XqColors.TextTertiary.copy(alpha = 0.35f),
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun DayHeaderRow(t: Timetable, today: Int) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(GUTTER_WIDTH))
        for (d in 0..6) {
            val isToday = d == today
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    XqFeatures.weekdayName(d).removePrefix("周"),
                    color = if (isToday) XqColors.AccentSoft else XqColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    dayOfMonth(t, d) ?: "",
                    color = XqColors.TextTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun GridBody(
    t: Timetable,
    times: PeriodTimes,
    shifts: List<Shift>,
    maxPeriod: Int,
    today: Int,
    onClick: (Course) -> Unit,
) {
    // 整周只算一次:7 列共用同一份 effective 数据(调休只在这里生效一次)
    val week = remember(t, times, shifts) { weekCells(t, times, shifts) }
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT * maxPeriod)) {
        // 左侧节次栏:节次号;配置了作息表才显示开始时间
        Column(Modifier.width(GUTTER_WIDTH)) {
            for (p in 1..maxPeriod) {
                Column(
                    Modifier.height(ROW_HEIGHT).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("$p", color = XqColors.TextSecondary, fontSize = 12.sp)
                    PeriodText.startLabel(times, p)?.let {
                        Text(it, color = XqColors.TextTertiary, fontSize = 8.sp)
                    }
                }
            }
        }

        for (day in 0..6) {
            val placed = remember(week, day, maxPeriod) {
                layoutDay(week.getOrElse(day) { emptyList() }, maxPeriod)
            }
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        if (day == today) XqColors.Accent.copy(alpha = 0.06f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                val colW = maxWidth
                for (p in 1..maxPeriod) {
                    Box(
                        Modifier
                            .offset(y = ROW_HEIGHT * (p - 1))
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0x14FFFFFF)),
                    )
                }
                for (b in placed) {
                    CourseBlock(
                        placed = b,
                        isToday = day == today,
                        modifier = Modifier
                            .offset(y = ROW_HEIGHT * (b.cell.start - 1), x = colW * b.lane / b.lanes)
                            .width(colW / b.lanes)
                            .height(ROW_HEIGHT * b.span - 3.dp),
                        onClick = { b.cell.course?.let(onClick) },
                    )
                }
            }
        }
    }
}

/**
 * 网格里的一格课。
 *
 * 调休的两种痕迹,与日程页保持一致(用的是同一份 [ScheduleOverrides] 数据):
 * - 已调走 → **整格灰掉且不可点**,角落标出调去了哪天;
 * - 调来的 → 正常配色,角标「调休」。
 *
 * 只有 [GridCell.course] 存在时才可点 —— 找不到对应 Course 就说明这格
 * 不该有详情页,宁可不响应也不要弹出一个内容不对的详情。
 */
@Composable
private fun CourseBlock(placed: Placed, isToday: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cell = placed.cell
    val disabled = cell.movedOut != null
    val base = if (disabled) MOVED_GRAY else courseColor(cell.name)
    val alpha = when {
        disabled -> 0.20f
        isToday -> 0.44f
        else -> 0.30f
    }
    Box(
        modifier = modifier
            .padding(1.5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(base.copy(alpha = alpha))
            .clickable(enabled = !disabled && cell.course != null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                cell.name,
                color = if (disabled) Color(0x99FFFFFF) else Color.White,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (placed.span >= 3) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (cell.room.isNotEmpty() && placed.span >= 2) {
                Text(
                    cell.room,
                    color = Color(0xCCFFFFFF),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val mark = when {
                cell.movedOut != null -> "→ ${cell.movedOut.monthValue}-${cell.movedOut.dayOfMonth}"
                cell.movedIn != null -> "调休"
                else -> null
            }
            if (mark != null) {
                Text(
                    mark,
                    color = if (disabled) Color(0xB3FFFFFF) else XqColors.AccentSoft,
                    fontSize = 7.5.sp,
                    lineHeight = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = XqColors.TextTertiary, fontSize = 14.sp)
    }
}

// ---- helpers ----

private fun todayColumnIndex(t: Timetable): Int {
    val start = t.weekStart ?: return -1
    return runCatching {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.now()).toInt()
        if (diff in 0..6) diff else -1
    }.getOrDefault(-1)
}

private fun dayOfMonth(t: Timetable, day: Int): String? {
    val start = t.weekStart ?: return null
    return runCatching { LocalDate.parse(start).plusDays(day.toLong()).dayOfMonth.toString() }.getOrNull()
}
