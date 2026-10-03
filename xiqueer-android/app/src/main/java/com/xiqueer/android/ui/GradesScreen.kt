package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.protocol.GradeRow

@Composable
fun GradesScreen(rows: List<GradeRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) {
        EmptyState("暂无成绩记录\n(新生或该学期未公布成绩时属正常)", modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rows.size) { i -> GradeCard(rows[i]) }
    }
}

@Composable
private fun GradeCard(row: GradeRow) {
    val entries = extractEntries(row)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(XqColors.GlassFill)
            .border(1.dp, XqColors.GlassStroke, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(
            row.termName.ifEmpty { row.term },
            color = XqColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            // 接口字段随学校配置变化,拿不到明细时原样展示,便于后续适配
            Text("无明细字段", color = XqColors.TextTertiary, fontSize = 12.sp)
        } else {
            entries.forEach { (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(k, color = XqColors.TextSecondary, fontSize = 13.sp)
                    Text(v, color = XqColors.TextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * 成绩明细的字段名各校不同,这里做一次宽松提取:
 * 找响应里的第一个对象数组,把每个对象的前若干个标量字段拉平展示。
 */
private fun extractEntries(row: GradeRow): List<Pair<String, String>> {
    val raw = row.raw
    val list = sequenceOf("resultset", "resultSet", "list", "cjList", "data")
        .mapNotNull { key -> (raw[key] as? List<*>) }
        .firstOrNull { it.isNotEmpty() }
        ?: return emptyList()

    val first = list.firstOrNull() as? Map<*, *> ?: return emptyList()
    return first.entries
        .mapNotNull { (k, v) -> (k as? String)?.let { it to v.toString() } }
        .filter { (_, v) -> v.isNotBlank() && v != "null" }
        .take(12)
}
