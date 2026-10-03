package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens

private fun Map<*, *>.s(k: String) = this[k]?.toString().orEmpty()

@Composable
fun NoticesScreen(
    notices: List<Map<String, Any?>>,
    modifier: Modifier = Modifier,
    onOpen: (Map<String, Any?>) -> Unit,
) {
    if (notices.isEmpty()) {
        EmptyState("暂无通知", modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(notices.size) { i -> NoticeCard(notices[i], onOpen) }
    }
}

@Composable
private fun NoticeCard(n: Map<String, Any?>, onOpen: (Map<String, Any?>) -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(n) },
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                n.s("title").ifEmpty { "(无标题)" },
                color = XqColors.TextPrimary,
                fontSize = 14.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(n.s("systemmc").ifEmpty { "通知" })
                Spacer(Modifier.width(8.dp))
                Text(
                    listOf(n.s("publish_time"), n.s("editor")).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = XqColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(XqColors.Accent.copy(alpha = 0.20f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = XqColors.AccentSoft, fontSize = 10.sp)
    }
}

/** 通知详情浮层。`detail` 为 null 表示还在加载。 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.NoticeDetailOverlay(
    detail: Map<String, Any?>?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    GlassSheet(visible = loading || detail != null, onDismiss = onDismiss) {
        val d = detail
        if (d == null) {
            Text("加载中…", color = XqColors.TextSecondary, fontSize = 13.sp)
            return@GlassSheet
        }
        Text(
            d.s("title").ifEmpty { "(无标题)" },
            color = XqColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            listOf(d.s("publish_time"), d.s("editor")).filter { it.isNotEmpty() }.joinToString(" · "),
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                d.s("content").ifEmpty { "(无正文)" },
                color = XqColors.TextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 20.sp,
            )
        }

        val fjs = (d["fj"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        if (fjs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("附件", color = XqColors.TextTertiary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            fjs.forEach { fj ->
                val name = fj["filename"]?.toString().orEmpty()
                val path = fj["filepath"]?.toString().orEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(GlassTokens.Fill)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(name.ifEmpty { "附件" }, color = XqColors.TextSecondary, fontSize = 12.sp)
                    Text("校内地址", color = XqColors.TextTertiary, fontSize = 10.sp)
                }
                if (path.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "点空白处关闭",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
