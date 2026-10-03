package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens

// 用 Map<*, *> 作接收者:接口返回里很多是嵌套的抽象 Map
private fun Map<*, *>.s(k: String) = this[k]?.toString().orEmpty()
private fun Map<*, *>.lst(k: String) = (this[k] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()

/**
 * 考试安排。
 *
 * `oriKsap/list` 给学期 + 轮次;`oriKsap/detail` 给排考明细。
 * 本校该生当前**没有排考**(`ksap` 为空数组),所以空状态是正常的 —— 页面上直接说明,
 * 避免误以为是 bug。
 */
@Composable
fun ExamsScreen(
    terms: List<Map<String, Any?>>,
    selectedTerm: String?,
    selectedRound: String?,
    items: List<Map<String, Any?>>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onSelectTerm: (String) -> Unit,
    onSelectRound: (String) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (terms.isEmpty()) {
            if (loading) EmptyState("加载中…", modifier) else EmptyState("暂无考试安排", modifier)
            return@Column
        }

        // 学期选择
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            terms.forEach { t ->
                val dm = t.s("dm")
                SelectChip(
                    label = t.s("mc").ifEmpty { dm },
                    selected = dm == selectedTerm,
                    onClick = { onSelectTerm(dm) },
                )
            }
        }

        // 轮次选择
        val rounds = terms.firstOrNull { it.s("dm") == selectedTerm }?.let { lstOf(it, "kslc") } ?: emptyList()
        if (rounds.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rounds.forEach { r ->
                    val lcdm = r.s("lcdm")
                    SelectChip(
                        label = r.s("lcmc").ifEmpty { "轮次 $lcdm" },
                        selected = lcdm == selectedRound,
                        onClick = { onSelectRound(lcdm) },
                        small = true,
                    )
                }
            }
        }

        when {
            loading -> EmptyState("加载中…")
            items.isEmpty() -> EmptyState("该轮次暂无排考\n(未排考或成绩已归档时属正常)")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items.size) { i -> ExamCard(items[i]) }
            }
        }
    }
}

private fun lstOf(m: Map<String, Any?>, k: String) = m.lst(k)

@Composable
private fun ExamCard(e: Map<String, Any?>) {
    val start = e.s("kssjqs").ifEmpty { e.s("kssj") }
    val end = e.s("kssjjs")
    val whenText = listOf(start, end).filter { it.isNotEmpty() }.joinToString(" ~ ")

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                e.s("kcmc").ifEmpty { "(未知课程)" },
                color = XqColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            DetailLine("时间", whenText)
            DetailLine("地点", e.s("ksdd"))
            DetailLine("座位", e.s("zwh"))
            DetailLine("形式", e.s("ksxz").ifEmpty { e.s("khfs") })
            DetailLine("轮次", e.s("lc"))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = XqColors.TextTertiary, fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = XqColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit, small: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) XqColors.Accent.copy(alpha = 0.26f) else GlassTokens.Fill)
            .clickable(onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 5.dp else 7.dp),
    ) {
        Text(
            label,
            color = if (selected) XqColors.AccentSoft else XqColors.TextSecondary,
            fontSize = if (small) 11.sp else 12.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
