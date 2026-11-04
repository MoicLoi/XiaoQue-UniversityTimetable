package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.data.CourseOverride
import com.xiqueer.android.data.VoidMode
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.XqFeatures
import java.time.LocalDate

/**
 * 「课节改动」—— 对**某一节课**做四件事:改教室、改节次、标失效、换到别的日子。
 *
 * 定位的粒度是"这一节":第几周 + 星期几 + 哪门课。别的周次、别的星期几都不动。
 *
 * 三个硬规则:
 *
 * 1. 改的是**本机覆盖层**,不上传学校 —— 所以随时可以恢复,不存在"改坏了学校数据"。
 * 2. 「恢复到原课节位置」**只在真的和服务端不一样时才显示** ——
 *    没改过却摆一个恢复按钮,会让人以为当前显示的不是原始数据。
 * 3. **失效与换天互斥**:一节课要么取消、要么换一天上。选了失效就把日期清掉,
 *    填了日期就把失效退回「正常」。
 *
 * 为什么换天也放在这个面板里,而不是另开一个入口:用户点开一节课,想改的就是"它"的
 * 一切;分成四个入口让人先想"这属于哪个功能",比多一个字段更糟。
 */
@Composable
fun BoxScope.CourseEditSheet(
    visible: Boolean,
    course: Course?,
    week: Int,
    weekday: Int,
    /** 当前已存的覆写(没有则 null)。 */
    override: CourseOverride?,
    /** 这一节当前的「单节调课」目标日(`yyyy-MM-dd`);没有则 null。 */
    moveTo: String? = null,
    /** 源日期(`yyyy-MM-dd`),用来算「次日 / 一周后」两个快捷键。 */
    sourceDate: String? = null,
    /** 保存。返回人话回执。 */
    onSave: (room: String?, periods: String?, voided: VoidMode?, moveTo: String?) -> String,
    /** 恢复到原课节位置(同时撤销换天)。 */
    onRestore: () -> String,
    onDismiss: () -> Unit,
) {
    if (course == null) return

    // 与服务端值的比对 —— 「恢复」按钮的显示条件就是它
    val effRoom = override?.room ?: course.room
    val effPeriods = override?.periods ?: course.periods
    val changed = effRoom != course.room || effPeriods != course.periods ||
        override?.voided != null || !moveTo.isNullOrBlank()
    val src = remember(sourceDate, visible) {
        sourceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        // 表单初值 = 当前生效值。面板每次打开都重新对齐,
        // 否则上一次没保存的输入会留在下一次打开的面板里。
        //
        // 状态声明放在滚区**外面**:底下的按钮行要用它们,而按钮行不许跟着滚走。
        var room by remember(visible, override?.id, course.code) { mutableStateOf(effRoom) }
        var periods by remember(visible, override?.id, course.code) { mutableStateOf(effPeriods) }
        var voided by remember(visible, override?.id, course.code) { mutableStateOf(override?.voided) }
        var move by remember(visible, override?.id, course.code) { mutableStateOf(moveTo.orEmpty()) }
        var message by remember(visible) { mutableStateOf<String?>(null) }
        LaunchedEffect(visible) { if (visible) message = null }

        // ⚠️ 字段区**必须限高 + 可滚**。字段从"教室/节次"涨到"教室/节次/失效/换天"之后,
        // 内容在 2400px 高的屏上会顶满整屏 —— 那时面板底下那层遮罩一点不剩,
        // 用户就没有"点外面关掉"这条路了(还会误以为面板卡死)。
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "课节改动",
                color = XqColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "第 $week 周 ${XqFeatures.weekdayName(weekday)} · ${course.name}",
                color = XqColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "只改这一次课,别的周次与星期几不受影响。改动只存在本机,不会上传学校。",
                color = XqColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))

            EditField("教室", room, course.room.ifEmpty { "原值留空" }) { room = it }
            EditField("节次", periods, course.periods) { periods = it }
            Text(
                "节次写成区间,如 3-4 或 1-4;填回原值即视为不改。",
                color = XqColors.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // ---- 失效 ----
            Text("这一节不上了?", color = XqColors.TextTertiary, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("正常", voided == null) { voided = null }
                for (m in VoidMode.entries) {
                    Pill(m.label, voided == m) {
                        voided = m
                        // 取消掉的课不该再有"换到哪天"
                        move = ""
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                when (voided) {
                    VoidMode.GREY -> "留在原位、画成灰的,标「已失效」。想改回来点它就行。"
                    VoidMode.HIDE -> "课表与日程里都不再出现。"
                    null -> "标灰 = 留在原位但是灰的;移除 = 干脆不显示。"
                },
                color = XqColors.TextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(10.dp))

            // ---- 跨日调换 ----
            Text("换到别的日子", color = XqColors.TextTertiary, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            EditField("目标日期(yyyy-MM-dd,留空 = 不换)", move, "如 2026-10-09") {
                move = it
                // 换一天上的课不可能同时又"不上"
                if (it.isNotBlank()) voided = null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (src != null) {
                    Pill("次日", move == src.plusDays(1).toString()) {
                        move = src.plusDays(1).toString()
                        voided = null
                    }
                    Pill("一周后", move == src.plusDays(7).toString()) {
                        move = src.plusDays(7).toString()
                        voided = null
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "只搬这一节,节次不变;原位置会灰显并标「→ 日期」。要改节次,上面那个框再改。",
                color = XqColors.TextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )

            // 与服务端不一致时,才把"原值"摆出来
            if (changed) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassTokens.Fill)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "原:第 ${course.periods} 节" +
                            (if (course.room.isNotBlank()) " · ${course.room}" else "") +
                            (if (!moveTo.isNullOrBlank()) " · 已换到 $moveTo" else "") +
                            (override?.voided?.let { " · 已失效" } ?: ""),
                        color = XqColors.TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 按钮行:固定在面板底部,不跟着字段区滚 ----
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(XqColors.Accent.copy(alpha = 0.26f))
                    .clickable {
                        message = onSave(
                            room.trim().takeIf { it.isNotEmpty() },
                            periods.trim().takeIf { it.isNotEmpty() },
                            voided,
                            move.trim().takeIf { it.isNotEmpty() },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("保存改动", color = XqColors.AccentSoft, fontSize = 12.sp)
            }

            if (changed) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GlassTokens.FillStrong)
                        .clickable { message = onRestore() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("恢复到原课节位置", color = XqColors.TextSecondary, fontSize = 11.sp)
                }
            }

            // 显式关闭:字段区限高之后外面还有遮罩,但多一条明路总归更稳
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GlassTokens.FillStrong)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("关闭", color = XqColors.TextSecondary, fontSize = 11.sp)
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = XqColors.TextTertiary, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/** 可选中的小胶囊(正常 / 标灰 / 移除、次日 / 一周后都用它,免得样式长歪)。 */
@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) XqColors.Accent.copy(alpha = 0.30f) else GlassTokens.Fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            color = if (on) XqColors.AccentSoft else XqColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun EditField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(label, color = XqColors.TextTertiary, fontSize = 10.sp)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GlassTokens.Fill)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = XqColors.TextTertiary, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = XqColors.TextPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(XqColors.AccentSoft),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
