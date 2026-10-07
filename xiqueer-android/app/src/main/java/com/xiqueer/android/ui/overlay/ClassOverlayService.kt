package com.xiqueer.android.ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import com.xiqueer.android.data.PeriodTimesStore
import com.xiqueer.android.notify.ClassOccurrence
import com.xiqueer.android.notify.Notifications
import com.xiqueer.android.notify.ScheduleOverrides
import com.xiqueer.android.repository.TimetableCache
import com.xiqueer.android.data.Overlays
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 「正在上课」悬浮窗。
 *
 * 只在**课中**运行:上课那一刻由闹钟拉起,下课那一刻自己停掉。
 * 这是刻意的 —— 一个常年挂着的悬浮窗会被用户当成流氓软件,
 * 而"上课时看一眼还剩多久"恰好只需要课中那段时间。
 *
 * 三个设计取舍:
 *
 * 1. **用前台服务**:API 26+ 起普通后台服务会被系统很快回收,浮窗会突然消失。
 *    前台服务的通知**就是**那条「正在上课」通知,所以状态栏不会多出一条。
 * 2. **用系统 Chronometer 倒计时**:`setCountDown(true)` + `setBase(下课时刻)`,
 *    系统自己走秒。我们自己不需要每秒钟醒来一次 —— 这对续航差别很大。
 * 3. **浮窗不接收触摸**(`FLAG_NOT_TOUCHABLE`):它是个状态显示,
 *    不该挡住底下的操作。要关就在设置里关开关,或者等下课自动消失。
 */
class ClassOverlayService : Service() {

    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var timer: Chronometer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)

        val o = currentOngoingCourse(this)
        if (o == null) {
            Log.i(TAG, "没有正在上的课 → 停掉浮窗")
            stopSelf()
            return START_NOT_STICKY
        }

        // 前台服务的通知复用「正在上课」那条:状态栏不会因此多一条
        startForeground(Notifications.ID_ONGOING, Notifications.classOngoingNotification(this, o))
        showOverlay(o)
        return START_NOT_STICKY
    }

    private fun showOverlay(o: ClassOccurrence) {
        if (overlayAllowed(this).not()) {
            Log.w(TAG, "没有悬浮窗权限,只保留通知")
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: return

        if (view == null) {
            view = buildView(o).also { v ->
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    // API 26 起必须用 TYPE_APPLICATION_OVERLAY;更低版本没有这个概念
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(10)
                    y = dp(96)
                }
                runCatching { wm.addView(v, params) }
                    .onFailure { Log.e(TAG, "addView failed", it) }
            }
        } else {
            // 已经在显示:只更新文案与倒计时基准
            title?.text = "正在上:${o.courseName}"
            detail?.text = detailOf(o)
            timer?.let { t ->
                t.stop()
                t.setDeadline(o.classEndMillis)
                if (o.classEndMillis != null) t.start()
            }
        }
        // 把剩余时长也打进日志:这样"界面上显示的数字"可以和"代码算出来的数字"
        // 对照着看 —— 只断言"窗口存在"是不够的,那个壳里曾经显示过四十几万小时。
        Log.i(
            TAG,
            "浮窗已显示:${o.courseName} 距下课 ${millisUntilEnd(o) / 60_000} 分钟" +
                " (下课时刻=${o.classEndMillis})",
        )

        // 再等一拍,把**控件自己的文本**打出来。
        //
        // 这一段是那次真机事故留下的:悬浮窗窗口、位置、颜色全对,唯独倒计时显示成
        // 1970 起点、四十多万小时 —— 因为 Chronometer 的 base 用错了时间基准。
        // 当时所有断言都只检查了"窗口存在",所以全绿。
        // 覆盖窗口不进无障碍树,`uiautomator dump` 读不到它的文字,
        // 于是唯一可靠的读法就是在进程内读 `Chronometer.text`(它就是被画出来的那个串)。
        view?.postDelayed({
            Log.i(TAG, "浮窗倒计时控件文本='${timer?.text}' (期望约 ${millisUntilEnd(o) / 1000} 秒)")
        }, 1500)
    }

    private fun buildView(o: ClassOccurrence): LinearLayout {
        val pad = dp(10)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E6121722"))          // 深色半透明
                setStroke(dp(1), Color.parseColor("#336FA8FF"))
            }
        }
        title = TextView(this).apply {
            text = "正在上:${o.courseName}"
            setTextColor(Color.parseColor("#F2F5FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        detail = TextView(this).apply {
            text = detailOf(o)
            setTextColor(Color.parseColor("#B3F2F5FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        timer = Chronometer(this).apply {
            setTextColor(Color.parseColor("#9CC4FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            // 系统自己走秒,倒数到下课 —— 我们不必每分钟醒来一次。
            // minSdk 26 = N,setCountDown 一定存在。
            setCountDown(true)
            setDeadline(o.classEndMillis)
            if (o.classEndMillis != null) start() else visibility = View.GONE
        }
        root.addView(title)
        root.addView(detail)
        root.addView(timer)
        return root
    }

    /**
     * 把"下课时刻"设成倒计时终点。
     *
     * ⚠️ **这里踩过一个真机才暴露的坑**:
     * `Chronometer` 的 `base` 时间基准是 `SystemClock.elapsedRealtime()`
     * (开机至今的毫秒数),**不是墙上时钟**。直接把 `classEndMillis`
     * (1970 起的 epoch 毫秒)赋给 `base`,它会算 `base - elapsedRealtime` ——
     * 一个 1.79e12 量级的差值,显示成「四十多万小时」,起点是 1970/1/1。
     *
     * 正确做法是只取**剩余时长**,再加到 elapsedRealtime 上 ——
     * 两个量都在同一个时间基准里,相减才有意义。
     *
     * 注意通知那条不走这里:通知的 `setWhen()` + chronometer 用的**就是**
     * epoch 毫秒,口径不同。所以同一个"下课时刻"在两处的用法不一样,
     * 这正是当初搞混的原因。
     */
    private fun Chronometer.setDeadline(endMillis: Long?) {
        if (endMillis == null) return
        val remaining = endMillis - System.currentTimeMillis()
        base = SystemClock.elapsedRealtime() + remaining
    }

    private fun detailOf(o: ClassOccurrence): String {
        val period = if (o.periodEnd != o.periodStart) {
            "第 ${o.periodStart}-${o.periodEnd} 节"
        } else {
            "第 ${o.periodStart} 节"
        }
        val where = listOf(o.room).filter { it.isNotBlank() }.joinToString(" · ")
        return if (where.isEmpty()) "$period · 距下课" else "$where · 距下课"
    }

    override fun onDestroy() {
        view?.let { v ->
            runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
        }
        view = null
        title = null
        detail = null
        timer = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "XqOverlay"

        /** 系统设置里那个「显示在其他应用上层」开关是否给到了本 App。 */
        fun overlayAllowed(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, ClassOverlayService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "start overlay service failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ClassOverlayService::class.java)) }
                .onFailure { Log.w(TAG, "stop overlay service failed", it) }
        }

        /** 当前正在上的那节课(与提醒共用同一套覆盖层修正逻辑)。 */
        fun currentOngoingCourse(context: Context, now: Long = System.currentTimeMillis()): ClassOccurrence? {
            val times = PeriodTimesStore(context).load()
            val overlays = Overlays.load(context)
            // 晚自习不依赖作息表,所以不能因为"没配作息"就把整个判断跳过
            val hasSelfStudy = overlays.selfStudies.any { it.weekdays.isNotEmpty() }
            if (!times.configured && !hasSelfStudy) return null
            val timetable = TimetableCache.read(context) ?: return null
            val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            return (0..1)
                .flatMap { d ->
                    ScheduleOverrides.forDate(timetable, times, overlays, today.plusDays(d.toLong()))
                        .filter { !it.isMovedOut }
                        .map { it.occurrence }
                }
                .firstOrNull {
                    val s = it.classStartMillis
                    val e = it.classEndMillis
                    s != null && e != null && now >= s && now < e
                }
        }

        /** 供调试:距离下课还有多少毫秒。 */
        fun millisUntilEnd(o: ClassOccurrence, now: Long = System.currentTimeMillis()): Long =
            ((o.classEndMillis ?: now) - now).coerceAtLeast(0)

        internal fun secondsUntilEnd(o: ClassOccurrence, now: Long = System.currentTimeMillis()): Long =
            TimeUnit.MILLISECONDS.toSeconds(millisUntilEnd(o, now))
    }
}
