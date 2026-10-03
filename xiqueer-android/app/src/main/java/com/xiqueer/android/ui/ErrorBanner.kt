package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

/**
 * 登录之后出错的提示条。
 *
 * **为什么必须补这一块**:`fail()` 一直有把错误写进 `state.error`,
 * 但登录之后**没有任何界面渲染它** —— 只有登录页会把 error 显示出来。
 * 于是"服务端不认会话"这件事,在用户眼里就是"所有页面都空了,
 * 点刷新也没反应,还不告诉我为什么"。
 *
 * 真机上实测到的场景:服务端回 `errcode:-1 口令失败`(会话失效),
 * 课表有缓存所以看着正常,成绩/考试/通知全空,而屏幕上**一个字都没提示**。
 * 会话失效时这里会多给一个「重新登录」—— 用户自己就能修好,
 * 不用去猜是不是 App 坏了。
 */
@Composable
fun ErrorBanner(
    message: String?,
    sessionExpired: Boolean,
    onRelogin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (sessionExpired) XqColors.Accent.copy(alpha = 0.18f)
                else XqColors.Danger.copy(alpha = 0.16f),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (sessionExpired) "登录已过期" else "没拿到数据",
                    color = if (sessionExpired) XqColors.AccentSoft else XqColors.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 会话失效时上面已经说人话了,这里给原始错误供排查
                    if (sessionExpired) "服务端不再接受本机保存的凭据($message)。重新登录一次即可。" else message,
                    color = XqColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                color = XqColors.TextTertiary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (sessionExpired) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "重新登录",
                    color = XqColors.AccentSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(XqColors.Accent.copy(alpha = 0.30f))
                        .clickable(onClick = onRelogin)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}
