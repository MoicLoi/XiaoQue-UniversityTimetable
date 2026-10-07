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
import com.xiqueer.android.data.SelfStudySlot
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.XqFeatures

/**
 * 晚自习设置。
 *
 * 晚自习**不是服务端课表的任何一节** —— 接口的节次只到白天的课。所以在本地定义它,
 * 并让它**被当成真的一节课**:占课表网格的一行、进日程页、参与上课提醒。
 *
 * 时间轴上的位置是"接在最后一节之后"而不是按时刻比例 —— 网格每一行等高,
 * 本身就不是按时间比例的(前 8 分钟的第 1 节和后 45 分钟的第 3 节一样高)。
 */
@Composable
fun BoxScope.SelfStudySheet(
    visible: Boolean,
    slots: List<SelfStudySlot>,
    /** 覆盖式保存。返回人话回执。 */
    onSave: (List<SelfStudySlot>) -> String,
    onDismiss: () -> Unit,
) {
    // 面板只编辑**第一条**;若数据里还有别的(比如 AI 写的),保存时原样保留。
    val editing = slots.firstOrNull()

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("晚自习", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "在时间轴上加一节自定义时段。它算作真的一节课:会出现在课表、日程里,也会提醒。",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))

        var label by remember(visible, editing?.id) { mutableStateOf(editing?.label ?: SelfStudySlot.DEFAULT_LABEL) }
        var start by remember(visible, editing?.id) { mutableStateOf(editing?.start ?: "19:00") }
        var end by remember(visible, editing?.id) { mutableStateOf(editing?.end ?: "20:30") }
        var days by remember(visible, editing?.id) {
            mutableStateOf(editing?.weekdays ?: listOf(0, 1, 2, 3, 4))
        }
        var message by remember(visible) { mutableStateOf<String?>(null) }
        LaunchedEffect(visible) { if (visible) message = null }

        Field("名称", label) { label = it }

        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { Field("开始(HH:mm)", start) { start = it } }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) { Field("结束(HH:mm)", end) { end = it } }
        }

        Text("启用星期", color = XqColors.TextTertiary, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 0..6) {
                val on = d in days
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) XqColors.Accent.copy(alpha = 0.30f) else GlassTokens.Fill)
                        .clickable {
                            days = if (on) days - d else (days + d).distinct().sorted()
                        }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        XqFeatures.weekdayName(d).removePrefix("周"),
                        color = if (on) XqColors.AccentSoft else XqColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(XqColors.Accent.copy(alpha = 0.26f))
                    .clickable {
                        val made = SelfStudySlot.of(
                            label = label,
                            start = start.trim(),
                            end = end.trim(),
                            weekdays = days,
                            id = editing?.id ?: "ss-${System.currentTimeMillis()}",
                        )
                        message = if (made == null) {
                            "保存失败:时间要写成 HH:mm,且结束要晚于开始"
                        } else {
                            // 保留数据里原有的其它时段,只替换正在编辑的第一条
                            onSave(listOf(made) + slots.drop(1))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("保存", color = XqColors.AccentSoft, fontSize = 12.sp)
            }
            if (editing != null) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GlassTokens.FillStrong)
                        .clickable { message = onSave(emptyList()) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("关闭晚自习", color = XqColors.TextSecondary, fontSize = 11.sp)
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
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
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
