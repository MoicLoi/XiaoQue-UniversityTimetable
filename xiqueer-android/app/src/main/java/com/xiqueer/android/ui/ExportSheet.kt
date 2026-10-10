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
 * 导出格式选择。
 *
 * 都走系统分享面板:用户可以"存到文件"、发微信、发邮件 —— 我们不知道也不用知道。
 *
 * 日历那一项在**既没填作息、也没有自带时钟的条目**时禁用并说明原因:不知道几点上课
 * 就排不出服务端课程的事件。但只要配了晚自习、或按时间填的临时课程,就仍然可用 ——
 * 它们的时间是用户自己填的,本身就是绝对时刻,不依赖作息表。
 * 早先这里只认作息表,结果把晚自习的日历导出也一并挡掉了。
 */
@Composable
fun BoxScope.ExportSheet(
    visible: Boolean,
    hasPeriodTimes: Boolean,
    /** 是否有自带绝对时刻的条目(晚自习 / 按时间填的临时课程)。见 `Overlays.hasOwnClock`。 */
    hasOwnClock: Boolean = false,
    onDismiss: () -> Unit,
    onImage: () -> Unit,
    onExcel: () -> Unit,
    onCsv: () -> Unit,
    onCalendar: () -> Unit,
) {
    val calendarOk = hasPeriodTimes || hasOwnClock
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text("导出课表", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "全部导出到本机,再通过系统分享面板保存或发送。不需要任何存储权限。",
            color = XqColors.TextTertiary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(14.dp))

        ExportOption(
            title = "图片(.png)",
            desc = "一整周课表画成一张图,直接发群里 —— 不用自己截图再裁状态栏",
        ) { onDismiss(); onImage() }

        ExportOption(
            title = "Excel(.xlsx)",
            desc = "两张表:①整周课表网格(连堂自动合并单元格)②课程明细,一行一门课",
        ) { onDismiss(); onExcel() }

        ExportOption(
            title = "CSV(.csv)",
            desc = "一行一门课的规整数据,方便自己筛选/导入别处;带 BOM,Excel 打开不乱码",
        ) { onDismiss(); onCsv() }

        ExportOption(
            title = "日历(.ics)",
            desc = when {
                hasPeriodTimes -> "每周每节课一个事件,导入手机日历后由系统提醒(提前 20 分钟)"
                hasOwnClock -> "只导出晚自习/临时课程事件;填了作息时间才会连白天的课一起导出"
                else -> "需要先填作息时间 —— 不知道几点上课就排不出日历事件"
            },
            enabled = calendarOk,
        ) { onDismiss(); onCalendar() }

        Spacer(Modifier.height(8.dp))
        Text(
            "课程明细字段:课程名 / 教师 / 教室 / 节次 / 周次 / 学分 / 课程号",
            color = XqColors.TextTertiary,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ExportOption(
    title: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) GlassTokens.Fill else GlassTokens.Fill.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                title,
                color = if (enabled) XqColors.TextPrimary else XqColors.TextTertiary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                desc,
                color = XqColors.TextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
