package com.xiqueer.android.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xiqueer.android.BuildConfig
import com.xiqueer.android.data.PeriodTimesStore
import com.xiqueer.android.data.Overlays
import com.xiqueer.android.repository.TimetableCache
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * 提醒调度。**三个独立的闹钟槽**:
 *
 * | 槽 | 依赖作息表 | 说明 |
 * |---|---|---|
 * | 课前提醒 | **是** | 逐节课排,T−N 分钟。未配置作息时**不排**。 |
 * | 每日摘要 | 否 | 每天固定时刻推当天课表(只含节次)。 |
 * | 正在上课 | **是** | 上课时发一条锁屏可见的常驻通知,下课时撤掉。 |
 *
 * 每个槽**同一时刻只有一个闹钟**,响完自己重排下一个 —— 避开「几十个 PendingIntent
 * 要逐个记账/取消」那堆坑;进程被杀也不影响(闹钟在系统层,接收器会被拉起重排)。
 *
 * ⚠️ "正在上课"**没有前台服务**:倒计时交给系统的 chronometer,
 * 我们只在上课/下课两个时刻各响一次闹钟。见 [Notifications.classOngoing]。
 */
object ReminderScheduler {

    private const val TAG = "XqReminder"
    private const val RC_CLASS = 1001
    private const val RC_DIGEST = 1002
    private const val RC_ONGOING = 1003
    private const val RC_TEST = 1999

    const val ACTION_CLASS = "com.xiqueer.android.action.CLASS_REMINDER"
    const val ACTION_DIGEST = "com.xiqueer.android.action.DAILY_DIGEST"
    const val ACTION_ONGOING = "com.xiqueer.android.action.CLASS_ONGOING"
    const val EXTRA_TEST = "xq_test"

    /** 课前提醒只排未来 24 小时。 */
    const val HORIZON_MS = 24L * 60 * 60 * 1000

    fun classIntent(context: Context, test: Boolean = false): PendingIntent {
        val base = Intent(context, ClassAlarmReceiver::class.java).setAction(ACTION_CLASS)
        if (test) base.putExtra(EXTRA_TEST, true)
        return PendingIntent.getBroadcast(
            context,
            if (test) RC_TEST else RC_CLASS,
            base,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 「正在上课」的闹钟:上课时刻发通知、下课时刻撤通知,都用它。 */
    fun ongoingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RC_ONGOING,
            Intent(context, OngoingClassReceiver::class.java).setAction(ACTION_ONGOING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun digestIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        RC_DIGEST,
        Intent(context, DigestAlarmReceiver::class.java).setAction(ACTION_DIGEST),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 睡眠/时区变化后统一重排。 */
    fun reschedule(context: Context) {
        runCatching { rescheduleClass(context) }.onFailure { Log.e(TAG, "class reschedule failed", it) }
        runCatching { rescheduleDigest(context) }.onFailure { Log.e(TAG, "digest reschedule failed", it) }
        runCatching { rescheduleOngoing(context) }.onFailure { Log.e(TAG, "ongoing reschedule failed", it) }
    }

    // ---- 正在上课(锁屏/状态栏常驻) ----

    /**
     * 重算"现在是不是在上课",并排下一个边界。
     *
     * 一次只排**一个**闹钟:要么是"当前这节课的下课时刻"(该撤通知),
     * 要么是"下一节课的上课时刻"(该发通知)。响的时候再重算一次 ——
     * 与课前提醒用的是同一套自续期模式。
     */
    private fun rescheduleOngoing(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(ongoingIntent(context))

        val times = PeriodTimesStore(context).load()
        val overlaysForGate = Overlays.load(context)
        // 晚自习自带绝对时间,不依赖作息表 —— 所以"没配作息就什么都不发"
        // 只对服务端课表成立,不能把用户自己填的时段也一起挡掉
        val hasSelfStudy = overlaysForGate.selfStudies.any { it.weekdays.isNotEmpty() }
        if (!times.configured && !hasSelfStudy) {
            // 不知道几点上下课,就既不知道该何时发也不知道何时撤 —— 干脆不发
            Notifications.cancelOngoing(context)
            Log.d(TAG, "period times not configured → no ongoing notification")
            return
        }
        val timetable = TimetableCache.read(context) ?: return
        val overlays = overlaysForGate
        val now = System.currentTimeMillis()
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

        // 今明两天:跨午夜的一节课(23:50 下课)也要能正确撤销
        val courses = (0..1)
            .flatMap { d ->
                ScheduleOverrides.forDate(timetable, times, overlays, today.plusDays(d.toLong()))
                    .filter { !it.isMovedOut }
                    .map { it.occurrence }
            }
            .sortedBy { it.classStartMillis ?: Long.MAX_VALUE }

        val ongoing = courses.firstOrNull {
            val s = it.classStartMillis
            val e = it.classEndMillis
            s != null && e != null && now >= s && now < e
        }
        if (ongoing != null) {
            // 开了悬浮窗且系统给了权限 → 交给前台服务(它顺手把通知也发了,
            // 所以状态栏仍然只有一条);否则退回普通常驻通知。
            val wantOverlay = com.xiqueer.android.data.OverlaySettingsStore(context).enabled &&
                com.xiqueer.android.ui.overlay.ClassOverlayService.overlayAllowed(context)
            if (wantOverlay) {
                com.xiqueer.android.ui.overlay.ClassOverlayService.start(context)
            } else {
                com.xiqueer.android.ui.overlay.ClassOverlayService.stop(context)
                Notifications.classOngoing(context, ongoing)
            }
            arm(context, alarm, ongoing.classEndMillis!!, ongoingIntent(context))
            Log.i(
                TAG,
                "ongoing: ${ongoing.courseName} until ${Date(ongoing.classEndMillis!!)}" +
                    if (wantOverlay) " (悬浮窗)" else "",
            )
            return
        }

        // 不在上课 → 撤掉(可能是下课了,也可能是调休把课挪走了)
        Notifications.cancelOngoing(context)
        com.xiqueer.android.ui.overlay.ClassOverlayService.stop(context)
        val next = courses.firstOrNull { (it.classStartMillis ?: 0) > now }
        if (next != null) {
            arm(context, alarm, next.classStartMillis!!, ongoingIntent(context))
            Log.i(TAG, "next ongoing: ${next.courseName} at ${Date(next.classStartMillis!!)}")
        } else {
            Log.d(TAG, "no upcoming class for ongoing notification")
        }
    }

    // ---- 课前提醒 ----

    private fun rescheduleClass(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val store = PeriodTimesStore(context)
        alarm.cancel(classIntent(context))

        if (!store.reminderEnabled) {
            Log.d(TAG, "class reminders disabled")
            return
        }
        val times = store.load()
        if (!times.configured) {
            // 未配置作息:不排课前提醒。这是刻意的 —— 没有绝对时间就不该假装能提醒
            Log.i(TAG, "period times not configured (source=${times.source}); class reminders off")
            return
        }
        val timetable = TimetableCache.read(context) ?: run {
            Log.d(TAG, "no cached timetable")
            return
        }
        // ⚠️ 走 ScheduleOverrides 而不是 ClassReminder:调休之后,
        // "要提醒的课"和"课在哪一天"都变了。直接用底表会出现
        // "课挪走了,闹钟还在原来的日子响"——这是这个功能最坏的 bug。
        val overlays = Overlays.load(context)
        val upcoming = ScheduleOverrides.upcoming(
            timetable = timetable,
            times = times,
            overlays = overlays,
            leadMinutes = store.leadMinutes,
            nowMillis = System.currentTimeMillis(),
            horizonMillis = HORIZON_MS,
        )
        val next = upcoming.firstOrNull()
        Log.i(
            TAG,
            "source=${times.source.label} week=${timetable.currentWeek} shifts=${overlays.shifts.size} " +
                "upcoming24h=${upcoming.size}" +
                upcoming.take(3).joinToString("") { " | ${it.courseName} ${it.date} 第${it.periodStart}节 fire=${Date(it.fireAtMillis!!)}" },
        )
        if (next?.fireAtMillis == null) {
            Log.d(TAG, "nothing within ${HORIZON_MS / 3_600_000}h")
            return
        }
        arm(context, alarm, next.fireAtMillis, classIntent(context))
        Log.i(TAG, "armed class reminder for ${next.courseName} at ${Date(next.fireAtMillis)}")
    }

    // ---- 每日摘要 ----

    private fun rescheduleDigest(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val store = PeriodTimesStore(context)
        alarm.cancel(digestIntent(context))
        if (!store.digestEnabled) {
            Log.d(TAG, "digest disabled")
            return
        }
        val hm = parseClock(store.digestAt) ?: LocalTime.of(7, 0)
        val at = nextOccurrence(hm)
        arm(context, alarm, at, digestIntent(context))
        Log.i(TAG, "armed daily digest at ${Date(at)}")
    }

    private fun parseClock(s: String): LocalTime? =
        runCatching { LocalTime.parse(s.trim()) }.getOrNull()

    private fun nextOccurrence(time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        var target = LocalDateTime.of(LocalDate.now(zone), time).atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        if (target <= now) target += TimeUnit.DAYS.toMillis(1)
        return target
    }

    // ---- 通用 ----

    private fun arm(context: Context, alarm: AlarmManager, atMillis: Long, pi: PendingIntent) {
        // API 31+ 需要精确闹钟授权;拿不到就退化为不精确 —— 晚几分钟总比不提醒强
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }.onFailure { Log.e(TAG, "arm failed", it) }
    }

    /** debug:若干秒后触发一次课前提醒,验证「闹钟 → 接收器 → 通知」链路。 */
    fun scheduleTest(context: Context, seconds: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, classIntent(context, test = true)) }
            .onFailure { Log.e(TAG, "test arm failed", it) }
        Log.i(TAG, "test class alarm in ${seconds}s")
    }

    /** debug:若干秒后触发一次每日摘要。 */
    fun scheduleTestDigest(context: Context, seconds: Int) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds.toLong())
        runCatching { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, digestIntent(context)) }
            .onFailure { Log.e(TAG, "test digest arm failed", it) }
        Log.i(TAG, "test digest alarm in ${seconds}s")
    }
}

/**
 * 「正在上课」闹钟。响一次就**整体重排**:
 * 该发通知就发、该撤就撤,并顺手排下一个边界。
 *
 * 复用 `ReminderScheduler.reschedule` 而不是只重排这一个槽 ——
 * 这样"上课/下课"这个时间点也顺带把课前提醒与摘要对齐了一次,不会出现两个槽各自漂移。
 */
class OngoingClassReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_ONGOING) return
        Notifications.ensureChannels(context)
        ReminderScheduler.reschedule(context)
    }
}

/** 课前提醒闹钟。 */
class ClassAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_CLASS) return
        Notifications.ensureChannels(context)
        val store = PeriodTimesStore(context)
        val times = store.load()
        val now = System.currentTimeMillis()

        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_TEST, false)) {
            // debug:从真实课表里挑一节演示(也走覆盖层,免得测出来的和真跑的不一样)
            val overlays = Overlays.load(context)
            val demo = TimetableCache.read(context)?.let { t ->
                ScheduleOverrides.forDate(t, times, overlays, LocalDate.now()).firstOrNull()?.occurrence
                    ?: ScheduleOverrides.forDate(
                        t, times, overlays, ClassReminder.weekMonday(t) ?: LocalDate.now(),
                    ).firstOrNull()?.occurrence
            }
            if (demo != null) {
                Notifications.classSoon(context, demo, store.leadMinutes)
                Log.i("XqReminder", "test notification posted: ${demo.courseName}")
            } else {
                Log.w("XqReminder", "test fired but no course to show")
            }
            ReminderScheduler.reschedule(context)
            return
        }

        if (times.configured) {
            val timetable = TimetableCache.read(context)
            if (timetable != null) {
                // 找出"刚刚应该提醒"的那一节:fireAt 已过但在 5 分钟内
                val justDue = ScheduleOverrides.upcoming(
                    timetable = timetable,
                    times = times,
                    overlays = Overlays.load(context),
                    leadMinutes = store.leadMinutes,
                    nowMillis = now - 5 * 60_000L,
                    horizonMillis = 5 * 60_000L,
                ).firstOrNull { (it.fireAtMillis ?: Long.MAX_VALUE) <= now + 60_000L }
                if (justDue != null) Notifications.classSoon(context, justDue, store.leadMinutes)
            }
        }

        ReminderScheduler.reschedule(context)
    }
}

/**
 * 每日摘要闹钟。
 *
 * 不需要作息表 —— 所以**它是未配置作息时唯一的推送**。响完自己排下一天。
 */
class DigestAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_DIGEST) return
        Notifications.ensureChannels(context)
        val store = PeriodTimesStore(context)
        val today = LocalDate.now()

        val timetable = TimetableCache.read(context)
        // 作息表已配置时,摘要也带上每节的开始时间(P6 导入后应当立刻体现在推送里)。
        // **并且走覆盖层**:不然调休当天早上推的还是原来那天的课,
        // 改了教室/节次的那一节也不会体现在摘要里。
        val courses = if (timetable == null) emptyList()
        else ScheduleOverrides
            .forDate(timetable, store.load(), Overlays.load(context), today)
            .filter { !it.isMovedOut }   // 被调走的课不再算"今天要上"
            .map { it.occurrence }
        // 没课也发一条 —— 让用户确信提醒是活的,而不是静默失效
        if (timetable != null) Notifications.digest(context, today, courses)

        ReminderScheduler.reschedule(context)
    }
}

/** 开机 / 时区 / 时间变更后重排 —— 系统会清掉已排的闹钟。 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> ReminderScheduler.reschedule(context)
        }
        // 选课监听也要重启(开机后服务会被清掉)。后台路径 —— API 34+ 不在这里起前台服务,
        // 等用户下次打开 App 由前台路径拉起。
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching {
                com.xiqueer.android.watch.SelectionWatch.resync(context, fromBackground = true)
            }
        }
    }
}

/**
 * **仅 debug 生效**的触发入口:
 *
 * ```
 * adb shell am broadcast -a com.xiqueer.android.debug.TEST_REMINDER \
 *     --ei seconds 8 -n com.xiqueer.android/.notify.DebugReceiver
 * adb shell am broadcast -a com.xiqueer.android.debug.TEST_DIGEST \
 *     --ei seconds 8 -n com.xiqueer.android/.notify.DebugReceiver
 * ```
 *
 * release 里 `BuildConfig.DEBUG` 为 false,直接返回,不留后门。
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val secs = intent.getIntExtra("seconds", 10)
        when (intent.action) {
            "com.xiqueer.android.debug.TEST_REMINDER" -> ReminderScheduler.scheduleTest(context, secs)
            "com.xiqueer.android.debug.TEST_DIGEST" -> ReminderScheduler.scheduleTestDigest(context, secs)
        }
    }
}
