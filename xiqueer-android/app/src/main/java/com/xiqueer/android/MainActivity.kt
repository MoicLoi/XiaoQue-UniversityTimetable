package com.xiqueer.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiqueer.android.export.ExportShare
import com.xiqueer.android.ui.AgentPanel
import com.xiqueer.android.ui.CourseDetailOverlay
import com.xiqueer.android.ui.CourseEditSheet
import com.xiqueer.android.ui.CourseItemsSheet
import com.xiqueer.android.ui.ExamsScreen
import com.xiqueer.android.ui.ExportSheet
import com.xiqueer.android.ui.GradesScreen
import com.xiqueer.android.ui.LoginScreen
import com.xiqueer.android.ui.MoreMenuSheet
import com.xiqueer.android.ui.NoticeDetailOverlay
import com.xiqueer.android.ui.NoticesScreen
import com.xiqueer.android.ui.PeriodTimesNotice
import com.xiqueer.android.ui.ScheduleScreen
import com.xiqueer.android.ui.SchoolPickerSheet
import com.xiqueer.android.ui.SelfStudySheet
import com.xiqueer.android.ui.SettingsSheet
import com.xiqueer.android.ui.ShiftSheet
import com.xiqueer.android.ui.StudyPlanScreen
import com.xiqueer.android.ui.TimetableScreen
import com.xiqueer.android.ui.XqColors
import com.xiqueer.android.ui.XqTheme
import com.xiqueer.android.ui.glass.GlassBackground
import com.xiqueer.android.ui.glass.GlassSurface
import com.xiqueer.android.ui.glass.GlassTokens
import com.xiqueer.android.ui.glass.LocalGlassSource
import com.xiqueer.android.ui.glass.rememberGlassSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // 前台标记:会话失效时用它决定"要不要弹通知"。
    // 前台有顶部提示条,再弹通知是噪音;后台那条通知是用户唯一的知情渠道。
    override fun onStart() {
        super.onStart()
        com.xiqueer.android.notify.AppVisibility.foreground = true
    }

    override fun onStop() {
        com.xiqueer.android.notify.AppVisibility.foreground = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 无开屏页:系统 SplashScreen 即首帧
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            XqTheme {
                val source = rememberGlassSource()
                CompositionLocalProvider(LocalGlassSource provides source) {
                    Box(Modifier.fillMaxSize()) {
                        // 背景单独一层:清晰绘制 + 录进采样层(不含内容,避免自我反馈)
                        GlassBackground(source = source, modifier = Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(XqColors.Ink, XqColors.Aurora2, XqColors.Aurora1, XqColors.Ink),
                                        ),
                                    ),
                            )
                        }
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    // 注意:这里只读真正需要的那几项。以前是 `val state = vm.state` 一把抓,
    // 于是 agent 面板每次刷新状态文本都会让整棵 UI 树重组。
    val booting = vm.state.booting
    val loggedIn = vm.state.loggedIn
    val busy = vm.state.busy
    val tab = vm.state.tab
    val error = vm.state.error
    val username = vm.state.username
    val displayName = vm.state.displayName
    val timetable = vm.state.timetable
    val periodTimes = vm.state.periodTimes
    val currentTermLabel = vm.state.currentTermLabel

    // 展开的课程详情:状态在 VM 里,屏幕旋转 / 进程重建都不会丢
    val selected = vm.selectedCourse()

    // 导出面板 + 生成导出文件。生成是纯计算(拼 zip/文本),但几百个日历事件
    // 也不该占主线程,所以放到 IO 里跑完再拉分享面板。
    var exportSheet by remember { mutableStateOf(false) }
    val exportScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun doExport(kind: String) {
        val t = timetable ?: return
        exportScope.launch {
            val art = withContext(Dispatchers.IO) {
                runCatching {
                    when (kind) {
                        "png" -> ExportShare.pngArtifact(t, periodTimes, vm.state.overlays, t.currentWeek)
                        "xlsx" -> ExportShare.xlsxArtifact(t, periodTimes, vm.state.overlays)
                        "csv" -> ExportShare.csvArtifact(t, vm.state.overlays, t.currentWeek)
                        else -> ExportShare.icsArtifact(t, periodTimes, vm.state.overlays)
                    }
                }.getOrNull()
            }
            if (art == null) {
                android.widget.Toast
                    .makeText(context, "导出失败:日历需要先填作息时间", android.widget.Toast.LENGTH_LONG)
                    .show()
            } else {
                runCatching { ExportShare.share(context, art) }
                    .onFailure {
                        android.widget.Toast
                            .makeText(context, "没有可用的分享目标", android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
            }
        }
    }

    // API 33+ 需要显式申请通知权限,否则上课提醒发不出来
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不阻塞使用,只是收不到提醒 */ }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            !loggedIn -> LoginScreen(
                initialUsername = username,
                schoolName = vm.state.schoolName,
                busy = busy,
                error = error,
                onPickSchool = vm::openSchoolPicker,
                onLogin = vm::login,
            )

            else -> Column(Modifier.fillMaxSize()) {
                HomeHeader(
                    title = displayName.ifEmpty { username },
                    subtitle = currentTermLabel,
                    busy = busy,
                    onRefresh = vm::refreshCurrent,
                    onSettings = vm::openSettings,
                    onMore = vm::toggleMoreMenu,
                    onLogout = vm::logout,
                )
                // 登录之后也必须能看到错误 —— 否则服务端一拒绝,
                // 用户看到的就是"页面全空、点刷新没反应、还没有任何解释"。
                com.xiqueer.android.ui.ErrorBanner(
                    message = error,
                    sessionExpired = vm.state.sessionExpired,
                    onRelogin = vm::logout,
                    onDismiss = vm::dismissError,
                )
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        // 日程:今天/明天要上什么、下一节是什么、要带什么
                        Tab.Schedule -> ScheduleScreen(
                            timetable = timetable,
                            times = periodTimes,
                            items = vm.state.courseItems,
                            overlays = vm.state.overlays,
                            nextWeekTimetable = vm.state.nextWeekTimetable,
                            onCourseClick = vm::openCourse,
                        )
                        Tab.Timetable -> TimetableScreen(
                            timetable = timetable,
                            times = periodTimes,
                            currentWeek = vm.state.currentWeek,
                            overlays = vm.state.overlays,
                            onCourseClick = vm::openCourse,
                            onPrevWeek = vm::prevWeek,
                            onNextWeek = vm::nextWeek,
                            onCurrentWeek = vm::backToCurrentWeek,
                            onExport = { exportSheet = true },
                        )
                        Tab.Grades -> GradesScreen(vm.state.grades)
                        Tab.Exams -> ExamsScreen(
                            terms = vm.state.examTerms,
                            selectedTerm = vm.state.examTerm,
                            selectedRound = vm.state.examRound,
                            items = vm.state.examItems,
                            loading = busy,
                            onSelectTerm = vm::selectExamTerm,
                            onSelectRound = vm::selectExamRound,
                        )
                        Tab.Notices -> NoticesScreen(vm.state.notices, onOpen = vm::openNotice)
                        Tab.Plan -> StudyPlanScreen(vm.state.plan)
                    }
                }
                BottomNav(current = tab, onSelect = vm::selectTab)
            }
        }

        CourseDetailOverlay(
            course = selected,
            times = periodTimes,
            override = vm.selectedOverride(),
            onDismiss = vm::closeCourse,
        )

        // 「课节改动」浮空按钮:点开某一节课之后才出现。
        // 观感与 AI 按钮同一套(同样 0.30 透明度),但叠在它**上方** ——
        // 两个圆钮落在同一个角上会互相遮住。AI 面板展开时让位。
        val slot = vm.selectedCourseSlot()
        if (loggedIn && slot != null && !vm.state.agent.open) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 140.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(XqColors.Accent.copy(alpha = 0.30f))
                    .clickable(onClick = vm::openCourseEdit)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "课节改动",
                    color = XqColors.AccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        CourseEditSheet(
            visible = vm.state.courseEditOpen,
            course = slot?.second,
            week = timetable?.currentWeek ?: 0,
            weekday = slot?.first ?: 0,
            override = vm.selectedOverride(),
            onSave = { room, periods ->
                val s = vm.selectedCourseSlot()
                if (s == null) "没选中课"
                else vm.setCourseOverride(
                    week = timetable?.currentWeek ?: 0,
                    weekday = s.first,
                    courseKey = com.xiqueer.android.data.CourseKey.of(s.second),
                    room = room,
                    periods = periods,
                )
            },
            onRestore = {
                val s = vm.selectedCourseSlot()
                if (s == null) "没选中课"
                else vm.clearCourseOverride(
                    week = timetable?.currentWeek ?: 0,
                    weekday = s.first,
                    courseKey = com.xiqueer.android.data.CourseKey.of(s.second),
                )
            },
            onDismiss = vm::closeCourseEdit,
        )

        SelfStudySheet(
            visible = vm.state.selfStudySheetOpen,
            slots = vm.state.overlays.selfStudies,
            onSave = vm::saveSelfStudies,
            onDismiss = vm::closeSelfStudySheet,
        )
        NoticeDetailOverlay(
            detail = vm.state.noticeDetail,
            loading = vm.state.noticeDetailOpen && vm.state.noticeDetail == null,
            onDismiss = vm::closeNotice,
        )
        PeriodTimesNotice(
            visible = vm.state.periodNoticeVisible,
            onDismiss = vm::dismissPeriodNotice,
            onOpenSettings = {
                vm.dismissPeriodNotice()
                vm.openSettings()
            },
        )

        // AI 面板只在登录后才出现 —— 未登录时它没有任何工具能查
        if (loggedIn) {
            AgentPanel(
                state = vm.state.agent,
                configured = vm.state.agentConfig.usable,
                onToggle = vm::toggleAgent,
                onClose = vm::closeAgent,
                onSend = vm::sendAgent,
                onConfirm = vm::confirmPending,
                onDismissPending = vm::dismissPending,
                onOpenSettings = { vm.closeAgent(); vm.openSettings() },
            )
        }

        CourseItemsSheet(
            visible = vm.state.itemsSheetOpen,
            courseNames = vm.courseNames(),
            items = vm.state.courseItems,
            onSet = vm::setCourseItems,
            onDismiss = vm::closeItemsSheet,
        )

        ShiftSheet(
            visible = vm.state.shiftSheetOpen,
            shifts = vm.state.overlays.shifts,
            onAdd = vm::addShift,
            onRemove = vm::removeShift,
            onDismiss = vm::closeShiftSheet,
        )

        MoreMenuSheet(
            visible = vm.state.moreMenuOpen,
            versionLabel = BuildConfig.VERSION_NAME,
            noticeCount = vm.state.notices.size,
            shiftCount = vm.state.overlays.shifts.size,
            selfStudyCount = vm.state.overlays.selfStudies.size,
            onNotices = {
                vm.closeMoreMenu()
                vm.selectTab(Tab.Notices)
            },
            onShifts = {
                vm.closeMoreMenu()
                vm.openShiftSheet()
            },
            onSelfStudy = {
                vm.closeMoreMenu()
                vm.openSelfStudySheet()
            },
            onDismiss = vm::closeMoreMenu,
        )

        SchoolPickerSheet(
            visible = vm.state.schoolPickerOpen,
            schools = vm.state.schools,
            loading = vm.state.schoolsLoading,
            error = vm.state.schoolsError,
            search = vm::searchSchools,
            onDismiss = vm::closeSchoolPicker,
            onPick = vm::pickSchool,
            onRetry = { vm.loadSchools(force = true) },
        )

        ExportSheet(
            visible = exportSheet,
            hasPeriodTimes = periodTimes.configured,
            hasSelfStudy = vm.state.overlays.selfStudies.any { it.weekdays.isNotEmpty() },
            onDismiss = { exportSheet = false },
            onImage = { doExport("png") },
            onExcel = { doExport("xlsx") },
            onCsv = { doExport("csv") },
            onCalendar = { doExport("ics") },
        )

        SettingsSheet(
            visible = vm.state.settingsOpen,
            agent = vm.state.agentConfig,
            periodTimes = periodTimes,
            leadMinutes = vm.state.leadMinutes,
            reminderEnabled = vm.state.reminderEnabled,
            digestEnabled = vm.state.digestEnabled,
            digestAt = vm.state.digestAt,
            watch = vm.state.watch,
            watchPollCount = vm.state.watchPollCount,
            itemsFilled = vm.state.courseItems.count { !it.value.isNullOrBlank() },
            itemsTotal = vm.courseNames().size,
            overlayEnabled = vm.state.overlayEnabled,
            overlayAllowed = vm.state.overlayAllowed,
            onOverlay = vm::setOverlayEnabled,
            onRequestOverlayPermission = {
                // 只引导到系统页面,绝不代用户点授权
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    android.widget.Toast
                        .makeText(context, "打不开系统设置,请手动到「设置 → 应用 → 显示在其他应用上层」", android.widget.Toast.LENGTH_LONG)
                        .show()
                }
            },
            onDismiss = vm::closeSettings,
            onOpenItems = {
                vm.closeSettings()
                vm.openItemsSheet()
            },
            onSaveAgent = vm::saveAgentConfig,
            onSavePeriodTimes = vm::saveManualPeriodTimes,
            onReminder = vm::setReminderEnabled,
            onLead = vm::setLeadMinutes,
            onDigest = vm::setDigest,
            onWatch = vm::saveWatch,
        )
    }
}

@Composable
private fun HomeHeader(
    title: String,
    subtitle: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onMore: () -> Unit,
    onLogout: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, color = XqColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = XqColors.TextTertiary, fontSize = 11.sp)
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            PillButton("刷新", onRefresh)
            Spacer(Modifier.width(8.dp))
            // 「更多」收纳通知等不常用入口 —— 通知从底栏让位给日程
            PillButton("更多", onMore)
            Spacer(Modifier.width(8.dp))
            PillButton("设置", onSettings)
            Spacer(Modifier.width(8.dp))
            PillButton("退出", onLogout)
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(GlassTokens.FillStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = XqColors.TextSecondary, fontSize = 13.sp)
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 注意用 BOTTOM_NAV 而不是 entries:通知已经挪进「更多」菜单,
            // 用 entries 会把它又画回底栏
            Tab.BOTTOM_NAV.forEach { t ->
                TabButton(t.label, current == t, Modifier.weight(1f)) { onSelect(t) }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) XqColors.Accent.copy(alpha = 0.26f) else GlassTokens.Fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) XqColors.AccentSoft else XqColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
        )
    }
}
