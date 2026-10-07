package com.xiqueer.android.data

import com.xiqueer.protocol.Course

/**
 * 一节课在**底表(服务端课表)**里的身份。
 *
 * 用四个字段拼:`课程名|节次|教室|教师`。
 *
 * ⚠️ **必须用底表的 [Course] 算,不能用覆写之后的值** ——
 * 课节覆写改的正是"节次"和"教室"这两项,拿覆写后的值去算,
 * 改完之后就再也认不出自己当初改的是哪一节了。
 *
 * 只在**一处**实现:这个键同时被「课程详情浮层记住选中的是哪节课」
 * 和「课节覆写的定位」使用,两份实现迟早会分叉 ——
 * 一处分叉的症状是"点开详情后详情对不上",很难查。
 */
object CourseKey {

    fun of(c: Course): String = "${c.name}|${c.periods}|${c.room}|${c.teacher}"
}
