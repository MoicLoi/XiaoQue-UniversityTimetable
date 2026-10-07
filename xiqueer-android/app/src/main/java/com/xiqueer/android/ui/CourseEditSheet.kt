package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.XqFeatures

/**
 * 「课节改动」—— 改**某一节课**的教室 / 节次。
 *
 * 定位的粒度是"这一节":第几周 + 星期几 + 哪门课。别的周次、别的星期几都不动。
 *
 * 两条硬规则:
 * 1. 改的是**本机覆盖层**,不上传学校 —— 所以随时可以恢复,不存在"改坏了学校数据";
 * 2. 「恢复到原课节位置」**只在真的和服务端不一样时才显示** ——
 *    没改过却摆一个恢复按钮会让人以为当前显示的不是原始数据。
 */
@Composable
fun BoxScope.CourseEditSheet(
    visible: Boolean,
    course: Course?,
    week: Int,
    weekday: Int,
    /** 当前已存的覆写(没有则 null)。 */
    override: CourseOverride?,
    /** 保存改动。返回人话回执。 */
    onSave: (room: String?, periods: String?) -> String,
    /** 恢复到原课节位置。返回人话回执。 */
    onRestore: () -> String,
    onDismiss: () -> Unit,
) {
    if (course == null) return

    // 与服务端值的比对 —— 「恢复」按钮的显示条件就是它
    val effRoom = override?.room ?: course.room
    val effPeriods = override?.periods ?: course.periods
    val changed = effRoom != course.room || effPeriods != course.periods

    GlassSheet(visible = visible, onDismiss = onDismiss) {
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

        // 表单初值 = 当前生效值。面板每次打开都重新对齐,
        // 否则上一次没保存的输入会留在下一次打开的面板里。
        var room by remember(visible, override?.id, course.code) { mutableStateOf(effRoom) }
        var periods by remember(visible, override?.id, course.code) { mutableStateOf(effPeriods) }
        var message by remember(visible) { mutableStateOf<String?>(null) }
        LaunchedEffect(visible) { if (visible) message = null }

        EditField("教室", room, course.room.ifEmpty { "原值留空" }) { room = it }
        EditField("节次", periods, course.periods) { periods = it }
        Text(
            "节次写成区间,如 3-4 或 1-4;填回原值即视为不改。",
            color = XqColors.TextTertiary,
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // 与服务端不一致时,才把"原值"和"恢复"摆出来
        if (changed) {
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
                        (if (course.room.isNotBlank()) " · ${course.room}" else ""),
                    color = XqColors.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(XqColors.Accent.copy(alpha = 0.26f))
                    .clickable {
                        message = onSave(
                            room.trim().takeIf { it.isNotEmpty() },
                            periods.trim().takeIf { it.isNotEmpty() },
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
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = XqColors.TextTertiary, fontSize = 11.sp, lineHeight = 16.sp)
        }
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
            if (value.isEmpty()) {
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
