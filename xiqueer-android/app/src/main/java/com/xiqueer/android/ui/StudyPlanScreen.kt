package com.xiqueer.android.ui

import androidx.compose.foundation.background
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
import com.xiqueer.protocol.XqFeatures

/**
 * 培养方案。
 *
 * `oriHd_tsjyk/getPyfa` -> `resultSet.llkc[]`,按学期分组;每组 `kc[]` 是课程,
 * 字段:`kcmc`(带 `[课程号]` 前缀)、`xf`、`kclb`、`khfs`、`zxs`/`zhxs`/`js`/`sy`/`sj`。
 * `sjhj` 是实践环节,本校为空。
 */
@Composable
fun StudyPlanScreen(plan: Map<String, Any?>, modifier: Modifier = Modifier) {
    val llkc = (plan["llkc"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
    if (llkc.isEmpty()) {
        EmptyState("暂无培养方案", modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(llkc.size) { i ->
            val term = llkc[i]
            val xq = term["xq"]?.toString().orEmpty()
            val kc = (term["kc"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
            TermSection(xq, kc)
        }
    }
}

@Composable
private fun TermSection(xq: String, courses: List<Map<*, *>>) {
    val totalCredit = courses.sumOf { (it["xf"]?.toString()?.toDoubleOrNull() ?: 0.0) }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    xq.ifEmpty { "未标注学期" },
                    color = XqColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${courses.size} 门 · ${trimZero(totalCredit)} 学分",
                    color = XqColors.TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            courses.forEach { c ->
                val name = c["kcmc"]?.toString().orEmpty()
                val (clean, code) = XqFeatures.splitCourseName(name)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassTokens.Fill)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            clean,
                            color = XqColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${c["xf"]?.toString().orEmpty()} 学分",
                            color = XqColors.AccentSoft,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOf(
                            c["kclb"]?.toString().orEmpty(),
                            c["khfs"]?.toString().orEmpty(),
                            "总 ${c["zxs"]?.toString().orEmpty()} 学时",
                            "理论 ${c["zhxs"]?.toString().orEmpty()}",
                        ).filter { it.isNotBlank() && !it.endsWith("  ") }.joinToString(" · "),
                        color = XqColors.TextTertiary,
                        fontSize = 11.sp,
                    )
                    if (code.isNotEmpty()) {
                        Text(code, color = XqColors.TextTertiary, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun trimZero(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

@Composable
private fun EmptyPlanBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = XqColors.TextTertiary, fontSize = 14.sp)
    }
}
