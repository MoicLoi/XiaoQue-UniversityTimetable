package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens

/**
 * 顶栏「更多」菜单。
 *
 * 存在的理由:底栏被「日程」占了位置,通知这类不常用的入口需要有个去处。
 * 放在这里还有一个好处 —— 以后加「关于」「检查更新」之类不必再动底栏。
 */
@Composable
fun BoxScope.MoreMenuSheet(
    visible: Boolean,
    versionLabel: String,
    noticeCount: Int,
    shiftCount: Int,
    /** 已配置的自定义时段(晚自习)条数,0 = 未开启。 */
    selfStudyCount: Int = 0,
    onNotices: () -> Unit,
    onShifts: () -> Unit,
    onSelfStudy: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("更多", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        MenuRow(
            title = "教务通知",
            desc = if (noticeCount > 0) "已加载 $noticeCount 条" else "学校发布的通知与公告",
            onClick = onNotices,
        )
        Spacer(Modifier.height(8.dp))
        MenuRow(
            title = "调休 / 换课",
            desc = if (shiftCount > 0) "已记录 $shiftCount 条" else "把某天的课挪到另一天(也可直接跟助手说)",
            onClick = onShifts,
        )
        Spacer(Modifier.height(8.dp))
        MenuRow(
            title = "晚自习",
            desc = if (selfStudyCount > 0) "已开启 $selfStudyCount 条自定义时段" else "在时间轴上加一节自定义时段(会进日程与提醒)",
            onClick = onSelfStudy,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "小鹊课表 · $versionLabel\n非官方客户端,与学校及厂商无隶属关系",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun MenuRow(title: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTokens.Fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(title, color = XqColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = XqColors.TextTertiary, fontSize = 11.sp)
        }
    }
}
