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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xiqueer.android.data.Shift
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import java.time.LocalDate

/**
 * 调休 / 换课管理。
 *
 * 两条入口都留着:主要路径是**直接跟助手说**("9月28的课挪到9月20"),
 * 这个面板负责查看与撤销,顺便给不想用 AI 的人一个手填的地方。
 *
 * ⚠️ 这里改的是**本机覆盖层**,不上传学校 —— 所以"撤销"永远有效,
 * 也不存在"改坏了学校的数据"这种后果。
 */
@Composable
fun BoxScope.ShiftSheet(
    visible: Boolean,
    shifts: List<Shift>,
    onAdd: (String, String, List<String>?) -> String,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("调休 / 换课", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "把某一天的课挪到另一天。只改本机显示与提醒,不会动学校的课表。" +
                "也可以直接跟助手说,例如「9月28的课挪到9月20」。",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxWidth().height(280.dp).verticalScroll(rememberScrollState())) {
            if (shifts.isEmpty()) {
                Text("还没有任何调休", color = XqColors.TextTertiary, fontSize = 12.sp)
            } else {
                shifts.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GlassTokens.Fill)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(s.describe(), color = XqColors.TextPrimary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(GlassTokens.FillStrong)
                                .clickable { onRemove(s.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("撤销", color = XqColors.TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Section("手动添加")
        var from by remember { mutableStateOf("") }
        var to by remember { mutableStateOf("") }
        var course by remember { mutableStateOf("") }
        var message by remember { mutableStateOf<String?>(null) }

        DateField("原定上课日期(yyyy-MM-dd)", from) { from = it }
        DateField("改到哪一天(yyyy-MM-dd)", to) { to = it }
        DateField("只挪这门课(留空 = 整天都挪)", course) { course = it }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(XqColors.Accent.copy(alpha = 0.26f))
                    .clickable {
                        message = onAdd(from.trim(), to.trim(), course.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) })
                        from = ""; to = ""; course = ""
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("添加调休", color = XqColors.AccentSoft, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(GlassTokens.FillStrong)
                    .clickable {
                        // 一键填今天 → 明天,省得手打日期
                        from = LocalDate.now().toString()
                        to = LocalDate.now().plusDays(1).toString()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("填今天→明天", color = XqColors.TextSecondary, fontSize = 11.sp)
            }
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = XqColors.TextTertiary, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = XqColors.AccentSoft,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
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
                Text("2026-09-28", color = XqColors.TextTertiary, fontSize = 12.sp)
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
