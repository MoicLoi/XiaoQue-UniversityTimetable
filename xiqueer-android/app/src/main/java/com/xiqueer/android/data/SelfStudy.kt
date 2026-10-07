package com.xiqueer.android.data

import android.content.Context
import android.util.Log
import com.xiqueer.protocol.XqJson
import java.io.File

/**
 * 一个**自定义时段** —— 目前用来表示晚自习。
 *
 * 它不属于服务端课表的任何一节:接口的 `maxPeriod` 只到白天的课,
 * 晚自习既没有节次号、也不在 `weekN` 数组里。所以它由用户在本地定义,
 * 并且要**被当成真的一节课**参与课表网格、日程页与上课提醒
 * (用户明确要求;否则会出现"网格里有、日程里没有、也不提醒"的两套口径)。
 *
 * 时间用 `"HH:mm"` 字符串存,与 [PeriodTimes] 保持同一套表示与校验 ——
 * 两处各写一套时间格式解析迟早会走偏。
 */
data class SelfStudySlot(
    val id: String,
    val label: String = DEFAULT_LABEL,
    /** `"19:00"` */
    val start: String,
    /** `"20:30"`;可缺省 —— 缺了就只有开始时间。 */
    val end: String? = null,
    /**
     * 启用它的星期,**0=周一 … 6=周日**(与 `Timetable.days` 同一套下标)。
     * 空列表按"每天都不启用"处理,而不是"每天都启用" —— 后者在用户误清空时会静默加课。
     */
    val weekdays: List<Int> = listOf(0, 1, 2, 3, 4),
) {
    fun enabledOn(weekday: Int): Boolean = weekday in weekdays

    /** `19:00-20:30` 或只有 `19:00`。 */
    fun timeLabel(): String = if (end.isNullOrBlank()) start else "$start-$end"

    /** `周一、周二、周三、周四、周五`;空列表给一句人话而不是空串。 */
    fun weekdayLabel(): String =
        if (weekdays.isEmpty()) "未选择"
        else weekdays.sorted().joinToString("、") { com.xiqueer.protocol.XqFeatures.weekdayName(it) }

    companion object {
        const val DEFAULT_LABEL = "晚自习"

        /** 校验并规整一条输入;不合法返回 null(调用方据此给用户人话解释)。 */
        fun of(
            label: String,
            start: String,
            end: String?,
            weekdays: List<Int>,
            id: String = "ss-${System.currentTimeMillis()}",
        ): SelfStudySlot? {
            val s = start.trim()
            if (!PeriodTimes.isClock(s)) return null
            val e = end?.trim()?.takeIf { it.isNotEmpty() }
            if (e != null && !PeriodTimes.isClock(e)) return null
            // 结束必须晚于开始 —— 否则提醒与"是否上完"的判断都会反过来
            if (e != null && toMinutes(e) <= toMinutes(s)) return null
            val days = weekdays.filter { it in 0..6 }.distinct().sorted()
            return SelfStudySlot(
                id = id,
                label = label.trim().ifEmpty { DEFAULT_LABEL },
                start = s,
                end = e,
                weekdays = days,
            )
        }

        /** `"19:00"` → 1140。调用前必须已通过 [PeriodTimes.isClock]。 */
        fun toMinutes(clock: String): Int =
            clock.substringBefore(':').toInt() * 60 + clock.substringAfter(':').toInt()
    }
}

/**
 * 自定义时段(晚自习)的**本地覆盖层**。
 *
 * 与 [ShiftStore] / [CourseOverrideStore] 同一个原则:底表只由刷新写,
 * 本层只由用户 / AI 写,两个写者不相交。
 */
class SelfStudyStore(context: Context) {

    private val file = File(context.filesDir, "self_study.json")

    fun all(): List<SelfStudySlot> {
        if (!file.exists()) return emptyList()
        return runCatching { parse(file.readText()) }.getOrElse {
            Log.w(TAG, "晚自习数据损坏,已丢弃", it)
            file.delete()
            emptyList()
        }
    }

    /**
     * 覆盖式保存:**整份列表一次写**。
     *
     * 晚自习的条目本来就少(通常一条),逐条增删反而让 UI 要维护更多中间状态。
     * 校验不过的条目会被丢弃,返回实际落地的列表 —— 调用方拿它回显。
     */
    fun save(slots: List<SelfStudySlot>): List<SelfStudySlot> {
        val clean = slots.mapNotNull { s ->
            SelfStudySlot.of(s.label, s.start, s.end, s.weekdays, s.id)
        }
        write(clean)
        return clean
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun write(list: List<SelfStudySlot>) {
        val json = list.joinToString(",", "[", "]") { s ->
            buildString {
                append("""{"id":${JsonText.quote(s.id)}""")
                append(""","label":${JsonText.quote(s.label)}""")
                append(""","start":${JsonText.quote(s.start)}""")
                append(""","end":${JsonText.quoteOrNull(s.end)}""")
                append(""","weekdays":${JsonText.quoteList(s.weekdays.map { it.toString() })}}""")
            }
        }
        file.writeText(json)
    }

    private fun parse(text: String): List<SelfStudySlot> =
        XqJson.parseArray(text).mapNotNull { it as? Map<*, *> }.mapNotNull { m ->
            val start = m["start"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // weekdays 可能是数字数组,也可能是字符串数组(两边都容忍,免得改格式时炸)
            val days = (m["weekdays"] as? List<*>)?.mapNotNull { v ->
                (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
            }.orEmpty()
            SelfStudySlot.of(
                label = m["label"]?.toString().orEmpty(),
                start = start,
                end = m["end"]?.toString(),
                weekdays = days,
                id = m["id"]?.toString() ?: "ss-$start",
            )
        }

    private companion object {
        const val TAG = "XqSelfStudy"
    }
}
