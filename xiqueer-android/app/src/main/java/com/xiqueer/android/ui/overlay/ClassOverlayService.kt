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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.TextView
import com.xiqueer.android.data.Overlays
import com.xiqueer.android.data.PeriodTimesStore
import com.xiqueer.android.notify.ClassOccurrence
import com.xiqueer.android.notify.Notifications
import com.xiqueer.android.notify.ScheduleOverrides
import com.xiqueer.android.repository.TimetableCache
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * 「正在上课」悬浮窗。
 *
 * 只在**课中**运行:上课那一刻由闹钟拉起,下课那一刻自己停掉。
 * 这是刻意的 —— 一个常年挂着的悬浮窗会被用户当成流氓软件,
 * 而"上课时看一眼还剩多久"恰好只需要课中那段时间。
 *
 * 设计取舍:
 *
 * 1. **用前台服务**:API 26+ 起普通后台服务会被系统很快回收,浮窗会突然消失。
 *    前台服务的通知**就是**那条「正在上课」通知,所以状态栏不会多出一条。
 * 2. **用系统 Chronometer 倒计时**:`setCountDown(true)` + `setBase(下课时刻)`,
 *    系统自己走秒。我们自己不需要每秒钟醒来一次 —— 这对续航差别很大。
 * 3. **浮窗可拖动,贴边自动收起**(见下)。
 *
 * ## 关于"可拖动"这件事带来的行为变化
 *
 * V1.0.3 之前这里挂着 `FLAG_NOT_TOUCHABLE`:浮窗是一块**点得穿的玻璃**,
 * 谁也不会被它挡住。要能拖、能点,就必须去掉它 —— 于是浮窗从此会**吃掉**
 * 落在它自己身上的触摸。
 *
 * 这是本功能最大的一处代价,对策有三条:
 * - 窗口是 `WRAP_CONTENT`,**只**吃掉自己那块矩形,外面照旧穿透;
 * - 拖动 vs 点击用系统的 `scaledTouchSlop` 判阈值,不会拖一下就误触发点击;
 * - 拖到边缘就**收起成小条**,把占位面积降到最小。
 *
 * ## 位置与收起状态
 *
 * 位置按"**贴着哪一边 + 距那一边多少**"存([OverlayPos]),而不是存绝对坐标:
 * 收起时窗口会变窄,绝对坐标会让它跳一下;贴边存法则自动贴住不动。
 * 存下来是刻意的 —— 否则每节课都要重拖一遍。
 */
class ClassOverlayService : Service() {

    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var timer: Chronometer? = null
    /** 收起态才显示:课程名的前两个字,否则用户不知道那条小东西是什么。 */
    private var shortName: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var pos: OverlayPos

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        pos = OverlayPos(this)

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
            val v = buildView(o)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // API 26 起必须用 TYPE_APPLICATION_OVERLAY;更低版本没有这个概念
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ⚠️ 这里**没有** FLAG_NOT_TOUCHABLE —— 浮窗要能拖、能点。
                // 代价是它会吃掉自己那块矩形上的触摸,见类注释。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { applySavedPosition() }

            view = v
            params = p
            attachTouch(v, wm)
            runCatching { wm.addView(v, p) }
                .onFailure { Log.e(TAG, "addView failed", it) }
            // 收起状态在**布局完成后**才能应用:收起要按窗口实际宽度贴边
            v.post { applyCollapsed(wm, pos.collapsed, snapToEdge = false, reason = "初次显示") }
        } else {
            // 已经在显示:只更新文案与倒计时基准
            title?.text = "正在上:${o.courseName}"
            detail?.text = detailOf(o)
            shortName?.text = o.courseName.take(2)
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
            Log.i(
                TAG,
                "浮窗倒计时控件文本='${timer?.text}' (期望约 ${millisUntilEnd(o) / 1000} 秒)" +
                    " 收起=${pos.collapsed} 贴边=${if (pos.anchorRight) "右" else "左"}" +
                    " x=${params?.x} y=${params?.y}",
            )
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
        shortName = TextView(this).apply {
            text = o.courseName.take(2)
            setTextColor(Color.parseColor("#F2F5FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
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
        // 收起时把名字和时间摆成一行(按时序 add 到末尾,可见性由 applyCollapsed 控制)
        root.addView(shortName)
        return root
    }

    // ---- 收起 / 展开 ----

    /**
     * 切到收起态或展开态。
     *
     * 收起 = 只留「课程名前两个字 + 倒计时」一行。**窗口会真的变窄**,
     * 而不是把内容藏起来留一块空白 —— 否则"占位最小"这个目的就没达到。
     *
     * @param snapToEdge 收起时把它贴到所在的那条边上
     */
    private fun applyCollapsed(wm: WindowManager, collapsed: Boolean, snapToEdge: Boolean, reason: String) {
        val v = view ?: return
        val p = params ?: return
        val t = title ?: return
        val d = detail ?: return
        val s = shortName ?: return

        t.visibility = if (collapsed) View.GONE else View.VISIBLE
        d.visibility = if (collapsed) View.GONE else View.VISIBLE
        s.visibility = if (collapsed) View.VISIBLE else View.GONE
        v.orientation = if (collapsed) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val pad = if (collapsed) dp(6) else dp(10)
        v.setPadding(pad, pad, pad, pad)
        (v.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius =
            (if (collapsed) dp(10) else dp(14)).toFloat()

        if (snapToEdge) p.x = dp(EDGE_MARGIN_DP)
        // 贴边的那个方向决定了 gravity 用 START 还是 END
        p.gravity = Gravity.TOP or (if (pos.anchorRight) Gravity.END else Gravity.START)

        pos.collapsed = collapsed
        runCatching { wm.updateViewLayout(v, p) }
            .onFailure { Log.e(TAG, "updateViewLayout failed", it) }
        Log.i(
            TAG,
            "浮窗${if (collapsed) "收起" else "展开"}($reason):贴${if (pos.anchorRight) "右" else "左"}边 x=${p.x} y=${p.y}",
        )
    }

    // ---- 拖动 / 点击 ----

    /**
     * 拖动与点击。
     *
     * 两条都写在同一个 `OnTouchListener` 里,是因为它们必须**共用同一个手势**:
     * 分开处理就会出现"松开手指先触发拖动、又触发一次点击"。
     * 判定用系统的 `scaledTouchSlop` —— 手指抖一下不算拖。
     */
    private fun attachTouch(v: View, wm: WindowManager) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false

        v.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val (sw, _) = screenSize(wm)
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startLeft = if (pos.anchorRight) sw - p.x - v.width else p.x
                    startTop = p.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (sw, sh) = screenSize(wm)
                        val newLeft = (startLeft + dx).toInt().coerceIn(0, (sw - v.width).coerceAtLeast(0))
                        val newTop = (startTop + dy).toInt().coerceIn(0, (sh - v.height).coerceAtLeast(0))
                        // 越过屏幕中线就换边贴 —— 这样"拖到左边缘"能真的贴左边
                        val anchorRight = newLeft + v.width / 2 >= sw / 2
                        if (anchorRight != pos.anchorRight) {
                            pos.anchorRight = anchorRight
                            Log.i(TAG, "拖动换边 → 贴${if (anchorRight) "右" else "左"}边")
                        }
                        p.gravity = Gravity.TOP or (if (anchorRight) Gravity.END else Gravity.START)
                        p.x = if (anchorRight) (sw - newLeft - v.width).coerceAtLeast(0) else newLeft
                        p.y = newTop
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        drop(wm, e.actionMasked == MotionEvent.ACTION_UP)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && pos.collapsed) {
                        // 点一下小条 → 展开回完整内容(需求要求"可预期",所以不做"拖离自动展开")
                        applyCollapsed(wm, collapsed = false, snapToEdge = false, reason = "点小条")
                    }
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 松手:贴到边缘就收起。
     *
     * 阈值取得比 `touchSlop` 大得多(28dp):判定的是"**这条浮窗离最近那条边有多近**",
     * 不是"手指动了多少"。太小会导致随手一拖就收起。
     */
    private fun drop(wm: WindowManager, committed: Boolean) {
        val v = view ?: return
        val p = params ?: return
        val (sw, _) = screenSize(wm)
        val left = if (pos.anchorRight) sw - p.x - v.width else p.x
        val dist = min(left, sw - (left + v.width))
        val shouldCollapse = dist <= dp(COLLAPSE_MARGIN_DP)

        Log.i(
            TAG,
            "松手:left=$left distFromEdge=$dist 收起=$shouldCollapse 贴${if (pos.anchorRight) "右" else "左"}边",
        )
        if (committed) {
            applyCollapsed(
                wm,
                shouldCollapse,
                snapToEdge = shouldCollapse,
                reason = if (shouldCollapse) "拖到边缘" else "拖离边缘",
            )
        }
        // ⚠️ 顺序要紧:**先贴边、再记录**。反过来的话,存下的是贴边之前的 x,
        // 下次起来就会差那 6dp(收起时贴到边上、重启后又离开一点)。
        persist(p)
    }

    /** 把当前位置与收起态写回本地 —— 跨课、跨重启都接着用,不用每节课重拖。 */
    private fun persist(p: WindowManager.LayoutParams) {
        pos.x = p.x
        pos.y = p.y
        Log.i(TAG, "已记住浮窗位置:贴${if (pos.anchorRight) "右" else "左"}边 x=${p.x} y=${p.y}")
    }

    /**
     * 还原上次的位置。
     *
     * **没有历史记录时默认贴右上**:与 V1.0.3 之前的位置一致,老用户不会觉得它跳了。
     */
    private fun WindowManager.LayoutParams.applySavedPosition() {
        gravity = Gravity.TOP or (if (pos.anchorRight) Gravity.END else Gravity.START)
        x = if (pos.x >= 0) pos.x else dp(10)
        y = if (pos.y >= 0) pos.y else dp(96)
        Log.i(TAG, "浮窗初始位置:贴${if (pos.anchorRight) "右" else "左"}边 x=$x y=$y")
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
        // 自定义层占的不是节次号 —— periodLabel() 会改显示它自己的时段
        val period = o.periodLabel()
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
        shortName = null
        params = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 屏幕尺寸(px)。API 30+ 用 `WindowMetrics`,更低版本退回 `displayMetrics`。 */
    private fun screenSize(wm: WindowManager): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val d = resources.displayMetrics
            d.widthPixels to d.heightPixels
        }

    companion object {
        private const val TAG = "XqOverlay"

        /** 贴边判定:浮窗离最近那条边小于这个 dp 数就收起。 */
        private const val COLLAPSE_MARGIN_DP = 28

        /** 收起后与屏幕边的留白 —— 贴死会顶到状态栏手势区。 */
        private const val EDGE_MARGIN_DP = 6

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
            // 晚自习 / 按时间填的临时课程自带时钟,所以不能因为"没配作息"就把整个判断跳过
            if (!times.configured && !overlays.hasOwnClock) return null
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

/**
 * 悬浮窗的位置与收起状态。
 *
 * 存的是"**贴着哪一边 + 距那一边多少 px + 距顶部多少 px**",不是绝对坐标 ——
 * 收起时窗口会变窄,绝对坐标会让它当场跳一下。存下来是刻意的:
 * 不存的话每节课都要重拖一遍(需求里专门点了这一条)。
 *
 * 用 `SharedPreferences` 而不是文件:就三个数,而且要在服务每次被拉起时立刻读到。
 */
private class OverlayPos(context: Context) {

    private val prefs = context.getSharedPreferences("class_overlay", Context.MODE_PRIVATE)

    /** 是否贴右边。默认 true —— 与 V1.0.3 之前写死的右上角一致。 */
    var anchorRight: Boolean
        get() = prefs.getBoolean("anchor_right", true)
        set(v) = prefs.edit().putBoolean("anchor_right", v).apply()

    /** 距所贴那条边的距离(px);-1 = 还没有历史记录。 */
    var x: Int
        get() = prefs.getInt("x", -1)
        set(v) = prefs.edit().putInt("x", v).apply()

    /** 距屏幕顶部(px);-1 = 还没有历史记录。 */
    var y: Int
        get() = prefs.getInt("y", -1)
        set(v) = prefs.edit().putInt("y", v).apply()

    var collapsed: Boolean
        get() = prefs.getBoolean("collapsed", false)
        set(v) = prefs.edit().putBoolean("collapsed", v).apply()
}
