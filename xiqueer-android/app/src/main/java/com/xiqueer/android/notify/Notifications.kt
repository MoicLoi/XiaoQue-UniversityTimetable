package com.xiqueer.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xiqueer.android.MainActivity
import java.time.LocalDate

/**
 * 通知渠道与构建器。
 *
 * 内容**只在有作息表时才带时钟** —— 未配置时显示「第 1-2 节 · 厚德楼-H502」,
 * 不编造时间。
 */
object Notifications {

    const val CH_CLASS_SOON = "class_soon"
    const val CH_DIGEST = "daily_digest"
    /** P7 选课监听复用。 */
    const val CH_SELECTION = "selection_alert"

    /** 监听常驻通知(前台服务必须挂一个,顺便让用户随时能看见"它在跑")。 */
    const val CH_WATCH = "selection_watch"

    /**
     * 「正在上课」常驻通知。
     *
     * IMPORTANCE_LOW:不出声、不弹横幅 —— 它是**状态显示**,不是提醒。
     * 但 `lockscreenVisibility = PUBLIC`:锁屏上必须能看见(需求要的"锁屏提示")。
     */
    const val CH_ONGOING = "class_ongoing"

    const val ID_CLASS_SOON = 1001
    const val ID_DIGEST = 1002
    const val ID_WATCH = 1003
    const val ID_SELECTION_ALERT = 1004
    const val ID_ONGOING = 1005
    const val ID_SESSION = 1006

    /**
     * 「登录已过期」。
     *
     * IMPORTANCE_DEFAULT:这条是要用户**做事**的(去重新登录),
     * 所以允许出声/弹横幅 —— 与「正在上课」那种状态显示不同。
     */
    const val CH_SESSION = "session_expired"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_CLASS_SOON, "上课提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "上课前提醒,含教室与节次" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DIGEST, "每日课表", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "每天早上汇总当天课程" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SELECTION, "选课与教务提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "选课开放、教务通知变更等" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "选课监听状态", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "监听运行期间的常驻状态栏提示" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "登录状态", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "会话失效时提醒你重新登录(平时不打扰)" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "正在上课", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "上课期间在状态栏/锁屏显示当前课程与下课倒计时"
                    // 静音、不弹横幅,但**锁屏要能看见** —— 这正是这个渠道存在的意义
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                },
        )
    }

    /**
     * 「正在上课」常驻通知 —— **只构建不发**。
     *
     * 悬浮窗开启时用它当前台服务的通知(所以状态栏不会多出一条);
     * 悬浮窗关闭时由 [classOngoing] 直接发出去。
     */
    fun classOngoingNotification(context: Context, o: ClassOccurrence): android.app.Notification {
        val end = o.classEndMillis
        val period = if (o.periodEnd != o.periodStart) {
            "第 ${o.periodStart}-${o.periodEnd} 节"
        } else {
            "第 ${o.periodStart} 节"
        }
        val where = listOf(o.room, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("正在上:${o.courseName}")
            .setContentText(where.ifEmpty { period })
            .setSubText("$period · 距下课")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
        if (end != null) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(end)
        }
        return b.build()
    }

    /**
     * 「正在上课」常驻通知 —— 直接发出去(悬浮窗关闭时的路径)。
     *
     * 倒计时交给**系统**:`setUsesChronometer(true)` + `setChronometerCountDown(true)`,
     * 系统自己每秒刷新,不需要任何常驻进程 —— 只用两个闹钟(上课发、下课撤)。
     *
     * ⚠️ 这里**直接发**。早先写成 `return Notification` 让调用方发,
     * 而调用方只调了不接返回值 —— 日志写着"ongoing: 无机化学",
     * 系统里却什么都没有。只有一个调用方的返回值 API 就是给这种错误留的口子。
     */
    fun classOngoing(context: Context, o: ClassOccurrence) {
        post(context, ID_ONGOING, classOngoingNotification(context, o))
    }

    fun cancelOngoing(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_ONGOING) }
    }

    /** 监听常驻通知(前台服务要求)。 */
    fun watchOngoing(context: Context, text: String): android.app.Notification =
        NotificationCompat.Builder(context, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("选课监听中")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .build()

    /**
     * 弱信号命中时的提醒。
     *
     * 文案刻意用「可能有」——因为我们看的是通知关键词,不是选课接口本身,
     * 把不确定说成确定是误导。
     */
    fun selectionAlert(context: Context, title: String, evidence: String) {
        post(
            context, ID_SELECTION_ALERT,
            NotificationCompat.Builder(context, CH_SELECTION)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("可能有选课相关信息")
                .setContentText(title)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("$title\n$evidence\n\n点开可以让 AI 帮你看看能不能选。"),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build(),
        )
    }

    /**
     * 「登录已过期」提醒。
     *
     * 由**后台**路径发出(每 6 小时的刷新、选课监听)—— 目的是让用户从通知得知,
     * 而不是打开 App 看见一屏空白再自己猜。前台路径不发这条:
     * 用户正看着界面,顶部提示条已经说清楚了,再弹一条纯属噪音。
     */
    fun sessionExpired(context: Context, detail: String) {
        post(
            context, ID_SESSION,
            NotificationCompat.Builder(context, CH_SESSION)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("登录已过期")
                .setContentText("点这里重新登录 —— 学校与学号都给你留着")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "点这里重新登录 —— 学校与学号都给你留着,只需要再输一次密码。\n\n$detail",
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build(),
        )
    }

    fun cancelSessionExpired(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_SESSION) }
    }

    private fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 课前提醒。只有配置了作息表才会走到这里。 */
    fun classSoon(context: Context, o: ClassOccurrence, leadMinutes: Int) {
        val title = if (o.startLabel != null) {
            "$leadMinutes 分钟后:${o.courseName}"
        } else {
            "即将上课:${o.courseName}"
        }
        val text = o.scheduleLine()
        post(
            context, ID_CLASS_SOON + o.stableId,
            NotificationCompat.Builder(context, CH_CLASS_SOON)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build(),
        )
    }

    /**
     * 每日课表摘要。
     *
     * **这是未配置作息表时唯一的推送** —— 它只需要节次。
     */
    fun digest(context: Context, date: LocalDate, courses: List<ClassOccurrence>) {
        val title = if (courses.isEmpty()) "今天没有课" else "今天 ${courses.size} 节课"
        val body = if (courses.isEmpty()) {
            "${date.monthValue} 月 ${date.dayOfMonth} 日 · 好好休息"
        } else {
            courses.joinToString("\n") { it.scheduleLine() }
        }
        post(
            context, ID_DIGEST,
            NotificationCompat.Builder(context, CH_DIGEST)
                .setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle(title)
                .setContentText(courses.firstOrNull()?.scheduleLine() ?: body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context))
                .build(),
        )
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
