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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens

/**
 * 「上课携带」编辑页。
 *
 * 需求:日程页要显示每门课要带的教材/物品,而**这个信息只能用户手写** ——
 * 没有任何接口知道你们老师要求带白大褂还是计算器。
 *
 * 单独一页而不是塞进设置面板:一个学期十几门课,十几行输入框会把设置页撑爆。
 * 键是**课程名**:同一门课不同周次带的东西一样。
 */
@Composable
fun BoxScope.CourseItemsSheet(
    visible: Boolean,
    courseNames: List<String>,
    items: Map<String, String>,
    onSet: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("上课携带", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "写一次就会显示在日程页的对应课程上。留空表示不提示。",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (courseNames.isEmpty()) {
            Text("课表还没加载出来,先去课表页刷新一次", color = XqColors.TextTertiary, fontSize = 12.sp)
            return@GlassSheet
        }

        val filled = courseNames.count { !items[it].isNullOrBlank() }
        Text("已填 $filled / ${courseNames.size} 门", color = XqColors.TextTertiary, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(courseNames, key = { it }) { name ->
                ItemRow(
                    name = name,
                    initial = items[name].orEmpty(),
                    onSet = { onSet(name, it) },
                )
            }
        }
    }
}

@Composable
private fun ItemRow(name: String, initial: String, onSet: (String) -> Unit) {
    // 本地草稿:输入过程中不写盘,点「保存」才落 —— 否则每敲一个字都写一次 prefs
    var draft by remember(name, initial) { mutableStateOf(initial) }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(name, color = XqColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlassTokens.Fill)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (draft.isEmpty()) {
                    Text("如:教材、白大褂、计算器", color = XqColors.TextTertiary, fontSize = 12.sp)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = XqColors.TextPrimary,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(XqColors.AccentSoft),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            val dirty = draft != initial
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (dirty) XqColors.Accent.copy(alpha = 0.26f) else GlassTokens.FillStrong,
                    )
                    .clickable(enabled = dirty) { onSet(draft) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    if (dirty) "保存" else "已存",
                    color = if (dirty) XqColors.AccentSoft else XqColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
