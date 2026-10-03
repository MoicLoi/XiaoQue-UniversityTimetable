package com.xiqueer.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.xiqueer.android.agent.AgentConfig
import com.xiqueer.android.data.PeriodTimeSource
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.ui.glass.GlassSheet
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.android.watch.WatchConfig

/**
 * 设置面板。
 *
 * 四块内容刻意放在一起:它们都是"给提醒与监听喂参数"的事,分成四个页面只会让人找不到。
 *
 * 关于 AI 的措辞:**不写"你的数据是安全的"这种空话** —— 而是直说问题与回答会发到
 * 用户自己配置的模型服务,key 只存在本机。
 */
@Composable
fun BoxScope.SettingsSheet(
    visible: Boolean,
    agent: AgentConfig,
    periodTimes: PeriodTimes,
    leadMinutes: Int,
    reminderEnabled: Boolean,
    digestEnabled: Boolean,
    digestAt: String,
    watch: WatchConfig,
    watchPollCount: Int,
    /** 「上课携带」已填/总数,只为在入口行上显示个进度。 */
    itemsFilled: Int,
    itemsTotal: Int,
    /** 上课悬浮窗。 */
    overlayEnabled: Boolean,
    overlayAllowed: Boolean,
    onOverlay: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onDismiss: () -> Unit,
    onOpenItems: () -> Unit,
    /**
     * 保存 AI 配置。第二个参数是**新填的** API Key:
     * `null` = 没改动(加密存储里那份保持不动),空串 = 清掉。
     */
    onSaveAgent: (AgentConfig, String?) -> Unit,
    onSavePeriodTimes: (PeriodTimes) -> Unit,
    onReminder: (Boolean) -> Unit,
    onLead: (Int) -> Unit,
    onDigest: (Boolean, String) -> Unit,
    onWatch: (WatchConfig) -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("设置", color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))

            // ---------------- AI ----------------
            Section("AI 助手")
            Toggle("启用 AI 助手", agent.enabled) { onSaveAgent(agent.copy(enabled = it), null) }
            Hint("开启后,你的问题会连同工具查到的课表/成绩/通知内容,发给你自己配置的模型服务。")
            Field("接口地址", agent.baseUrl, "https://api.deepseek.com/v1") {
                onSaveAgent(agent.copy(baseUrl = it), null)
            }
            // API Key 存在加密存储里,**界面从不回显** —— 只显示配没配,要换就整条覆盖。
            // 这里不套 mask:反正框里永远只有"你刚敲进去的新 Key",
            // 而 Field 的 mask 实现会让输入框变只读(得先点「显示」),反而更难用。
            Field(
                label = if (agent.keyPresent) "API Key(已配置,填新的可覆盖)" else "API Key(未配置)",
                value = "",
                placeholder = if (agent.keyPresent) "已加密保存在本机" else "sk-…",
                mask = false,
            ) { entered ->
                // 没打字就当作"没改";打了字(或点了清空)才写入
                onSaveAgent(agent, entered.ifBlank { if (agent.keyPresent) null else "" })
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Action("清除已存的 Key") { onSaveAgent(agent, "") }
            }
            Field("模型", agent.model, "deepseek-chat") {
                onSaveAgent(agent.copy(model = it), null)
            }
            Toggle("允许模型看到「提交选课」这类写操作", agent.exposeWriteTools) {
                onSaveAgent(agent.copy(exposeWriteTools = it), null)
            }
            Hint("即使打开,写操作也只会生成待确认的计划 —— 真正提交必须由你点确认。")
            Hint("Key 存在本机 Android Keystore 加密存储里(AES-256-GCM),明文不落盘、也不进备份。")

            Spacer(Modifier.height(16.dp))

            // ---------------- 上课悬浮窗 ----------------
            //
            // 刻意**默认关闭**、而且要点按钮去系统设置授权:
            // "显示在其他应用上层"是很重的权限,一进 App 就弹会被当成流氓软件。
            Section("上课悬浮窗")
            Hint("开启后,上课期间会在所有应用上层显示一个小卡片:课程名 + 距下课倒计时。只在课中出现,下课自动消失。")
            Toggle("开启悬浮窗", overlayEnabled, onOverlay)
            if (overlayEnabled && !overlayAllowed) {
                Hint("还需要系统授权「显示在其他应用上层」。点下面的按钮到系统设置里打开 —— 这个权限只能由你手动授予。")
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Action("去系统设置授权", accent = true, onClick = onRequestOverlayPermission)
                }
            } else if (overlayEnabled) {
                Hint("权限已授予。上课时会自动出现,不需要保持 App 在前台。")
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- 上课携带 ----------------
            Section("上课携带")
            Hint("日程页会显示每门课要带的东西。这个信息只能你自己写 —— 接口不知道你们老师要求带什么。")
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Action(
                    label = if (itemsTotal == 0) "还没加载课表" else "编辑携带清单(已填 $itemsFilled/$itemsTotal)",
                    accent = itemsTotal > 0,
                    onClick = onOpenItems,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- 提醒 ----------------
            Section("上课提醒")
            Toggle("课前提醒", reminderEnabled, onReminder)
            if (!periodTimes.configured) {
                Hint("当前没有作息时间,课前提醒不会触发(只能按节次提醒)。下面填入作息后会立即生效。")
            }
            Stepper("提前提醒(分钟)", leadMinutes, 5, 5, 120, onLead)
            Toggle("每天早上推送课表摘要", digestEnabled) { onDigest(it, digestAt) }
            Stepper("摘要时间(小时)", digestAt.substringBefore(':').toIntOrNull() ?: 7, 1, 0, 23) { h ->
                onDigest(digestEnabled, "%02d:%s".format(h, digestAt.substringAfter(':', "00")))
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- 作息表 ----------------
            Section("作息时间")
            Hint(
                "来源:" + periodTimes.source.label +
                    if (periodTimes.configured) "" else " —— 学校接口不提供每节课的时间,只能自己填," +
                    "或者让 AI 帮你从作息表导入(直接跟助手说就行)。",
            )
            var text by remember(periodTimes) {
                mutableStateOf(periodTimes.slots.joinToString(",") { it.toString() })
            }
            Field("每节课时间(逗号分隔,第 1 个即第 1 节)", text, "08:00-08:45,08:55-09:40") {
                text = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("保存作息", accent = true) {
                    val slots = PeriodTimes.parseSlots(text)
                    if (slots.isEmpty()) {
                        onSavePeriodTimes(PeriodTimes.Empty)
                    } else {
                        onSavePeriodTimes(PeriodTimes(PeriodTimeSource.Manual, slots))
                    }
                }
                Action("清空") { text = ""; onSavePeriodTimes(PeriodTimes.Empty) }
            }

            Spacer(Modifier.height(16.dp))

            // ---------------- 选课监听 ----------------
            Section("选课监听")
            Hint(
                "本校的选课接口走教务内网,公网查不到课程列表,所以监听看的是**间接信号**" +
                    "(通知里出现选课关键词等),命中时会明确标注「可能有」,不会替你下定论。",
            )
            Toggle("开启监听", watch.enabled) { onWatch(watch.copy(enabled = it)) }
            Stepper("最短间隔(分钟)", watch.minIntervalSec / 60, 1, 1, 30) {
                onWatch(watch.copy(minIntervalSec = it * 60))
            }
            Stepper("最长间隔(分钟)", watch.maxIntervalSec / 60, 1, 1, 60) {
                onWatch(watch.copy(maxIntervalSec = it * 60))
            }
            Stepper("静默开始(点)", watch.quietStartHour, 1, 0, 23) {
                onWatch(watch.copy(quietStartHour = it))
            }
            Stepper("静默结束(点)", watch.quietEndHour, 1, 0, 23) {
                onWatch(watch.copy(quietEndHour = it))
            }
            Stepper("最长连续监听(天)", watch.maxDays, 1, 1, 30) {
                onWatch(watch.copy(maxDays = it))
            }
            Hint(
                "防封设计:间隔随机、0–8 点不请求、最长连续 7 天自动停、随时可手动关。" +
                    "已检查 $watchPollCount 轮。" +
                    "注意:监听期间会常驻一条状态栏通知,这是 Android 对前台服务的要求。",
            )

            Spacer(Modifier.height(16.dp))
            Action("关闭", accent = true, onDismiss)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = XqColors.AccentSoft,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ColumnScope.Hint(text: String) {
    Text(
        text,
        color = XqColors.TextTertiary,
        fontSize = 10.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = XqColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) XqColors.Accent.copy(alpha = 0.40f) else GlassTokens.Fill)
                .clickable { onChange(!checked) },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .width(20.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (checked) XqColors.AccentSoft else XqColors.TextTertiary),
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    step: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = XqColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Action("−", onClick = { onChange((value - step).coerceAtLeast(min)) })
        Text(
            value.toString(),
            color = XqColors.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Action("+", onClick = { onChange((value + step).coerceAtMost(max)) })
    }
}

/** 单行输入:`onDone` 在该行失去焦点或回车时触发。 */
@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    mask: Boolean = false,
    onDone: (String) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    var revealed by remember { mutableStateOf(false) }
    // 整块"输入框"可点即聚焦 —— 与学校搜索框同理,BasicTextField 自身的可点区域
    // 只有文字那一小块,贴着框边点会落空(以前还会把设置面板关掉)。
    val focus = remember { FocusRequester() }
    val boxInteraction = remember { MutableInteractionSource() }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = XqColors.TextTertiary, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(GlassTokens.Fill)
                    .clickable(interactionSource = boxInteraction, indication = null) {
                        focus.requestFocus()
                    }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                val shown = if (mask && !revealed) "•".repeat(local.length.coerceAtMost(24)) else local
                if (shown.isEmpty()) {
                    Text(placeholder, color = XqColors.TextTertiary, fontSize = 12.sp)
                }
                BasicTextField(
                    value = shown,
                    onValueChange = { new ->
                        // 掩码状态下不允许编辑(否则会把圆点写进去)
                        if (!mask || revealed) local = new
                    },
                    enabled = !mask || revealed,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = XqColors.TextPrimary,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(XqColors.AccentSoft),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (mask) {
                Spacer(Modifier.width(6.dp))
                Action(if (revealed) "隐藏" else "显示") { revealed = !revealed }
            }
            Spacer(Modifier.width(6.dp))
            Action("保存") { onDone(local) }
        }
    }
}

@Composable
private fun Action(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) XqColors.Accent.copy(alpha = 0.26f) else GlassTokens.FillStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (accent) XqColors.AccentSoft else XqColors.TextSecondary,
            fontSize = 11.sp,
        )
    }
}
