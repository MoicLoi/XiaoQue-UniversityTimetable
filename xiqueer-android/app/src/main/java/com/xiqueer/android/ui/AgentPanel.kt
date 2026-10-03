package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import com.xiqueer.android.AgentUiState
import com.xiqueer.android.ChatTurn
import com.xiqueer.android.agent.PendingAction
import com.xiqueer.android.agent.Risk
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens

/**
 * 常驻的可展开对话框(P6)。
 *
 * 为什么不做成独立页面:用户问的多半是"今天有什么课""这门课在哪",
 * 答案是**看着课表的上下文**,来回切页会把上下文切碎。所以它浮在当前页之上,
 * 折叠时是一个小圆钮,展开时占下半屏。
 */
@Composable
fun BoxScope.AgentPanel(
    state: AgentUiState,
    configured: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismissPending: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!state.open) {
        // 折叠态:右下角小圆钮
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 84.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(50))
                .background(XqColors.Accent.copy(alpha = 0.30f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = XqColors.AccentSoft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }

    GlassSurface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // 边到边绘制 + adjustResize 下,系统不会自动把内容抬到键盘之上,
            // 不加 imePadding 输入框会被软键盘盖住(实测踩过)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("助手", color = XqColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if (state.status.isNotEmpty()) {
                    Text(state.status, color = XqColors.TextTertiary, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                if (state.busy) {
                    CircularProgressIndicator(Modifier.height(14.dp).width(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                SmallPill("设置", onOpenSettings)
                Spacer(Modifier.width(6.dp))
                SmallPill("收起", onClose)
            }

            Spacer(Modifier.height(10.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 300.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassTokens.Fill)
                    .padding(10.dp),
            ) {
                val scroll = rememberScrollState()
                LaunchedEffect(state.turns.size, state.pending) {
                    scroll.animateScrollTo(scroll.maxValue)
                }
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    if (state.turns.isEmpty()) {
                        Text(
                            if (configured) "问点什么吧 —— 比如「今天有什么课」「这周课表」「我的成绩」。"
                            else "还没配置 AI。点右上角「设置」填入 API Key 后就能用了。" +
                                "用之前请先了解:开启后你的问题会发给你自己配置的模型服务。",
                            color = XqColors.TextTertiary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                    state.turns.forEach { Bubble(it) }
                }
            }

            val pending = state.pending
            if (pending != null) {
                Spacer(Modifier.height(10.dp))
                ConfirmCard(pending, onConfirm, onDismissPending)
            }

            Spacer(Modifier.height(10.dp))

            var input by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(GlassTokens.Fill)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (input.isEmpty()) {
                        Text("问点什么…", color = XqColors.TextTertiary, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = XqColors.TextPrimary,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(XqColors.AccentSoft),
                        // 允许多行,但要封顶 —— 否则每按一次回车输入框就长高一行,把面板挤没
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (state.busy) GlassTokens.Fill else XqColors.Accent.copy(alpha = 0.26f),
                        )
                        .clickable(enabled = !state.busy) {
                            val text = input
                            input = ""
                            onSend(text)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "发送",
                        color = if (state.busy) XqColors.TextTertiary else XqColors.AccentSoft,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val fg = when (turn.role) {
        "user" -> XqColors.AccentSoft
        "error" -> XqColors.Danger
        "note" -> XqColors.TextTertiary
        else -> XqColors.TextSecondary
    }
    // Box 的 contentAlignment 要二维对齐(Alignment.CenterEnd),不能用 Alignment.End
    val alignment = if (turn.role == "user") Alignment.CenterEnd else Alignment.CenterStart
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = alignment) {
        Text(
            turn.text,
            color = fg,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (turn.role == "user") XqColors.Accent.copy(alpha = 0.16f) else GlassTokens.Fill,
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

/**
 * 写操作的确认卡。
 *
 * 这里是**唯一的提交口**:按钮点下去才会真的发请求。
 * 不可逆操作(选课)用红色描边 + 明确的"不可撤回"字样。
 */
@Composable
private fun ConfirmCard(action: PendingAction, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val danger = action.risk == Risk.Irreversible
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) XqColors.Danger.copy(alpha = 0.12f) else GlassTokens.Fill)
            .padding(12.dp),
    ) {
        Text(
            action.title,
            color = if (danger) XqColors.Danger else XqColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(action.summary, color = XqColors.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            action.requestPreview,
            color = XqColors.TextTertiary,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(GlassTokens.Fill)
                .padding(8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassTokens.Fill)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", color = XqColors.TextSecondary, fontSize = 13.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (danger) XqColors.Danger.copy(alpha = 0.30f)
                        else XqColors.Accent.copy(alpha = 0.30f),
                    )
                    .clickable(onClick = onConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (danger) "确认执行(不可撤回)" else "确认执行",
                    color = if (danger) XqColors.Danger else XqColors.AccentSoft,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SmallPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassTokens.FillStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = XqColors.TextSecondary, fontSize = 11.sp)
    }
}
