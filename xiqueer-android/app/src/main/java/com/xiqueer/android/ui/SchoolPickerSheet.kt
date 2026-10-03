package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.data.School
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens

/**
 * 学校选择。
 *
 * 登录页**不再让用户看到学校代码**:用户输的是校名(或拼音/首字母),
 * 选中的是「辽宁中医药大学」这样的名字,`xxdm` 只是它背后的一串数字。
 *
 * 名单来自 `getAgent`,本地搜索 —— 所以断网也能选(只要拉过一次)。
 */
@Composable
fun BoxScope.SchoolPickerSheet(
    visible: Boolean,
    schools: List<School>,
    loading: Boolean,
    error: String?,
    /** 本地搜索(由数据层的 [com.xiqueer.android.data.SchoolDirectory.search] 提供)。 */
    search: (String) -> List<School>,
    onDismiss: () -> Unit,
    onPick: (School) -> Unit,
    onRetry: () -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("选择学校", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "输中文名、全拼或首字母都行,例如「辽宁中医」「liaoning」「lnzyy」",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))

        var query by remember { mutableStateOf("") }
        // 整个"输入框"都能点中并聚焦,而不是只有文字那一小块。
        // BasicTextField 的可点区域本来只有文字范围,贴在框边点会落空(以前还会关掉面板)。
        val focus = remember { FocusRequester() }
        val boxInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTokens.Fill)
                .clickable(interactionSource = boxInteraction, indication = null) {
                    focus.requestFocus()
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) {
                Text("搜索学校", color = XqColors.TextTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = XqColors.TextPrimary,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(XqColors.AccentSoft),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }

        Spacer(Modifier.height(10.dp))

        // 搜索与过滤都在本地:几百条数据,每次输入重算的开销可以忽略
        val results = remember(query, schools) {
            if (query.isBlank()) schools.take(60) else search(query)
        }

        when {
            loading && schools.isEmpty() -> Hint("正在获取学校名单…")
            error != null && schools.isEmpty() -> {
                Hint(error)
                Row2("重试", onRetry)
            }
            results.isEmpty() -> Hint(if (schools.isEmpty()) "还没有学校名单,点重试获取" else "没有匹配的学校")
            else -> {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(results, key = { it.xxdm }) { s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(GlassTokens.Fill)
                                .clickable { onPick(s) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(s.xxmc, color = XqColors.TextPrimary, fontSize = 13.5.sp)
                            if (s.pinyin.isNotEmpty()) {
                                Text(
                                    s.pinyin,
                                    color = XqColors.TextTertiary,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Hint(
                    if (query.isBlank()) "共 ${schools.size} 所学校(本地搜索,断网也可用)"
                    else "匹配 ${results.size} 条",
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = XqColors.TextTertiary, fontSize = 11.sp, lineHeight = 16.sp)
}

@Composable
private fun Row2(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassTokens.FillStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = XqColors.TextSecondary, fontSize = 12.sp)
    }
}
