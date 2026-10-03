package com.xiqueer.android.export

import com.xiqueer.android.data.PeriodTimes
import com.xiqueer.protocol.Course
import com.xiqueer.protocol.Timetable
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 课表导出 —— **零依赖的微型引擎**。
 *
 * 为什么自己写而不是引库:
 * - `Apache POI` 光核心就几 MB,还要拖 XMLBeans,为了导出一张课表不值;
 * - xlsx 本质就是"一个 zip 里装几个 XML",而 zip 是平台自带的
 *   (`java.util.zip`),XML 拼字符串就能产出;
 * - 所以整个引擎只有这一个文件,不引任何依赖,R8 之后是几十 KB 的量级。
 *
 * 产出三种格式:
 * | 格式 | 用途 | 依赖作息表 |
 * |---|---|---|
 * | CSV | 一行一门课(规整数据),Excel 直接开 | 否 |
 * | XLSX | 双表:①课表网格(带合并单元格)②课程明细 | 否 |
 * | ICS | 日历事件,导入手机日历后由系统提醒 | **是**(没有上下课时间就排不出事件) |
 *
 * CSV 特意带 UTF-8 BOM:不然 Excel 在中文 Windows 上会按 GBK 解码,整片乱码 ——
 * 这是最常见的"导出就是个残废"的原因。
 */
object TimetableExport {

    /** 网格单元格。`span` > 1 表示这门课连上多节,导出时纵向合并。 */
    private data class Cell(var text: String, var span: Int)

    /**
     * 课程明细表头。顺序即列顺序,CSV 与 XLSX 共用。
     */
    private val DETAIL_HEADER = listOf("课程名", "教师", "教室", "节次", "周次", "学分", "课程号")

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    // ------------------------------------------------------------------ 网格

    /**
     * 排出一张 `maxPeriod × 7` 的网格。
     *
     * 同一格出现多门课时**不丢弃**,而是拼在一起(真实课表里冲突本来就应该被看见)。
     * 这里刻意不使用 UI 那套"泳道"布局:导出的目标是"看得全",
     * 不是"排得好看"。
     */
    private fun grid(t: Timetable): Array<Array<Cell?>> {
        val maxPeriod = maxOf(1, t.maxPeriod)
        val g = Array(maxPeriod) { arrayOfNulls<Cell>(7) }
        for (day in 0..6) {
            for (c in t.days.getOrElse(day) { emptyList() }) {
                val (s, e) = parsePeriods(c.periods) ?: continue
                if (s !in 1..maxPeriod) continue
                val span = (e - s + 1).coerceAtMost(maxPeriod - s + 1)
                val cell = g[s - 1][day]
                if (cell == null) {
                    g[s - 1][day] = Cell(cellText(c), span)
                } else {
                    cell.text = cell.text + "\n———\n" + cellText(c)
                    cell.span = maxOf(cell.span, span)
                }
            }
        }
        return g
    }

    private fun cellText(c: Course): String = buildString {
        append(c.name)
        if (c.room.isNotBlank()) append('\n').append(c.room)
        if (c.teacher.isNotBlank()) append('\n').append(c.teacher)
        if (c.weeks.isNotBlank()) append('\n').append(c.weeks)
    }

    // ------------------------------------------------------------------ CSV

    /** 一行一门课的规整数据。带 BOM,Excel 打开不乱码。 */
    fun csv(t: Timetable): ByteArray {
        val sb = StringBuilder()
        sb.append(DETAIL_HEADER.joinToString(",")).append("\r\n")
        for (c in allCourses(t)) {
            sb.append(
                listOf(c.name, c.teacher, c.room, c.periods, c.weeks, c.credit, c.code)
                    .joinToString(",") { csvField(it) },
            ).append("\r\n")
        }
        val body = sb.toString().toByteArray(Charsets.UTF_8)
        // EF BB BF
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
    }

    private fun csvField(s: String): String {
        val needsQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    // ------------------------------------------------------------------ XLSX

    /**
     * 最小可用的 xlsx:两个 sheet。
     *
     * 只用 `inlineStr` 写字,不建 `sharedStrings.xml` —— 少一个部件、少一层间接,
     * 对几百行的课表没有任何损失。
     */
    fun xlsx(t: Timetable, times: PeriodTimes): ByteArray {
        val maxPeriod = maxOf(1, t.maxPeriod)
        val g = grid(t)

        // ---- sheet1:课表网格 ----
        val s1 = StringBuilder()
        s1.append("""<cols><col min="1" max="1" width="7" customWidth="1"/>""")
        for (i in 2..8) s1.append("""<col min="$i" max="$i" width="17" customWidth="1"/>""")
        s1.append("</cols><sheetData>")

        // 表头行:节次 + 星期
        s1.append("""<row r="1" ht="20" customHeight="1">""")
        s1.append(cellStr("A1", "节次", STYLE_HEADER))
        for (d in 0..6) s1.append(cellStr(colLetter(d + 2) + "1", WEEKDAYS[d], STYLE_HEADER))
        s1.append("</row>")

        val merges = ArrayList<String>()
        for (p in 1..maxPeriod) {
            val r = p + 1
            s1.append("""<row r="$r" ht="42" customHeight="1">""")
            val label = buildString {
                append(p)
                times.startOf(p)?.let { append('\n').append(it.take(5)) }
            }
            s1.append(cellStr("A$r", label, STYLE_HEADER))
            for (d in 0..6) {
                val cell = g[p - 1][d]
                val ref = colLetter(d + 2) + r
                if (cell == null) {
                    s1.append("""<c r="$ref" s="$STYLE_BODY"/>""")
                } else {
                    s1.append(cellStr(ref, cell.text, STYLE_BODY))
                    if (cell.span > 1) merges.add("$ref:${colLetter(d + 2)}${r + cell.span - 1}")
                }
            }
            s1.append("</row>")
        }
        s1.append("</sheetData>")
        if (merges.isNotEmpty()) {
            s1.append("""<mergeCells count="${merges.size}">""")
            merges.forEach { s1.append("""<mergeCell ref="$it"/>""") }
            s1.append("</mergeCells>")
        }

        // ---- sheet2:课程明细 ----
        val s2 = StringBuilder()
        s2.append("""<cols>""")
        val widths = listOf(26, 12, 20, 8, 22, 7, 14)
        widths.forEachIndexed { i, w ->
            s2.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        s2.append("</cols><sheetData>")
        s2.append("""<row r="1" ht="20" customHeight="1">""")
        DETAIL_HEADER.forEachIndexed { i, h -> s2.append(cellStr(colLetter(i + 1) + "1", h, STYLE_HEADER)) }
        s2.append("</row>")
        allCourses(t).forEachIndexed { i, c ->
            val r = i + 2
            s2.append("""<row r="$r">""")
            listOf(c.name, c.teacher, c.room, c.periods, c.weeks, c.credit, c.code)
                .forEachIndexed { j, v -> s2.append(cellStr(colLetter(j + 1) + r, v, STYLE_BODY)) }
            s2.append("</row>")
        }
        s2.append("</sheetData>")

        // ---- 打包 ----
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // 约定:第一个条目是 [Content_Types].xml
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", RELS)
            zip.put("xl/workbook.xml", workbook())
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheet(s1.toString(), freezeTopLeft = true))
            zip.put("xl/worksheets/sheet2.xml", sheet(s2.toString(), freezeTopLeft = true))
        }
        return out.toByteArray()
    }

    private fun sheet(body: String, freezeTopLeft: Boolean): String {
        val views = if (freezeTopLeft) {
            // A2 冻结:首行(表头)固定,滚动时始终可见
            """<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>"""
        } else {
            """<sheetViews><sheetView workbookViewId="0"/></sheetViews>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$views$body</worksheet>"""
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun cellStr(ref: String, text: String, style: Int): String =
        """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${xml(text)}</t></is></c>"""

    /** 第 1 列 = A。列数不超过 26,不需要 AA/AB 处理。 */
    private fun colLetter(n: Int): String = ('A' + (n - 1)).toString()

    // ------------------------------------------------------------------ ICS

    /**
     * 生成日历事件。**没有作息表就返回 null** —— 不知道几点上课,排不出事件,
     * 与其猜一个时间,不如让调用方告诉用户"先填作息"。
     *
     * 不用 `RRULE`:`skzs` 支持 `4,6,8,…` 这种不连续周次,写 RRULE 要配 EXDATE,
     * 容易错。**一门课一周一个事件**,最多几百个,文件很小,而且绝对准确。
     *
     * ⚠️ 时间基准只有 `t.termStartMonday`(**第 1 周周一**)可用。
     * 早先版本改从外面传 `ClassReminder.weekMonday(t)` 进来,那是"**本周**周一" ——
     * 两者差 `currentWeek - 1` 周,结果每个日历事件都晚了 3 周。
     * 所以现在由本函数自己从课表模型里取,不给调用方传错的机会。
     */
    fun ics(t: Timetable, times: PeriodTimes): String? {
        if (!times.configured) return null
        val monday0 = t.termStartMonday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return null
        val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "T000000Z"

        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//xiqueer-cli//xiqueer android//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("METHOD:PUBLISH\r\n")
        sb.append("X-WR-CALNAME:课表\r\n")

        for (day in 0..6) {
            for (c in t.days.getOrElse(day) { emptyList() }) {
                val (s, e) = parsePeriods(c.periods) ?: continue
                val startLabel = times.startOf(s) ?: continue
                val startTime = runCatching { LocalTime.parse(startLabel) }.getOrNull() ?: continue
                val endLabel = times.endOf(e) ?: startLabel
                val endTime = runCatching { LocalTime.parse(endLabel) }.getOrNull() ?: startTime

                for (w in weeksOf(c.weeks, t.maxWeek)) {
                    val date = monday0.plusWeeks((w - 1).toLong()).plusDays(day.toLong())
                    val dt = DateTimeFormatter.ofPattern("yyyyMMdd")
                    sb.append("BEGIN:VEVENT\r\n")
                    sb.append("UID:${uid(c, day, w)}\r\n")
                    sb.append("DTSTAMP:$stamp\r\n")
                    sb.append("DTSTART;VALUE=DATE-TIME:${date.format(dt)}T${startTime.format(HM)}00\r\n")
                    sb.append("DTEND;VALUE=DATE-TIME:${date.format(dt)}T${endTime.format(HM)}00\r\n")
                    sb.append("SUMMARY:${icsText(c.name)}\r\n")
                    if (c.room.isNotBlank()) sb.append("LOCATION:${icsText(c.room)}\r\n")
                    if (c.teacher.isNotBlank()) sb.append("DESCRIPTION:${icsText(c.teacher)}\r\n")
                    // 提前 20 分钟提醒,与 App 内默认一致
                    sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT20M\r\n")
                    sb.append("DESCRIPTION:${icsText(c.name)}\r\nEND:VALARM\r\n")
                    sb.append("END:VEVENT\r\n")
                }
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    private val HM = DateTimeFormatter.ofPattern("HHmm")

    private fun uid(c: Course, day: Int, week: Int): String =
        "xq-${Integer.toHexString("${c.name}|$day|$week".hashCode())}@xiqueer"

    /** ICS 的字段值要转义逗号/分号/反斜杠,换行写成 `\n`。 */
    private fun icsText(s: String): String = s
        .replace("\\", "\\\\")
        .replace(",", "\\,")
        .replace(";", "\\;")
        .replace("\n", "\\n")

    // ------------------------------------------------------------------ 公共

    /** 课表里出现的全部课程(按星期/节次排序,去重)。 */
    internal fun allCourses(t: Timetable): List<Course> = t.days
        .flatten()
        .distinctBy { "${it.name}|${it.periods}|${it.room}|${it.teacher}|${it.code}" }
        .sortedWith(compareBy({ it.periods }, { it.name }))

    /** `jcxx` = "1-2" / "3" / "1-4"。 */
    internal fun parsePeriods(jcxx: String): Pair<Int, Int>? {
        val parts = jcxx.split('-', '~', '－', '–').mapNotNull { it.trim().toIntOrNull() }
        return when {
            parts.size >= 2 -> parts[0] to maxOf(parts[0], parts[1])
            parts.size == 1 -> parts[0] to parts[0]
            else -> null
        }
    }

    /**
     * `skzs` 展开成周次集合。实测两种写法:`3-10周`(区间)与 `4,6,8周`(枚举),
     * 另外兼容 `单`/`双` 与全角标点。空串按"每周都上"。
     */
    internal fun weeksOf(skzs: String, maxWeek: Int): List<Int> {
        val raw = skzs.trim()
        if (raw.isEmpty()) return (1..maxWeek).toList()
        val odd = raw.contains("单")
        val even = raw.contains("双")
        val body = raw
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("（[^）]*）"), "")
            .replace("周", "")
        val weeks = sortedSetOf<Int>()
        for (part in body.split(',', '，', '、')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = p.split('-', '~', '－').map { it.trim() }
            if (range.size >= 2) {
                val a = range[0].toIntOrNull() ?: continue
                val b = range[1].toIntOrNull() ?: continue
                for (w in minOf(a, b)..maxOf(a, b)) weeks.add(w)
            } else {
                p.toIntOrNull()?.let { weeks.add(it) }
            }
        }
        if (weeks.isEmpty()) return (1..maxWeek).toList()
        return weeks.filter { w ->
            w in 1..maxWeek && !(odd && w % 2 == 0) && !(even && w % 2 == 1)
        }
    }

    private fun xml(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            // xlsx 里换行必须用 <br/> 之外的实体形式,否则 Excel 不认这个换行
            '\n' -> append("&#10;")
            else -> if (ch.code < 0x20 && ch != '\t') Unit else append(ch)
        }
    }

    // 单元格样式索引,与下面 STYLES 里的 cellXfs 顺序一一对应
    private const val STYLE_BODY = 1
    private const val STYLE_HEADER = 2

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>
<sheet name="课表" sheetId="1" r:id="rId1"/>
<sheet name="课程明细" sheetId="2" r:id="rId2"/>
</sheets>
</workbook>"""

    private fun workbook() = WORKBOOK

    /**
     * 最小 styles.xml。
     *
     * `<cellStyles>` 那一行不能省:第一版漏了它,openpyxl 直接警告
     * "Workbook contains no default style" —— 说明文件在"默认样式"这块不合规。
     * Excel 宽容,但别的读者未必,所以补上。
     */
    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="等线"/></font><font><b/><sz val="11"/><name val="等线"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="2"><border/>
<border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="3">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
