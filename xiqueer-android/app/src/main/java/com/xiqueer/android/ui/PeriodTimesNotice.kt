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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens

/**
 * 首次启动的作息表提示。
 *
 * **不是让用户"确认"一份预填值** —— 而是如实告知:学校没有提供作息时间,
 * 所以现在只能按**节次**提醒(如「第 1-2 节」),无法在上课前精确提醒;
 * 想要精确提醒,需要一份自己学校的作息表。
 *
 * 我们**不编造** 1–12 节的默认时间:各校差异很大(有的 8:00 开始、有的 8:30,
 * 有的两节连排 90 分钟),写死等于让所有学校的提醒都错。
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.PeriodTimesNotice(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            "还没配置作息时间",
            color = XqColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        Text(
            "学校接口没有提供每节课的上下课时间,所以现在只能按【节次】提醒 —— " +
                "比如「第 1-2 节 · 厚德楼-H502」,不会有「20 分钟后上课」这种精确提醒。",
            color = XqColors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "每天早上的课表摘要不受影响,照常推送。",
            color = XqColors.TextTertiary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )

        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTokens.Fill)
                .padding(12.dp),
        ) {
            Text("配上作息表后可以:", color = XqColors.TextTertiary, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Bullet("课前 N 分钟精确提醒")
            Bullet("课表与详情里显示每节的上下课时间")
            Bullet("早间摘要带上时间点")
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassTokens.Fill)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("知道了", color = XqColors.TextSecondary, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(XqColors.Accent.copy(alpha = 0.26f))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Text("去填作息", color = XqColors.AccentSoft, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "也可以直接把作息表发给助手,让它帮你写进去",
            color = XqColors.TextTertiary,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text("·", color = XqColors.AccentSoft, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(text, color = XqColors.TextSecondary, fontSize = 12.sp)
    }
}
