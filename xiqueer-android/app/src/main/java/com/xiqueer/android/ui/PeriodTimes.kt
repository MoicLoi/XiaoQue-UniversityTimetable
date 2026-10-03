package com.xiqueer.android.ui

import com.xiqueer.android.data.PeriodTimes

/**
 * 作息表的**展示** helper。
 *
 * 刻意没有内置默认值 —— 未配置时就不显示时钟,只显示节次。
 * 数据与来源见 [com.xiqueer.android.data.PeriodTimesStore]。
 *
 * ⚠️ 名字不能叫 `PeriodTimes`:那样会和同名的数据类冲突 ——
 * 一个 `import com.xiqueer.android.data.PeriodTimes` 就会把本对象遮蔽掉。
 */
object PeriodText {

    /** 节次栏用:第 [period] 节的开始时间;未配置返回 null(此时不渲染时间)。 */
    fun startLabel(times: PeriodTimes, period: Int): String? = times.startOf(period)

    /** `第 1-2 节`;有时间时补成 `第 1-2 节(08:00-09:40)`。 */
    fun label(times: PeriodTimes, periodStart: Int, periodEnd: Int): String {
        val base = "第 $periodStart-$periodEnd 节"
        val s = times.startOf(periodStart) ?: return base
        val e = times.endOf(periodEnd)
        return if (e != null) "$base($s-$e)" else "$base($s 开始)"
    }
}
