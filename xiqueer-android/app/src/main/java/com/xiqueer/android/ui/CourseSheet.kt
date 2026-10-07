package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.Course

/**
 * 课程详情浮层。
 *
 * 用**同窗口内**的浮层而不是 `ModalBottomSheet`:后者是独立 Window,
 * 采样不到主窗口录制的背景层,玻璃会失效。这里 scrim 和玻璃面都在同一棵树里,
 * 玻璃面采样的是 scrim **之前**的背景,所以观感是对的。
 */
@Composable
fun BoxScope.CourseDetailOverlay(
    course: Course?,
    times: PeriodTimes,
    /**
     * 这一节已存的课节覆写。
     *
     * ⚠️ 详情要显示**生效值**而不是底表值:网格上已经画着新教室 / 新节次了,
     * 点开却写着老值,用户会以为改动没生效。
     * 底表值仍然保留在下面那行"原:…"里,好让人知道改之前是什么。
     */
    override: com.xiqueer.android.data.CourseOverride? = null,
    onDismiss: () -> Unit,
) {
    if (course == null) return

    val interaction = remember { MutableInteractionSource() }

    val effRoom = override?.room ?: course.room
    val effPeriods = override?.periods ?: course.periods
    val changed = effRoom != course.room || effPeriods != course.periods

    // 遮罩:点击关闭
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C05070B))
            .clickable(interactionSource = interaction, indication = null) { onDismiss() },
    )

    GlassSurface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        shape = RoundedCornerShape(24.dp),
        tint = GlassTokens.FillStrong,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                course.name,
                color = XqColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
            )
            Spacer(Modifier.height(14.dp))

            DetailRow("教师", course.teacher)
            DetailRow("地点", effRoom.ifEmpty { course.roomRaw })
            DetailRow("节次", periodLabel(effPeriods, times))
            if (changed) {
                val orig = ArrayList<String>(2)
                if (effPeriods != course.periods) orig.add(periodLabel(course.periods, times))
                if (effRoom != course.room && course.room.isNotBlank()) orig.add(course.room)
                if (orig.isNotEmpty()) DetailRow("已改动", "原 " + orig.joinToString(" · "))
            }
            DetailRow("周次", course.weeks)
            DetailRow("学分", course.credit)
            if (course.note.isNotEmpty()) DetailRow("备注", course.note)
            if (course.code.isNotEmpty()) DetailRow("课程号", course.code)

            Spacer(Modifier.height(6.dp))
            Text(
                "点任意处关闭",
                color = XqColors.TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = XqColors.TextTertiary, fontSize = 13.sp)
        Spacer(Modifier.width(16.dp))
        Text(value, color = XqColors.TextPrimary, fontSize = 13.sp)
    }
}

/**
 * `"1-2"` -> `"第 1-2 节"`;配置了作息表才补 `"(08:00-09:40)"`。
 * 未配置时**不编造时间**。
 */
private fun periodLabel(jcxx: String, times: PeriodTimes): String {
    if (jcxx.isBlank()) return ""
    val parts = jcxx.split('-', '~', '－', '–').mapNotNull { it.trim().toIntOrNull() }
    if (parts.isEmpty()) return jcxx
    val s = parts.first()
    val e = parts.getOrElse(1) { s }
    return PeriodText.label(times, s, e)
}
