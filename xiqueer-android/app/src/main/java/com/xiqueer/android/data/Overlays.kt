package com.xiqueer.android.data

import android.content.Context

/**
 * **本地覆盖层**的总和 —— 一切"用户/AI 写的、会改变实际要上什么"的数据。
 *
 * 为什么要打成一个包传,而不是每个覆盖层各占一个参数:
 * 覆盖层的数量只会增加(调休 → 课节覆写 → 晚自习 → 临时课程 → …),
 * 而它们的**消费方是同一个** [com.xiqueer.android.notify.ScheduleOverrides]。
 * 每加一层就给 `forDate` 多一个参数,结果是 8+ 个调用点要逐个记得补 ——
 * 而漏掉一处的症状恰恰是"某一处显示的还是老课表"(本项目在调休上已经犯过一次:
 * 课挪走了闹钟还在原日子响,以及网格页不跟着灰显)。
 *
 * 打成一个值之后,新增覆盖层**不需要改任何调用点**。
 */
data class Overlays(
    /** 调休:把某天的课挪到另一天。 */
    val shifts: List<Shift> = emptyList(),
    /** 课节覆写:改某一节的教室 / 节次。 */
    val courseOverrides: List<CourseOverride> = emptyList(),
    /** 自定义时段(晚自习)。 */
    val selfStudies: List<SelfStudySlot> = emptyList(),
    /** 临时课程(紧急调换 / 开会)。 */
    val customCourses: List<CustomCourse> = emptyList(),
) {
    /** 没有任何覆盖 —— 可以据此走"纯服务端课表"的快路径。 */
    val isEmpty: Boolean
        get() = shifts.isEmpty() && courseOverrides.isEmpty() &&
            selfStudies.isEmpty() && customCourses.isEmpty()

    /**
     * 是否存在**自带时钟**的条目(晚自习、按绝对时间填的临时课程)。
     *
     * 它回答的是:"没有作息表时,还能不能算出绝对时刻?"
     * 服务端的课只能靠作息表定时,但这些条目自己写着 `HH:mm` ——
     * 所以"没配作息就什么都提醒不了"这条**只对服务端课表成立**。
     *
     * 放在这里而不是各调用点各写一遍:浮窗、提醒调度、导出闸门三处都要用,
     * 三处各写一遍必然有一天长歪(本项目已经因为同一个规则两处实现踩过坑)。
     */
    val hasOwnClock: Boolean
        get() = selfStudies.any { it.weekdays.isNotEmpty() } ||
            customCourses.any { !it.start.isNullOrBlank() }

    companion object {
        val Empty = Overlays()

        /**
         * 从本地四个 Store 一次性读齐。
         *
         * 后台入口(闹钟接收器、Worker、悬浮窗服务)拿不到 ViewModel,
         * 但又都必须看到同一份覆盖层 —— 所以"怎么读"只放在这一处。
         */
        fun load(context: Context): Overlays = Overlays(
            shifts = ShiftStore(context).all(),
            courseOverrides = CourseOverrideStore(context).all(),
            selfStudies = SelfStudyStore(context).all(),
            customCourses = CustomCourseStore(context).all(),
        )
    }
}
