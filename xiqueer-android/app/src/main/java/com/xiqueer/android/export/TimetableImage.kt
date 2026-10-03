package com.xiqueer.android.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.xiqueer.android.CourseColors
import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.android.data.Shift
import com.xiqueer.android.notify.ClassReminder
import com.xiqueer.android.notify.ScheduleOverrides
import com.xiqueer.protocol.Timetable
import com.xiqueer.protocol.XqFeatures
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把整周课表画成一张 PNG。
 *
 * 为什么要有这个:手机里最常发生的分享动作是**把课表截图发群里**,
 * 而截图会带上状态栏、时间、电量,别人还得自己裁剪。
 * 直接给一张干净的图,比自己截好得多。
 *
 * 三条实现约束:
 *
 * 1. **不引任何依赖**。`android.graphics.Canvas` 就够了 ——
 *    与 [TimetableExport] 的"零依赖 xlsx"是同一个原则:能用系统的就不加库。
 * 2. **数据源必须是 [ScheduleOverrides.week]**。导出图和屏幕上看到的
 *    必须是同一份数据,否则会出现"截图里有调休、导出图里没有"这种最尴尬的不一致。
 * 3. **不依赖任何 UI 状态**:不读 Compose、不读屏幕尺寸。
 *    给定课表就能画出图,这样它可以在任何线程、任何时机被调用。
 */
object TimetableImage {

    // ---- 版面(单位:像素,按"分享出去要看清楚"来定,不跟屏幕密度走)----
    private const val PAD = 30f
    private const val GUTTER = 104f
    private const val CELL_W = 200f
    private const val CELL_H = 130f
    private const val TITLE_H = 138f
    private const val HEADER_H = 100f
    private const val FOOTER_H = 58f

    // ---- 配色(深色,和 App 里那张网格同一族)----
    private const val BG = 0xFF0B0F16.toInt()
    private const val LINE = 0x18FFFFFF
    private const val TEXT_HI = 0xFFF2F5FA.toInt()
    private const val TEXT_MID = 0xFFB9C2D0.toInt()
    private const val TEXT_LO = 0xFF7C8798.toInt()
    private const val ACCENT = 0xFF6FA8FF.toInt()
    private const val GRAY = 0xFF6B7280.toInt()
    private const val TODAY_BG = 0x146FA8FF

    fun png(t: Timetable, times: PeriodTimes, shifts: List<Shift>): ByteArray {
        val maxPeriod = maxOf(1, t.maxPeriod).coerceAtMost(14)
        val w = (PAD * 2 + GUTTER + CELL_W * 7).toInt()
        val h = (PAD * 2 + TITLE_H + HEADER_H + CELL_H * maxPeriod + FOOTER_H).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        val week = ScheduleOverrides.week(t, times, shifts)
        val monday = ClassReminder.weekMonday(t)
        val gridTop = PAD + TITLE_H + HEADER_H

        drawTitle(c, t)
        drawHeader(c, t, monday, gridTop - HEADER_H)

        // 今天所在列(仅当正在看的那一周就是本周时才有意义)
        val todayCol = todayColumn(t)

        for (day in 0..6) {
            val x0 = PAD + GUTTER + CELL_W * day
            if (day == todayCol) {
                paint(0xFF000000.toInt()).let { p ->
                    p.color = TODAY_BG
                    p.style = Paint.Style.FILL
                    c.drawRect(x0, gridTop, x0 + CELL_W, gridTop + CELL_H * maxPeriod, p)
                }
            }
            // 行分隔线
            val line = strokePaint(LINE, 1f)
            for (p in 0..maxPeriod) {
                val y = gridTop + CELL_H * p
                c.drawLine(x0, y, x0 + CELL_W, y, line)
            }
        }
        // 列分隔线
        val vline = strokePaint(LINE, 1f)
        for (day in 0..7) {
            val x = PAD + GUTTER + CELL_W * day
            c.drawLine(x, gridTop, x, gridTop + CELL_H * maxPeriod, vline)
        }

        drawGutter(c, t, times, maxPeriod, gridTop)
        for (day in 0..6) drawColumn(c, week.getOrElse(day) { emptyList() }, day, maxPeriod, gridTop)
        drawFooter(c, t, h)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // ---- 各块 ----

    private fun drawTitle(c: Canvas, t: Timetable) {
        val big = textPaint(TEXT_HI, 46f, bold = true)
        c.drawText("第 ${t.currentWeek} 周", PAD, PAD + 52f, big)

        val small = textPaint(TEXT_LO, 24f)
        val range = listOfNotNull(t.weekStart, t.weekEnd).joinToString(" ~ ")
        c.drawText(
            "$range  ·  共 ${maxOf(1, t.maxWeek)} 周  ·  每天 ${maxOf(1, t.maxPeriod)} 节",
            PAD, PAD + 92f, small,
        )
    }

    private fun drawHeader(c: Canvas, t: Timetable, monday: LocalDate?, headerTop: Float) {
        val dayName = textPaint(TEXT_MID, 27f, bold = true)
        val dateTxt = textPaint(TEXT_LO, 21f)

        // 左上角空出来的格子写个「节次」,免得那一栏看着没头
        c.drawText("节次", PAD + 8f, headerTop + 44f, dateTxt)

        for (d in 0..6) {
            val cx = PAD + GUTTER + CELL_W * d + CELL_W / 2f
            val label = XqFeatures.weekdayName(d)
            val isToday = d == todayColumn(t)
            dayName.color = if (isToday) ACCENT else TEXT_MID
            c.drawText(label, cx - dayName.measureText(label) / 2f, headerTop + 42f, dayName)

            val date = monday?.plusDays(d.toLong())
            if (date != null) {
                val s = "${date.monthValue}/${date.dayOfMonth}"
                dateTxt.color = if (isToday) ACCENT else TEXT_LO
                c.drawText(s, cx - dateTxt.measureText(s) / 2f, headerTop + 74f, dateTxt)
            }
        }
    }

    private fun drawGutter(
        c: Canvas,
        t: Timetable,
        times: PeriodTimes,
        maxPeriod: Int,
        gridTop: Float,
    ) {
        val num = textPaint(TEXT_MID, 26f)
        val time = textPaint(TEXT_LO, 18f)
        for (p in 1..maxPeriod) {
            val cy = gridTop + CELL_H * (p - 1)
            val label = "$p"
            c.drawText(label, PAD + 20f, cy + 46f, num)
            // 没填作息就不画时间 —— 绝不用猜的时间填空
            times.startOf(p)?.let { s ->
                c.drawText(s, PAD + 58f, cy + 46f, time)
            }
        }
    }

    private fun drawColumn(
        c: Canvas,
        col: List<com.xiqueer.android.notify.EffectiveCourse>,
        day: Int,
        maxPeriod: Int,
        gridTop: Float,
    ) {
        // 与网格页同一套:按节次分泳道,目标日本来就有课时并排两列
        val items = col.mapNotNull { e ->
            val o = e.occurrence
            if (o.periodStart !in 1..maxPeriod) null
            else Item(o.courseName, o.periodStart, o.periodEnd, o.room, e.movedTo, e.movedFrom)
        }.sortedWith(compareBy({ it.start }, { -it.end }))

        val laneEnds = ArrayList<Int>()
        val laneOf = ArrayList<Int>()
        for (item in items) {
            var lane = laneEnds.indexOfFirst { it < item.start }
            if (lane < 0) {
                laneEnds.add(item.end)
                lane = laneEnds.size - 1
            } else {
                laneEnds[lane] = item.end
            }
            laneOf.add(lane)
        }
        val lanes = maxOf(1, laneEnds.size)

        val x0 = PAD + GUTTER + CELL_W * day
        items.forEachIndexed { i, item ->
            val lane = laneOf[i]
            val laneW = CELL_W / lanes
            val left = x0 + laneW * lane + 4f
            val right = x0 + laneW * (lane + 1) - 4f
            val top = gridTop + CELL_H * (item.start - 1) + 4f
            val bottom = gridTop + CELL_H * item.end - 4f
            drawBlock(c, item, left, top, right, bottom)
        }
    }

    private fun drawBlock(
        c: Canvas,
        item: Item,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val disabled = item.movedOut != null
        val base = if (disabled) GRAY else CourseColors.of(item.name)

        val fill = fillPaint(if (disabled) 0x33 else 0x5A, base)
        val rf = RectF(left, top, right, bottom)
        c.drawRoundRect(rf, 14f, 14f, fill)

        val padX = 10f
        val innerW = right - left - padX * 2
        val namePaint = textPaint(if (disabled) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt(), 22f, bold = true)
        val roomPaint = textPaint(if (disabled) 0x88FFFFFF.toInt() else 0xCCFFFFFF.toInt(), 18f)

        var y = top + 30f
        for (line in wrap(namePaint, item.name, innerW, if (item.end - item.start >= 1) 3 else 2)) {
            c.drawText(line, left + padX, y, namePaint)
            y += 25f
        }
        if (item.room.isNotBlank() && item.end - item.start >= 1) {
            for (line in wrap(roomPaint, item.room, innerW, 2)) {
                c.drawText(line, left + padX, y, roomPaint)
                y += 21f
            }
        }
        // 调休痕迹:与网格页一致
        val mark = when {
            item.movedOut != null -> "→ ${item.movedOut.monthValue}-${item.movedOut.dayOfMonth}"
            item.movedIn != null -> "调休"
            else -> null
        }
        if (mark != null && y < bottom - 6f) {
            val markPaint = textPaint(if (disabled) 0xB3FFFFFF.toInt() else ACCENT, 17f)
            c.drawText(mark, left + padX, y + 4f, markPaint)
        }
    }

    private fun drawFooter(c: Canvas, t: Timetable, h: Int) {
        val p = textPaint(TEXT_LO, 19f)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        val note = "导出时间 $stamp" + if (t.term.isNotBlank()) "  ·  ${t.term}" else ""
        c.drawText(note, PAD, h - PAD + 4f, p)
    }

    // ---- 小工具 ----

    private data class Item(
        val name: String,
        val start: Int,
        val end: Int,
        val room: String,
        val movedOut: LocalDate?,
        val movedIn: LocalDate?,
    )

    private fun todayColumn(t: Timetable): Int {
        val start = t.weekStart ?: return -1
        return runCatching {
            val diff = java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.parse(start), LocalDate.now()).toInt()
            if (diff in 0..6) diff else -1
        }.getOrDefault(-1)
    }

    private fun paint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun fillPaint(alpha: Int, base: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 保留课程色的 RGB,只调透明度 —— 深色底上直接混白会脏
        color = (base and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun strokePaint(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
    }

    private fun textPaint(color: Int, size: Float, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /**
     * 按宽度折行。中文没有词边界,所以**逐字符**折 ——
     * 按空格分词对中文课名完全无效。
     *
     * 超出 [maxLines] 时在末行加省略号,宁可截断也不要画到格子外面去。
     */
    private fun wrap(p: Paint, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val flat = text.replace('\n', ' ').trim()
        if (flat.isEmpty() || maxLines <= 0 || maxWidth <= 0f) return emptyList()

        val out = ArrayList<String>()
        val sb = StringBuilder()
        var truncated = false
        for (ch in flat) {
            if (p.measureText(sb.toString() + ch) > maxWidth && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
                if (out.size >= maxLines) {
                    truncated = true
                    break
                }
            }
            sb.append(ch)
        }
        if (!truncated && sb.isNotEmpty() && out.size < maxLines) out.add(sb.toString())
        if (out.isEmpty()) return emptyList()

        if (truncated || (sb.isNotEmpty() && out.size >= maxLines)) {
            var last = out[out.size - 1]
            while (last.isNotEmpty() && p.measureText(last + "…") > maxWidth) {
                last = last.dropLast(1)
            }
            out[out.size - 1] = last + "…"
        }
        return out
    }
}
