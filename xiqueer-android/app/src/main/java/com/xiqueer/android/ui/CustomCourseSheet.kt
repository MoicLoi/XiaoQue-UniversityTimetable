package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.xiqueer.android.data.CustomCourse
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.protocol.XqFeatures
import java.time.LocalDate

/**
 * 临时课程(紧急调换 / 开会)。
 *
 * 三条来自需求的口径,写在这里免得以后走偏:
 *
 * 1. **两种生效范围**:开会多数是**临时通知**来的 → 默认「只这一次(今天)」;
 *    每周固定的那一类(每周三下午的例会)也支持,配置入口就是这里。
 * 2. **名称可以索引服务器侧现有课程**:候选项来自底表(`courseNames`,由 ViewModel 给)。
 *    选中同名课程后,**配色**与**「要带什么」自动继承** —— 它们本来就是按课名索引的
 *    ([com.xiqueer.android.CourseColors] / `CourseItemsStore`),不需要额外机制;
 *    这里额外做的只有一件:把该课的教师 / 教室作为默认值填进表单(仍可改)。
 * 3. **不参与调休** —— 见 `ScheduleOverrides`:自定义层在调出/调来两步都被跳过。
 */
@Composable
fun BoxScope.CustomCourseSheet(
    visible: Boolean,
    courses: List<CustomCourse>,
    /** 底表现有的课程名 —— 名称索引的候选,来自服务器课表。 */
    serverCourseNames: List<String> = emptyList(),
    /** 选中某个已有课程名时,拿它的教师/教室当默认值。 */
    lookup: (String) -> com.xiqueer.protocol.Course? = { null },
    /** 覆盖式保存。返回人话回执。 */
    onSave: (List<CustomCourse>) -> String,
    onRemove: (String) -> String = { "" },
    onDismiss: () -> Unit,
) {
    val today = remember(visible) { LocalDate.now().toString() }
    var editing by remember(visible) { mutableStateOf<CustomCourse?>(null) }
    var name by remember(visible, editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    var room by remember(visible, editing?.id) { mutableStateOf(editing?.room.orEmpty()) }
    var teacher by remember(visible, editing?.id) { mutableStateOf(editing?.teacher.orEmpty()) }
    var byPeriods by remember(visible, editing?.id) { mutableStateOf(editing?.byPeriods ?: true) }
    var periods by remember(visible, editing?.id) { mutableStateOf(editing?.periods ?: "3-4") }
    var start by remember(visible, editing?.id) { mutableStateOf(editing?.start ?: "14:00") }
    var end by remember(visible, editing?.id) { mutableStateOf(editing?.end ?: "15:30") }
    var oneOff by remember(visible, editing?.id) { mutableStateOf(editing?.oneOff ?: true) }
    var date by remember(visible, editing?.id) { mutableStateOf(editing?.date ?: today) }
    var days by remember(visible, editing?.id) {
        mutableStateOf(editing?.weekdays ?: listOf(2))
    }
    var message by remember(visible) { mutableStateOf<String?>(null) }
    LaunchedEffect(visible) { if (visible) message = null }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                // 字段比晚自习多,小屏会顶出屏幕 —— 面板自己能滚
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("临时课程", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "紧急调换、临时开会这类事学校系统里没有,但你要在课表上看见、要能提醒。" +
                    "默认只算今天这一次;每周固定的也可以在这里配置。它不参与调休。",
                color = XqColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))

            if (courses.isNotEmpty()) {
                Text("已有 ${courses.size} 条", color = XqColors.TextTertiary, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                courses.forEach { c ->
                    ExistsRow(
                        c = c,
                        onEdit = { editing = c },
                        onRemove = { message = onRemove(c.id) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Field("名称(必填)", name) { name = it }
            if (serverCourseNames.isNotEmpty()) {
                Text("从学校课表里挑一门(同名即继承配色与「要带什么」)", color = XqColors.TextTertiary, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    serverCourseNames.forEach { n ->
                        val on = n == name
                        Pill(n, on) {
                            name = n
                            // 选中已有课程:把它的教师/教室当默认值填进来(仍可改)
                            lookup(n)?.let { c ->
                                if (c.room.isNotBlank()) room = c.room
                                if (c.teacher.isNotBlank()) teacher = c.teacher
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { Field("地点", room) { room = it } }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { Field("教师", teacher) { teacher = it } }
            }

            Text("时段", color = XqColors.TextTertiary, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("按首尾节", byPeriods) { byPeriods = true }
                Pill("按起止时间", !byPeriods) { byPeriods = false }
            }
            Spacer(Modifier.height(6.dp))
            if (byPeriods) {
                Field("首尾节(如 3-4)", periods) { periods = it }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { Field("开始(HH:mm)", start) { start = it } }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) { Field("结束(HH:mm)", end) { end = it } }
                }
            }

            Text("生效范围", color = XqColors.TextTertiary, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("只这一次", oneOff) { oneOff = true }
                Pill("每周重复", !oneOff) { oneOff = false }
            }
            Spacer(Modifier.height(6.dp))
            if (oneOff) {
                Field("日期(yyyy-MM-dd)", date) { date = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("今天", date == today) { date = today }
                    Pill("明天", date == LocalDate.now().plusDays(1).toString()) {
                        date = LocalDate.now().plusDays(1).toString()
                    }
                }
                Spacer(Modifier.height(6.dp))
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0..6) {
                        val on = d in days
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(if (on) XqColors.Accent.copy(alpha = 0.30f) else GlassTokens.Fill)
                                .clickable { days = if (on) days - d else (days + d).distinct().sorted() }
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
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(XqColors.Accent.copy(alpha = 0.26f))
                        .clickable {
                            val made = CustomCourse.of(
                                name = name,
                                room = room,
                                teacher = teacher,
                                periods = if (byPeriods) periods else null,
                                start = if (byPeriods) null else start.trim(),
                                end = if (byPeriods) null else end.trim(),
                                weekdays = if (oneOff) emptyList() else days,
                                date = if (oneOff) date.trim() else null,
                                id = editing?.id ?: "cc-${System.currentTimeMillis()}",
                            )
                            message = if (made == null) {
                                "保存失败:名称必填;按时间填时要是 HH:mm 且结束晚于开始;" +
                                    "选「每周重复」时至少要勾一个星期"
                            } else {
                                // 保留数据里原有的其它条目,只替换正在编辑的那一条
                                val rest = if (editing == null) courses else courses.filter { it.id != editing!!.id }
                                onSave(rest + made)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (editing == null) "添加" else "保存修改", color = XqColors.AccentSoft, fontSize = 12.sp)
                }
                if (editing != null) {
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(GlassTokens.FillStrong)
                            .clickable { editing = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("取消编辑", color = XqColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = XqColors.TextTertiary, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

/** 已有条目一行:点一下载入表单继续改,右边的「删」直接删。 */
@Composable
private fun ExistsRow(c: CustomCourse, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassTokens.Fill)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
            Text(c.name, color = XqColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "${c.timeLabel()} · ${c.scopeLabel()}" + if (c.room.isNotBlank()) " · ${c.room}" else "",
                color = XqColors.TextTertiary,
                fontSize = 10.sp,
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(GlassTokens.FillStrong)
                .clickable(onClick = onRemove)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text("删", color = XqColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

/** 可选中的小胶囊(单选/开关都用它,免得两处样式长歪)。 */
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
